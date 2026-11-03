package com.teamflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.teamflow.auth.PasswordResetMail;
import com.teamflow.job.JobDelivery;
import com.teamflow.job.JobRunner;
import com.teamflow.job.JobStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DailyWorkApiIT extends PostgresIntegrationTest {
    @Autowired JobStore store;
    @Autowired JobDelivery delivery;
    @Autowired JobRunner runner;
    @MockitoBean PasswordResetMail mail;
    private JsonNode owner, member;
    private String workspace, project, tasks;

    @BeforeEach void setup() throws Exception {
        clearDatabase();
        owner = register("owner@example.test"); member = register("member@example.test");
        workspace = "/api/v1/workspaces/" + body(call("POST", "/api/v1/workspaces", owner, Map.of("name", "Team"), null).andExpect(status().isCreated())).path("id").asText();
        call("POST", workspace + "/members", owner, Map.of("email", "member@example.test", "role", "MEMBER"), null).andExpect(status().isCreated());
        project = workspace + "/projects/" + body(call("POST", workspace + "/projects", owner, Map.of("name", "Project"), null).andExpect(status().isCreated())).path("id").asText();
        call("POST", project + "/members", owner, Map.of("userId", userId(member)), null).andExpect(status().isCreated());
        tasks = project + "/tasks";
        when(mail.available()).thenReturn(true);
    }

    @Test void servesBrowserShellWithCspButApiRemainsProtected() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("index.html"))
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("script-src 'self'")));
        mvc.perform(get("/index.html")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("TeamFlow")));
        mvc.perform(get("/assets/app.js")).andExpect(status().isOk());
        mvc.perform(get("/assets/app.css")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(get(project + "/filters")).andExpect(status().isUnauthorized());
    }

    @Test void searchesTitleAndDescriptionLiterallyBeforePagination() throws Exception {
        task("100% API_one", "literal search", "TODO", null);
        task("Other", "Find API in the description", "TODO", null);
        task("1000 APIzone", null, "TODO", null);
        call("GET", tasks + "?q=API&size=1", member, null, null).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(get(tasks).param("q", "  api  ").param("size", "1").param("page", "1").header("Authorization", bearer(member)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1)).andExpect(jsonPath("$.totalElements").value(3));
        for (String literal : List.of("%", "_", "100% API_one")) mvc.perform(get(tasks).param("q", literal).header("Authorization", bearer(member)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get(tasks).param("q", "x".repeat(201)).header("Authorization", bearer(member))).andExpect(status().isBadRequest());
    }

    @Test void overdueUsesUtcAndExcludesDoneTodayFutureAndNoDate() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        var late = task("Late", null, "TODO", today.minusDays(1).toString());
        task("Completed", null, "DONE", today.minusDays(2).toString());
        task("Today", null, "TODO", today.toString());
        task("Future", null, "TODO", today.plusDays(1).toString());
        task("No date", null, "TODO", null);
        assign(late, member).andExpect(status().isOk());
        call("GET", tasks + "?overdue=true&priority=HIGH&status=TODO&assigneeId=" + userId(member), member, null, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].title").value("Late"));
        call("GET", tasks + "?overdue=true&status=DONE", member, null, null).andExpect(jsonPath("$.totalElements").value(0));
        call("GET", tasks + "?overdue=false", member, null, null).andExpect(jsonPath("$.totalElements").value(5));
    }

    @Test void savedFiltersArePrivateVersionedAndRecheckProjectAccess() throws Exception {
        Map<String, Object> data = Map.of("name", "My urgent", "q", "API", "priority", "HIGH", "overdue", true);
        JsonNode filter = body(call("POST", project + "/filters", member, data, null).andExpect(status().isCreated()).andExpect(header().exists("ETag")));
        String path = project + "/filters/" + filter.path("id").asText();
        call("GET", project + "/filters", owner, null, null).andExpect(jsonPath("$.length()").value(0));
        call("GET", project + "/filters", member, null, null).andExpect(jsonPath("$[0].q").value("API"));
        call("PUT", path, owner, data, etag(filter)).andExpect(status().isNotFound());
        call("PUT", path, member, data, null).andExpect(status().isPreconditionRequired());
        JsonNode updated = body(call("PUT", path, member, Map.of("name", "Updated", "overdue", false), etag(filter)).andExpect(status().isOk()));
        call("DELETE", path, member, null, etag(filter)).andExpect(status().isPreconditionFailed());
        call("POST", project + "/filters", member, Map.of("name", "Updated"), null).andExpect(status().isConflict());
        call("DELETE", project + "/members/" + userId(member), owner, null, null).andExpect(status().isNoContent());
        call("GET", project + "/filters", member, null, null).andExpect(status().isNotFound());
        call("DELETE", path, member, null, etag(updated)).andExpect(status().isNotFound());
    }

    @Test void savedFiltersCanBeDeletedAndLimitIsEnforced() throws Exception {
        JsonNode f = body(call("POST", project + "/filters", member, Map.of("name", "Delete me"), null).andExpect(status().isCreated()));
        call("DELETE", project + "/filters/" + f.path("id").asText(), member, null, etag(f)).andExpect(status().isNoContent());
        for (int i = 0; i < 50; i++) call("POST", project + "/filters", member, Map.of("name", "Filter " + i), null).andExpect(status().isCreated());
        call("POST", project + "/filters", member, Map.of("name", "Too many"), null).andExpect(status().isConflict());
        call("GET", project + "/filters", member, null, null).andExpect(jsonPath("$.length()").value(50));
    }

    @Test void assignmentAndCommentEnqueueTransactionallyAndIdempotentReplayDoesNotDuplicate() throws Exception {
        JsonNode task = task("Task", null, "TODO", null);
        JsonNode assigned = body(assign(task, member).andExpect(status().isOk()));
        assign(assigned, member).andExpect(status().isOk());
        assertThat(count("background_jobs")).isEqualTo(1);
        assertThat(count("notifications")).isZero();
        String key = UUID.randomUUID().toString();
        for (int i = 0; i < 2; i++) mvc.perform(post(tasks + "/" + task.path("id").asText() + "/comments")
                .header("Authorization", bearer(owner)).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Please review\"}"))
                .andExpect(status().isCreated());
        assertThat(count("background_jobs")).isEqualTo(2);
        runner.runBatch(); runner.runBatch();
        assertThat(count("notifications")).isEqualTo(2);
        call("GET", "/api/v1/notifications", owner, null, null).andExpect(jsonPath("$.totalElements").value(0));
        call("GET", "/api/v1/notifications", member, null, null).andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test void outboxFailureRollsBackAssignmentAndComment() throws Exception {
        JsonNode task = task("Task", null, "TODO", null);
        jdbc.execute("ALTER TABLE background_jobs ADD CONSTRAINT test_block_event CHECK (kind = 'PASSWORD_RECOVERY')");
        try {
            assign(task, member).andExpect(status().isConflict());
            call("POST", tasks + "/" + task.path("id").asText() + "/comments", owner, Map.of("body", "Must roll back"), null).andExpect(status().isConflict());
            assertThat(count("task_comments")).isZero();
            assertThat(jdbc.queryForObject("SELECT assignee_id FROM tasks", UUID.class)).isNull();
            assertThat(count("background_jobs")).isZero();
        } finally { jdbc.execute("ALTER TABLE background_jobs DROP CONSTRAINT test_block_event"); }
    }

    @Test void notificationReadAndCountsArePrivateAndRevokedAccessIsHidden() throws Exception {
        assign(task("Task", null, "TODO", null), member).andExpect(status().isOk()); runner.runBatch();
        JsonNode items = body(call("GET", "/api/v1/notifications", member, null, null).andExpect(status().isOk()));
        String path = "/api/v1/notifications/" + items.path("content").get(0).path("id").asText() + "/read";
        call("GET", "/api/v1/notifications/unread-count", member, null, null).andExpect(jsonPath("$.count").value(1));
        call("PUT", path, owner, null, null).andExpect(status().isNotFound());
        call("PUT", path, member, null, null).andExpect(status().isNoContent());
        call("PUT", path, member, null, null).andExpect(status().isNoContent());
        call("GET", "/api/v1/notifications?unread=true", member, null, null).andExpect(jsonPath("$.totalElements").value(0));
        call("DELETE", project + "/members/" + userId(member), owner, null, null).andExpect(status().isNoContent());
        call("GET", "/api/v1/notifications", member, null, null).andExpect(jsonPath("$.totalElements").value(0));
        call("PUT", path, member, null, null).andExpect(status().isNotFound());
    }

    @Test void removedRecipientsAreSkippedAndDeletedTasksCascadeJobs() throws Exception {
        JsonNode task = body(assign(task("Task", null, "TODO", null), member).andExpect(status().isOk()));
        call("DELETE", project + "/members/" + userId(member), owner, null, null).andExpect(status().isNoContent());
        runner.runBatch(); assertThat(count("notifications")).isZero();
        JsonNode current = body(call("GET", tasks + "/" + task.path("id").asText(), owner, null, null).andExpect(status().isOk()));
        call("DELETE", tasks + "/" + current.path("id").asText(), owner, null, etag(current)).andExpect(status().isNoContent());
        assertThat(count("background_jobs")).isZero();
    }

    @Test void failedCompletionRollsBackNotificationAndRetryProducesExactlyOne() throws Exception {
        assign(task("Task", null, "TODO", null), member).andExpect(status().isOk());
        jdbc.execute("ALTER TABLE background_jobs ADD CONSTRAINT test_block_complete CHECK (completed_at IS NULL)");
        try {
            runner.runBatch();
            assertThat(count("notifications")).isZero();
            var row = jdbc.queryForMap("SELECT attempts, last_error, available_at > now() AS delayed FROM background_jobs");
            assertThat(row).containsEntry("attempts", 1).containsEntry("delayed", true);
            assertThat(row.get("last_error")).isNotNull();
        } finally { jdbc.execute("ALTER TABLE background_jobs DROP CONSTRAINT test_block_complete"); }
        jdbc.update("UPDATE background_jobs SET available_at = now() - interval '1 second'");
        new JobRunner(store, delivery).runBatch();
        runner.runBatch(); assertThat(count("notifications")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attempts FROM background_jobs", Integer.class)).isEqualTo(2);
    }

    @Test void leaseRecoveryAndStaleWorkerCannotDuplicateDelivery() throws Exception {
        assign(task("Task", null, "TODO", null), member).andExpect(status().isOk());
        var abandoned = store.claim(); assertThat(abandoned).isNotNull(); assertThat(store.claim()).isNull();
        jdbc.update("UPDATE background_jobs SET leased_until = now() - interval '1 second'");
        var recovered = store.claim(); assertThat(recovered.claimId()).isNotEqualTo(abandoned.claimId());
        delivery.deliver(abandoned); assertThat(count("notifications")).isZero();
        delivery.deliver(recovered); delivery.deliver(recovered);
        assertThat(count("notifications")).isEqualTo(1);
    }

    @Test void concurrentWorkersDeliverEachEventOnce() throws Exception {
        for (int i = 0; i < 4; i++) assign(task("Task " + i, null, "TODO", null), member).andExpect(status().isOk());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(runner::runBatch); var second = pool.submit(runner::runBatch);
            first.get(15, TimeUnit.SECONDS); second.get(15, TimeUnit.SECONDS);
        }
        assertThat(count("notifications")).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT sum(attempts) FROM background_jobs", Integer.class)).isEqualTo(4);
    }

    @Test void permanentFailureStopsAfterFiveAttemptsAndAbandonedFinalLeaseBecomesFailed() throws Exception {
        assign(task("Task", null, "TODO", null), member).andExpect(status().isOk());
        jdbc.execute("ALTER TABLE notifications ADD CONSTRAINT test_reject_notifications CHECK (false)");
        try {
            for (int i = 0; i < 6; i++) { jdbc.update("UPDATE background_jobs SET available_at = now() - interval '1 second'"); runner.runBatch(); }
            assertThat(jdbc.queryForObject("SELECT attempts FROM background_jobs", Integer.class)).isEqualTo(5);
            assertThat(jdbc.queryForObject("SELECT failed_at IS NOT NULL FROM background_jobs", Boolean.class)).isTrue();
        } finally { jdbc.execute("ALTER TABLE notifications DROP CONSTRAINT test_reject_notifications"); }
        jdbc.update("UPDATE background_jobs SET failed_at = NULL, leased_until = now() - interval '1 second'");
        assertThat(store.claim()).isNull();
        assertThat(jdbc.queryForObject("SELECT last_error FROM background_jobs", String.class)).isEqualTo("LeaseExpired");
    }

    @Test void durableRecoveryRetriesMailAndSkipsSupersededRequests() throws Exception {
        AtomicReference<String> code = new AtomicReference<>();
        doThrow(new org.springframework.mail.MailSendException("Synthetic failure")).doAnswer(call -> { code.set(call.getArgument(1)); return null; })
                .when(mail).send(anyString(), anyString());
        call("POST", "/api/v1/auth/password/forgot", null, Map.of("email", "member@example.test"), null).andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject("SELECT email FROM background_jobs", String.class)).isEqualTo("member@example.test");
        runner.runBatch(); assertThat(count("password_resets")).isZero();
        jdbc.update("UPDATE background_jobs SET available_at = now() - interval '1 second'");
        runner.runBatch(); assertThat(code.get()).isNotBlank(); assertThat(count("password_resets")).isEqualTo(1);
        call("POST", "/api/v1/auth/password/forgot", null, Map.of("email", "owner@example.test"), null).andExpect(status().isAccepted());
        call("POST", "/api/v1/auth/password/forgot", null, Map.of("email", "owner@example.test"), null).andExpect(status().isAccepted());
        runner.runBatch(); verify(mail, times(1)).send(eq("owner@example.test"), anyString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM background_jobs WHERE completed_at IS NOT NULL", Integer.class)).isEqualTo(3);
    }

    private JsonNode register(String email) throws Exception { return body(call("POST", "/api/v1/auth/register", null, Map.of("email", email, "displayName", "User", "password", "Correct-password-123"), null).andExpect(status().isCreated())); }
    private String userId(JsonNode actor) { return actor.path("user").path("id").asText(); }
    private String bearer(JsonNode actor) { return "Bearer " + actor.path("accessToken").asText(); }
    private String etag(JsonNode item) { return "\"" + item.path("id").asText() + ":" + item.path("version").asLong() + "\""; }
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }
    private JsonNode task(String title, String description, String status, String date) throws Exception {
        var data = new HashMap<String, Object>(); data.put("title", title); data.put("description", description); data.put("status", status); data.put("priority", "HIGH"); data.put("dueDate", date);
        return body(call("POST", tasks, owner, data, null).andExpect(status().isCreated()));
    }
    private ResultActions assign(JsonNode task, JsonNode assignee) throws Exception { return call("PUT", tasks + "/" + task.path("id").asText() + "/assignee", owner, Map.of("userId", userId(assignee)), etag(task)); }
    private JsonNode body(ResultActions result) throws Exception { return json.readTree(result.andReturn().getResponse().getContentAsString()); }
    private ResultActions call(String method, String path, JsonNode actor, Object data, String etag) throws Exception {
        var request = request(org.springframework.http.HttpMethod.valueOf(method), path);
        if (actor != null) request.header("Authorization", bearer(actor));
        if (data != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(data));
        if (etag != null) request.header("If-Match", etag);
        return mvc.perform(request);
    }
}
