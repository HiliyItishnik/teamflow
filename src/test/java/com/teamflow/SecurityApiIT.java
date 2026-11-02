package com.teamflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.teamflow.security.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.ResultActions;
import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SecurityApiIT extends PostgresIntegrationTest {
    private static final String ROOT = "/api/v1/workspaces";
    private static final String PASSWORD = "Correct-password-123";
    @Autowired PasswordEncoder passwords;
    @Autowired JwtEncoder jwtEncoder;

    record Actor(String id, String email, String token) {}

    @BeforeEach
    void reset() { clearDatabase(); }

    @Test
    void registrationNormalizesEmailHashesPasswordAndIssuesUsableToken() throws Exception {
        Actor actor = register("  OWNER@Example.test  ");
        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE email = 'owner@example.test'", String.class);
        assertThat(hash).isNotEqualTo(PASSWORD).startsWith("$2a$12$");
        assertThat(passwords.matches(PASSWORD, hash)).isTrue();
        call(HttpMethod.GET, "/api/v1/auth/me", actor, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(actor.id()))
                .andExpect(jsonPath("$.email").value("owner@example.test"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        performWithCurrentEtag(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(registration("OWNER@example.test"))))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad-email", "short-password", "unicode-overflow", "blank-name", "role-injection"})
    void rejectsInvalidRegistrationWithoutCreatingUser(String invalid) throws Exception {
        var body = json.valueToTree(registration("user@example.test"));
        var object = (com.fasterxml.jackson.databind.node.ObjectNode) body;
        switch (invalid) {
            case "bad-email" -> object.put("email", "not-an-email");
            case "short-password" -> object.put("password", "short");
            case "unicode-overflow" -> object.put("password", "я".repeat(37));
            case "blank-name" -> object.put("displayName", "   ");
            case "role-injection" -> object.put("role", "ADMIN");
        }
        performWithCurrentEtag(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class)).isZero();
    }

    @Test
    void loginAcceptsNormalizedEmailAndDoesNotRevealWhetherBadCredentialsExist() throws Exception {
        Actor actor = register("owner@example.test");
        String wrongPassword = performWithCurrentEtag(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", actor.email(), "password", "Wrong-password-123"))))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String unknownUser = performWithCurrentEtag(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "unknown@example.test", "password", PASSWORD))))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(wrongPassword)).isEqualTo(json.readTree(unknownUser));
        String login = performWithCurrentEtag(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", " OWNER@EXAMPLE.TEST ", "password", PASSWORD))))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn().getResponse().getContentAsString();
        String token = json.readTree(login).get("accessToken").asText();
        assertThat(token).isNotEqualTo(actor.token());
        performWithCurrentEtag(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(status().isOk());
    }

    @Test
    void protectedApiRejectsAnonymousRequestsWhileHealthAndDocsRemainPublic() throws Exception {
        String workspace = ROOT + "/" + UUID.randomUUID();
        String project = workspace + "/projects/" + UUID.randomUUID();
        String tasks = project + "/tasks";
        for (String path : new String[]{ROOT, workspace, workspace + "/projects", project, tasks, tasks + "/" + UUID.randomUUID(), workspace + "/members", "/api/v1/auth/me"}) {
            call(HttpMethod.GET, path, null, null).andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }
        call(HttpMethod.POST, ROOT, null, Map.of("name", "Forbidden")).andExpect(status().isUnauthorized());
        call(HttpMethod.PUT, workspace, null, Map.of("name", "Forbidden")).andExpect(status().isUnauthorized());
        call(HttpMethod.DELETE, workspace, null, null).andExpect(status().isUnauthorized());
        performWithCurrentEtag(get("/actuator/health")).andExpect(status().isOk());
        performWithCurrentEtag(get("/swagger-ui/index.html")).andExpect(status().isOk());
        performWithCurrentEtag(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"malformed", "expired", "wrong-issuer", "wrong-audience", "invalid-subject", "missing-expiry", "future", "wrong-key", "unsigned"})
    void rejectsInvalidTokens(String kind) throws Exception {
        Actor actor = register("token@example.test");
        String token;
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(TokenService.ISSUER).audience(List.of(TokenService.AUDIENCE))
                .subject(actor.id()).issuedAt(now.minusSeconds(3600)).expiresAt(now.plusSeconds(1800));
        switch (kind) {
            case "expired" -> claims.expiresAt(now.minusSeconds(120));
            case "wrong-issuer" -> claims.issuer("attacker");
            case "wrong-audience" -> claims.audience(List.of("another-api"));
            case "invalid-subject" -> claims.subject("not-a-uuid");
            case "missing-expiry" -> claims.claims(map -> map.remove("exp"));
            case "future" -> claims.notBefore(now.plusSeconds(600));
        }
        JwtEncoder encoder = kind.equals("wrong-key")
                ? new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(new byte[32], "HmacSHA256"))) : jwtEncoder;
        token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
        if (kind.equals("malformed")) token = "broken.jwt.token";
        if (kind.equals("unsigned")) token = "eyJhbGciOiJub25lIn0.e30.";
        performWithCurrentEtag(get(ROOT).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void usersCannotReadOrMutateAnotherWorkspaceHierarchy() throws Exception {
        Actor owner = register("owner@example.test");
        Actor outsider = register("outsider@example.test");
        String workspace = workspace(owner, "Owner team");
        workspace(outsider, "Outsider team");
        String project = project(owner, workspace);
        String task = task(owner, project);
        call(HttpMethod.GET, ROOT, outsider, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].name").value("Outsider team"));
        for (String path : new String[]{workspace, workspace + "/projects", project, project + "/tasks", task, workspace + "/members"}) {
            call(HttpMethod.GET, path, outsider, null).andExpect(status().isNotFound());
        }
        for (String path : new String[]{workspace, project, task, workspace + "/members/" + owner.id()}) {
            call(HttpMethod.DELETE, path, outsider, null).andExpect(status().isNotFound());
        }
        call(HttpMethod.PUT, workspace, outsider, Map.of("name", "Stolen")).andExpect(status().isNotFound());
        call(HttpMethod.PUT, project, outsider, Map.of("name", "Stolen")).andExpect(status().isNotFound());
        call(HttpMethod.PUT, task, outsider, taskBody()).andExpect(status().isNotFound());
        call(HttpMethod.POST, workspace + "/projects", outsider, Map.of("name", "Stolen")).andExpect(status().isNotFound());
        call(HttpMethod.POST, project + "/tasks", outsider, taskBody()).andExpect(status().isNotFound());
        call(HttpMethod.POST, workspace + "/members", outsider, Map.of("email", outsider.email(), "role", "ADMIN"))
                .andExpect(status().isNotFound());
        call(HttpMethod.PUT, workspace + "/members/" + outsider.id(), outsider, Map.of("role", "ADMIN"))
                .andExpect(status().isNotFound());
        call(HttpMethod.GET, task, owner, null).andExpect(status().isOk());
    }

    @Test
    void memberCanWorkWithTasksButCannotManageWorkspaceProjectsOrMemberships() throws Exception {
        Actor owner = register("owner@example.test");
        Actor member = register("member@example.test");
        String workspace = workspace(owner, "Team");
        String project = project(owner, workspace);
        addMember(owner, workspace, member, "MEMBER");
        call(HttpMethod.POST, project + "/members", owner, Map.of("userId", member.id())).andExpect(status().isCreated());
        call(HttpMethod.GET, workspace, member, null).andExpect(status().isOk());
        call(HttpMethod.GET, project, member, null).andExpect(status().isOk());
        call(HttpMethod.GET, workspace + "/members?size=1", member, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        String task = task(member, project);
        call(HttpMethod.PUT, task, member, Map.of("title", "Finished", "status", "DONE", "priority", "LOW"))
                .andExpect(status().isOk());
        call(HttpMethod.DELETE, task, member, null).andExpect(status().isNoContent());
        call(HttpMethod.PUT, workspace, member, Map.of("name", "Changed")).andExpect(status().isForbidden());
        call(HttpMethod.DELETE, workspace, member, null).andExpect(status().isForbidden());
        call(HttpMethod.POST, workspace + "/projects", member, Map.of("name", "Forbidden")).andExpect(status().isForbidden());
        call(HttpMethod.PUT, project, member, Map.of("name", "Forbidden")).andExpect(status().isForbidden());
        call(HttpMethod.DELETE, project, member, null).andExpect(status().isForbidden());
        call(HttpMethod.POST, workspace + "/members", member, Map.of("email", "unknown@example.test", "role", "ADMIN"))
                .andExpect(status().isForbidden());
        call(HttpMethod.PUT, workspace + "/members/" + member.id(), member, Map.of("role", "ADMIN"))
                .andExpect(status().isForbidden());
        call(HttpMethod.DELETE, workspace + "/members/" + owner.id(), member, null).andExpect(status().isForbidden());
        String ownWorkspace = workspace(member, "My other team");
        project(member, ownWorkspace); // ADMIN here, still MEMBER in the original workspace with the same JWT.
        call(HttpMethod.DELETE, project, member, null).andExpect(status().isForbidden());
    }

    @Test
    void protectsLastAdminAndAppliesRoleChangesImmediatelyWithExistingTokens() throws Exception {
        Actor first = register("first@example.test");
        Actor second = register("second@example.test");
        String workspace = workspace(first, "Team");
        String firstMember = workspace + "/members/" + first.id();
        call(HttpMethod.DELETE, firstMember, first, null).andExpect(status().isConflict());
        call(HttpMethod.PUT, firstMember, first, Map.of("role", "MEMBER")).andExpect(status().isConflict());
        addMember(first, workspace, second, "ADMIN");
        call(HttpMethod.PUT, firstMember, first, Map.of("role", "MEMBER")).andExpect(status().isOk());
        call(HttpMethod.POST, workspace + "/projects", first, Map.of("name", "Forbidden now"))
                .andExpect(status().isForbidden());
        call(HttpMethod.DELETE, firstMember, second, null).andExpect(status().isNoContent());
        call(HttpMethod.GET, workspace, first, null).andExpect(status().isNotFound());
        call(HttpMethod.GET, ROOT, first, null).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        project(second, workspace);
    }

    @Test
    void membershipRejectsDuplicatesUnknownUsersAndInvalidRoles() throws Exception {
        Actor owner = register("owner@example.test");
        Actor member = register("member@example.test");
        String workspace = workspace(owner, "Team");
        addMember(owner, workspace, member, "MEMBER");
        call(HttpMethod.POST, workspace + "/members", owner, Map.of("email", member.email(), "role", "ADMIN"))
                .andExpect(status().isConflict());
        call(HttpMethod.POST, workspace + "/members", owner, Map.of("email", "unknown@example.test", "role", "MEMBER"))
                .andExpect(status().isNotFound());
        call(HttpMethod.PUT, workspace + "/members/" + member.id(), owner, Map.of("role", 0))
                .andExpect(status().isBadRequest());
        call(HttpMethod.PUT, workspace + "/members/" + member.id(), owner, Map.of("role", "OWNER"))
                .andExpect(status().isBadRequest());
        call(HttpMethod.GET, workspace + "/members?size=101", owner, null).andExpect(status().isBadRequest());
    }

    @Test
    void concurrentDemotionsCannotRemoveBothAdministrators() throws Exception {
        Actor first = register("first@example.test");
        Actor second = register("second@example.test");
        String workspace = workspace(first, "Team");
        addMember(first, workspace, second, "ADMIN");
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of(first, second).stream().map(actor -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timeout");
                return call(HttpMethod.PUT, workspace + "/members/" + actor.id(), actor, Map.of("role", "MEMBER"))
                        .andReturn().getResponse().getStatus();
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(futures.get(0).get(30, TimeUnit.SECONDS), futures.get(1).get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_members WHERE role = 'ADMIN'", Long.class)).isEqualTo(1);
    }

    @Test
    void tokenForDeletedUserCannotCreateOrListWorkspaces() throws Exception {
        Actor actor = register("deleted@example.test");
        jdbc.update("DELETE FROM users WHERE id = ?", UUID.fromString(actor.id()));
        call(HttpMethod.GET, ROOT, actor, null).andExpect(status().isUnauthorized());
        call(HttpMethod.POST, ROOT, actor, Map.of("name", "Forbidden")).andExpect(status().isUnauthorized());
    }

    private Actor register(String email) throws Exception {
        String result = performWithCurrentEtag(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(registration(email))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(1800)).andExpect(jsonPath("$.user.password").doesNotExist())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn().getResponse().getContentAsString();
        JsonNode body = json.readTree(result);
        return new Actor(body.at("/user/id").asText(), body.at("/user/email").asText(), body.get("accessToken").asText());
    }

    private Map<String, String> registration(String email) {
        return Map.of("email", email, "displayName", "Team member", "password", PASSWORD);
    }

    private ResultActions call(HttpMethod method, String path, Actor actor, Object body) throws Exception {
        var builder = request(method, path);
        if (actor != null) builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + actor.token());
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return performWithCurrentEtag(builder);
    }

    private String workspace(Actor actor, String name) throws Exception {
        String response = call(HttpMethod.POST, ROOT, actor, Map.of("name", name)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ROOT + "/" + json.readTree(response).get("id").asText();
    }

    private String project(Actor actor, String workspace) throws Exception {
        String response = call(HttpMethod.POST, workspace + "/projects", actor, Map.of("name", "Project"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return workspace + "/projects/" + json.readTree(response).get("id").asText();
    }

    private String task(Actor actor, String project) throws Exception {
        String response = call(HttpMethod.POST, project + "/tasks", actor, taskBody())
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return project + "/tasks/" + json.readTree(response).get("id").asText();
    }

    private Map<String, String> taskBody() { return Map.of("title", "Task", "status", "TODO", "priority", "HIGH"); }

    private void addMember(Actor admin, String workspace, Actor member, String role) throws Exception {
        call(HttpMethod.POST, workspace + "/members", admin, Map.of("email", member.email(), "role", role))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.role").value(role));
    }
}
