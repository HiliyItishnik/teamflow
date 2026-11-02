package com.teamflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.teamflow.auth.AuthRateLimiter;
import com.teamflow.auth.AuthRetention;
import com.teamflow.auth.PasswordResetMail;
import com.teamflow.auth.TooManyAttemptsException;
import com.teamflow.security.SecretTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AccountLifecycleIT extends PostgresIntegrationTest {
    private static final String AUTH = "/api/v1/auth";
    private static final String PASSWORD = "Correct-password-123";
    private static final String NEW_PASSWORD = "Changed-password-456";
    @MockitoBean PasswordResetMail mail;
    @Autowired ThreadPoolTaskExecutor recoveryExecutor;
    @Autowired AuthRateLimiter limiter;
    @Autowired AuthRetention retention;
    private final Map<String, String> delivered = new ConcurrentHashMap<>();

    @BeforeEach
    void reset() {
        awaitIdle();
        clearDatabase();
        delivered.clear();
        when(mail.available()).thenReturn(true);
        doAnswer(call -> { delivered.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(mail).send(anyString(), anyString());
    }

    @AfterEach void finishDelivery() { awaitIdle(); }
    private void awaitIdle() {
        await().atMost(Duration.ofSeconds(10)).until(() -> recoveryExecutor.getActiveCount() == 0
                && recoveryExecutor.getThreadPoolExecutor().getQueue().isEmpty());
    }

    @Test
    void rotatesRefreshTokensKeepsAbsoluteExpiryAndStoresOnlyHashes() throws Exception {
        JsonNode actor = register("owner@example.test");
        String refresh = actor.path("refreshToken").asText();
        assertThat(refresh).hasSize(43);
        assertThat(jdbc.queryForObject("SELECT token_hash FROM refresh_tokens", String.class)).isEqualTo(SecretTokens.hash(refresh));
        assertThat(actor.path("expiresIn").asInt()).isEqualTo(900);
        JsonNode next = body(refresh(actor).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")));
        assertThat(next.path("sessionId")).isEqualTo(actor.path("sessionId"));
        assertThat(next.path("refreshExpiresAt")).isEqualTo(actor.path("refreshExpiresAt"));
        assertThat(next.path("refreshToken")).isNotEqualTo(actor.path("refreshToken"));
        me(actor).andExpect(status().isOk());
        call("GET", AUTH + "/sessions", next, null).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].current").value(true)).andExpect(jsonPath("$[0].refreshToken").doesNotExist());
        call("POST", AUTH + "/logout", next, null).andExpect(status().isNoContent());
        me(actor).andExpect(status().isUnauthorized());
        refresh(next).andExpect(status().isUnauthorized());
    }

    @Test
    void replayRevokesWholeSessionButPreservesOtherDevice() throws Exception {
        JsonNode actor = register("owner@example.test");
        JsonNode other = login("owner@example.test", PASSWORD);
        JsonNode rotated = body(refresh(actor).andExpect(status().isOk()));
        refresh(actor).andExpect(status().isUnauthorized());
        refresh(rotated).andExpect(status().isUnauthorized());
        me(rotated).andExpect(status().isUnauthorized());
        me(other).andExpect(status().isOk());
    }

    @Test
    void sessionRevocationIsOwnerOnlyAndLogoutAllIsImmediate() throws Exception {
        JsonNode actor = register("owner@example.test");
        JsonNode other = login("owner@example.test", PASSWORD);
        JsonNode outsider = register("outsider@example.test");
        call("DELETE", AUTH + "/sessions/" + actor.path("sessionId").asText(), outsider, null).andExpect(status().isNotFound());
        call("DELETE", AUTH + "/sessions/" + other.path("sessionId").asText(), actor, null).andExpect(status().isNoContent());
        me(other).andExpect(status().isUnauthorized());
        call("DELETE", AUTH + "/sessions", actor, null).andExpect(status().isNoContent());
        me(actor).andExpect(status().isUnauthorized());
        me(outsider).andExpect(status().isOk());
    }

    @Test
    void rejectsExpiredSessionsAndMalformedRefreshTokens() throws Exception {
        JsonNode actor = register("owner@example.test");
        jdbc.update("UPDATE auth_sessions SET expires_at = now() - interval '1 second'");
        me(actor).andExpect(status().isUnauthorized());
        refresh(actor).andExpect(status().isUnauthorized());
        call("POST", AUTH + "/refresh", null, Map.of("token", "unknown")).andExpect(status().isUnauthorized());
        call("POST", AUTH + "/refresh", null, Map.of("token", " ")).andExpect(status().isBadRequest());
    }

    @Test
    void simultaneousRefreshesHaveOneWinnerAndReuseRevokesWinnerToo() throws Exception {
        JsonNode actor = register("owner@example.test");
        var results = parallel(() -> refresh(actor).andReturn(), () -> refresh(actor).andReturn());
        assertThat(results.stream().map(r -> r.getResponse().getStatus())).containsExactlyInAnyOrder(200, 401);
        var winner = results.stream().filter(r -> r.getResponse().getStatus() == 200).findFirst().orElseThrow();
        JsonNode rotated = json.readTree(winner.getResponse().getContentAsString());
        me(rotated).andExpect(status().isUnauthorized());
        refresh(rotated).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens", Integer.class)).isEqualTo(2);
    }

    @Test
    void revocationAndRefreshCannotLeaveAnActiveSession() throws Exception {
        JsonNode actor = register("owner@example.test");
        var results = parallel(() -> refresh(actor).andReturn(), () -> call("POST", AUTH + "/logout", actor, null).andReturn());
        assertThat(results.get(1).getResponse().getStatus()).isEqualTo(204);
        assertThat(results.get(0).getResponse().getStatus()).isIn(200, 401);
        me(actor).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_sessions WHERE revoked_at IS NULL", Integer.class)).isZero();
    }

    @Test
    void loginLimitSurvivesFailuresNormalizesEmailAndExpires() throws Exception {
        register("owner@example.test");
        for (int i = 0; i < 10; i++) {
            call("POST", AUTH + "/login", null, Map.of("email", " OWNER@EXAMPLE.TEST ", "password", "Wrong-password-123"))
                    .andExpect(status().isUnauthorized());
        }
        call("POST", AUTH + "/login", null, Map.of("email", "owner@example.test", "password", PASSWORD))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.status").value(429));
        jdbc.update("UPDATE auth_rate_limits SET expires_at = now() - interval '1 second'");
        login("owner@example.test", PASSWORD);
    }

    @Test
    void ipLimitCannotBeBypassedWithForwardedHeader() throws Exception {
        for (int i = 0; i < 60; i++) {
            call("POST", AUTH + "/refresh", null, Map.of("token", "invalid")).andExpect(status().isUnauthorized());
        }
        mvc.perform(post(AUTH + "/refresh").header("X-Forwarded-For", "203.0.113.55")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"invalid\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
    }

    @Test
    void concurrentRateCountersDoNotLoseAttempts() throws Exception {
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 20; i++) futures.add(pool.submit(() -> {
                try { limiter.check("test", "shared", 5, 60); allowed.incrementAndGet(); }
                catch (TooManyAttemptsException expected) { rejected.incrementAndGet(); }
            }));
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        }
        assertThat(allowed.get()).isEqualTo(5);
        assertThat(rejected.get()).isEqualTo(15);
    }

    @Test
    void invitationsRequireAdminAndAcceptanceByExactEmail() throws Exception {
        JsonNode owner = register("owner@example.test");
        JsonNode member = register("member@example.test");
        JsonNode outsider = register("outsider@example.test");
        String workspace = workspace(owner);
        JsonNode invite = invite(owner, workspace, " MEMBER@EXAMPLE.TEST ", "MEMBER");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_members", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT token_hash FROM workspace_invitations", String.class))
                .isEqualTo(SecretTokens.hash(invite.path("token").asText()));
        accept(outsider, invite).andExpect(status().isNotFound());
        accept(member, invite).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("MEMBER"));
        accept(member, invite).andExpect(status().isNotFound());
        call("GET", workspace + "/invitations", owner, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].email").value("member@example.test"))
                .andExpect(jsonPath("$.content[0].token").doesNotExist()).andExpect(jsonPath("$.content[0].tokenHash").doesNotExist());
        call("GET", workspace + "/invitations", member, null).andExpect(status().isForbidden());
        call("GET", workspace + "/invitations", outsider, null).andExpect(status().isNotFound());
        call("POST", workspace + "/invitations", member, Map.of("email", "x@example.test", "role", "ADMIN"))
                .andExpect(status().isForbidden());
        call("DELETE", invitationPath(workspace, invite), owner, null).andExpect(status().isConflict());
    }

    @Test
    void invitationCanPrecedeRegistrationAndResendRevokesOldToken() throws Exception {
        JsonNode owner = register("owner@example.test");
        String workspace = workspace(owner);
        JsonNode first = invite(owner, workspace, "future@example.test", "MEMBER");
        JsonNode second = invite(owner, workspace, "future@example.test", "ADMIN");
        JsonNode future = register("future@example.test");
        accept(future, first).andExpect(status().isNotFound());
        accept(future, second).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ADMIN"));
        call("POST", workspace + "/invitations", owner, Map.of("email", "future@example.test", "role", "MEMBER"))
                .andExpect(status().isConflict());
    }

    @Test
    void expiredAndRevokedInvitationsCannotBeAcceptedAndForeignWorkspaceCannotRevoke() throws Exception {
        JsonNode owner = register("owner@example.test");
        JsonNode invited = register("invited@example.test");
        String workspace = workspace(owner);
        String otherWorkspace = workspace(owner);
        JsonNode first = invite(owner, workspace, "invited@example.test", "MEMBER");
        call("DELETE", invitationPath(otherWorkspace, first), owner, null).andExpect(status().isNotFound());
        jdbc.update("UPDATE workspace_invitations SET expires_at = now() - interval '1 second'");
        accept(invited, first).andExpect(status().isNotFound());
        JsonNode next = invite(owner, workspace, "invited@example.test", "MEMBER");
        call("DELETE", invitationPath(workspace, next), owner, null).andExpect(status().isNoContent());
        call("DELETE", invitationPath(workspace, next), owner, null).andExpect(status().isNoContent());
        accept(invited, next).andExpect(status().isNotFound());
    }

    @Test
    void concurrentAcceptCreatesExactlyOneMembership() throws Exception {
        JsonNode owner = register("owner@example.test");
        JsonNode member = register("member@example.test");
        JsonNode invite = invite(owner, workspace(owner), "member@example.test", "MEMBER");
        var results = parallel(() -> accept(member, invite).andReturn(), () -> accept(member, invite).andReturn());
        assertThat(results.stream().map(r -> r.getResponse().getStatus())).containsExactlyInAnyOrder(200, 404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_members", Integer.class)).isEqualTo(2);
    }

    @Test
    void concurrentAcceptAndRevokeHaveConsistentOutcome() throws Exception {
        JsonNode owner = register("owner@example.test");
        JsonNode member = register("member@example.test");
        String workspace = workspace(owner);
        JsonNode invite = invite(owner, workspace, "member@example.test", "MEMBER");
        var results = parallel(() -> accept(member, invite).andReturn(),
                () -> call("DELETE", invitationPath(workspace, invite), owner, null).andReturn());
        int accepted = results.getFirst().getResponse().getStatus();
        assertThat(accepted).isIn(200, 404);
        assertThat(results.get(1).getResponse().getStatus()).isEqualTo(accepted == 200 ? 409 : 204);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_members", Integer.class)).isEqualTo(accepted == 200 ? 2 : 1);
    }

    @Test
    void forgotIsGenericAndStoresOnlyHashWithLimitedLifetime() throws Exception {
        register("owner@example.test");
        String known = forgot(" OWNER@EXAMPLE.TEST ").andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String unknown = forgot("unknown@example.test").andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        assertThat(known).isEqualTo(unknown).doesNotContain("token");
        awaitIdle();
        String token = delivered.get("owner@example.test");
        assertThat(token).hasSize(43);
        assertThat(jdbc.queryForObject("SELECT token_hash FROM password_resets", String.class)).isEqualTo(SecretTokens.hash(token));
        assertThat(jdbc.queryForObject("SELECT expires_at > now() AND expires_at <= now() + interval '15 minutes' FROM password_resets", Boolean.class)).isTrue();
        verify(mail, never()).send(eq("unknown@example.test"), anyString());
    }

    @Test
    void resetChangesPasswordConsumesTokenAndRevokesAllSessions() throws Exception {
        JsonNode actor = register("owner@example.test");
        JsonNode device = login("owner@example.test", PASSWORD);
        String code = recoveryCode("owner@example.test");
        resetPassword(code).andExpect(status().isNoContent());
        resetPassword(code).andExpect(status().isUnauthorized());
        me(actor).andExpect(status().isUnauthorized());
        me(device).andExpect(status().isUnauthorized());
        refresh(actor).andExpect(status().isUnauthorized());
        call("POST", AUTH + "/login", null, Map.of("email", "owner@example.test", "password", PASSWORD)).andExpect(status().isUnauthorized());
        me(login("owner@example.test", NEW_PASSWORD)).andExpect(status().isOk());
    }

    @Test
    void replacementResetTokenInvalidatesPreviousAndExpiredTokenDoesNothing() throws Exception {
        JsonNode actor = register("owner@example.test");
        String first = recoveryCode("owner@example.test");
        String second = recoveryCode("owner@example.test");
        assertThat(first).isNotEqualTo(second);
        resetPassword(first).andExpect(status().isUnauthorized());
        call("POST", AUTH + "/password/reset", null, Map.of("token", second, "password", "short")).andExpect(status().isBadRequest());
        jdbc.update("UPDATE password_resets SET expires_at = now() - interval '1 second'");
        resetPassword(second).andExpect(status().isUnauthorized());
        me(actor).andExpect(status().isOk());
        login("owner@example.test", PASSWORD);
    }

    @Test
    void concurrentResetConsumesTokenOnceAndRefreshCannotSurviveReset() throws Exception {
        JsonNode actor = register("owner@example.test");
        String code = recoveryCode("owner@example.test");
        var results = parallel(() -> resetPassword(code).andReturn(), () -> resetPassword(code).andReturn());
        assertThat(results.stream().map(r -> r.getResponse().getStatus())).containsExactlyInAnyOrder(204, 401);
        refresh(actor).andExpect(status().isUnauthorized());
        JsonNode next = login("owner@example.test", NEW_PASSWORD);
        String another = recoveryCode("owner@example.test");
        var race = parallel(() -> resetPassword(another).andReturn(), () -> refresh(next).andReturn());
        assertThat(race.getFirst().getResponse().getStatus()).isEqualTo(204);
        assertThat(race.get(1).getResponse().getStatus()).isIn(200, 401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_sessions WHERE revoked_at IS NULL", Integer.class)).isZero();
    }

    @Test
    void resetDeliveryIsThrottledWithoutRevealingAccountAndUnavailableMailIsExplicit() throws Exception {
        register("owner@example.test");
        for (int i = 0; i < 4; i++) { forgot("owner@example.test").andExpect(status().isAccepted()); awaitIdle(); }
        verify(mail, times(3)).send(eq("owner@example.test"), anyString());
        when(mail.available()).thenReturn(false);
        forgot("owner@example.test").andExpect(status().isServiceUnavailable());
        forgot("unknown@example.test").andExpect(status().isServiceUnavailable());
    }

    @Test
    void failedMailDeliveryRollsBackResetIssuance() throws Exception {
        register("owner@example.test");
        String original = recoveryCode("owner@example.test");
        doThrow(new org.springframework.mail.MailSendException("Synthetic SMTP failure")).when(mail).send(anyString(), anyString());
        forgot("owner@example.test").andExpect(status().isAccepted());
        awaitIdle();
        assertThat(jdbc.queryForObject("SELECT token_hash FROM password_resets", String.class)).isEqualTo(SecretTokens.hash(original));
        resetPassword(original).andExpect(status().isNoContent());
    }

    @Test
    void newLoginBoundsActiveSessionsAndRevokesOldest() throws Exception {
        JsonNode actor = register("owner@example.test");
        UUID oldest = UUID.randomUUID();
        UUID owner = UUID.fromString(actor.path("user").path("id").asText());
        jdbc.update("INSERT INTO auth_sessions (id, user_id, created_at, expires_at) VALUES (?, ?, now() - interval '2 days', now() + interval '1 day')", oldest, owner);
        for (int i = 0; i < 19; i++) jdbc.update("INSERT INTO auth_sessions (id, user_id, created_at, expires_at) VALUES (?, ?, now() - interval '1 day', now() + interval '1 day')", UUID.randomUUID(), owner);
        JsonNode newest = login("owner@example.test", PASSWORD);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_sessions WHERE revoked_at IS NULL", Integer.class)).isEqualTo(20);
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM auth_sessions WHERE id = ?", Boolean.class, oldest)).isTrue();
        me(newest).andExpect(status().isOk());
    }

    @Test
    void retentionDeletesExpiredSecretsButKeepsLiveSessions() throws Exception {
        JsonNode actor = register("owner@example.test");
        JsonNode old = login("owner@example.test", PASSWORD);
        jdbc.update("UPDATE auth_sessions SET expires_at = now() - interval '2 days' WHERE id = ?", UUID.fromString(old.path("sessionId").asText()));
        recoveryCode("owner@example.test");
        jdbc.update("UPDATE password_resets SET expires_at = now() - interval '1 second'");
        jdbc.update("UPDATE auth_rate_limits SET expires_at = now() - interval '2 days'");
        retention.cleanExpired();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_sessions", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM password_resets", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_rate_limits", Integer.class)).isZero();
        me(actor).andExpect(status().isOk());
    }

    private JsonNode register(String email) throws Exception {
        return body(call("POST", AUTH + "/register", null, Map.of("email", email, "displayName", "User", "password", PASSWORD))
                .andExpect(status().isCreated()));
    }
    private JsonNode login(String email, String password) throws Exception {
        return body(call("POST", AUTH + "/login", null, Map.of("email", email, "password", password)).andExpect(status().isOk()));
    }
    private ResultActions refresh(JsonNode actor) throws Exception { return call("POST", AUTH + "/refresh", null, Map.of("token", actor.path("refreshToken").asText())); }
    private ResultActions me(JsonNode actor) throws Exception { return call("GET", AUTH + "/me", actor, null); }
    private String workspace(JsonNode owner) throws Exception {
        return "/api/v1/workspaces/" + body(call("POST", "/api/v1/workspaces", owner, Map.of("name", "Team"))
                .andExpect(status().isCreated())).path("id").asText();
    }
    private JsonNode invite(JsonNode owner, String workspace, String email, String role) throws Exception {
        return body(call("POST", workspace + "/invitations", owner, Map.of("email", email, "role", role))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store")));
    }
    private String invitationPath(String workspace, JsonNode invite) { return workspace + "/invitations/" + invite.path("invitation").path("id").asText(); }
    private ResultActions accept(JsonNode actor, JsonNode invite) throws Exception { return call("POST", "/api/v1/invitations/accept", actor, Map.of("token", invite.path("token").asText())); }
    private ResultActions forgot(String email) throws Exception { return call("POST", AUTH + "/password/forgot", null, Map.of("email", email)); }
    private String recoveryCode(String email) throws Exception {
        forgot(email).andExpect(status().isAccepted()); awaitIdle(); return delivered.get(email);
    }
    private ResultActions resetPassword(String code) throws Exception { return call("POST", AUTH + "/password/reset", null, Map.of("token", code, "password", NEW_PASSWORD)); }
    private ResultActions call(String method, String path, JsonNode actor, Object data) throws Exception {
        var request = request(org.springframework.http.HttpMethod.valueOf(method), path);
        if (actor != null) request.header("Authorization", "Bearer " + actor.path("accessToken").asText());
        if (data != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(data));
        return mvc.perform(request);
    }
    private JsonNode body(ResultActions response) throws Exception { return json.readTree(response.andReturn().getResponse().getContentAsString()); }
    @SafeVarargs
    private final List<MvcResult> parallel(Callable<MvcResult>... actions) throws Exception {
        try (var pool = Executors.newFixedThreadPool(actions.length)) {
            CountDownLatch start = new CountDownLatch(1);
            var futures = new ArrayList<Future<MvcResult>>();
            for (var action : actions) futures.add(pool.submit(() -> { start.await(); return action.call(); }));
            start.countDown();
            var results = new ArrayList<MvcResult>();
            for (var future : futures) results.add(future.get(20, TimeUnit.SECONDS));
            return results;
        }
    }
}
