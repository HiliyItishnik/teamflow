package com.teamflow;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReliabilityApiIT extends PostgresIntegrationTest {
    private String admin, member, memberId, workspace, project, tasks, task;
    private record Reply(JsonNode body, String etag) { String id() { return body.get("id").asText(); } }

    @BeforeEach
    void setup() throws Exception {
        clearDatabase();
        admin = register("admin").body.get("accessToken").asText();
        var account = register("member").body;
        member = account.get("accessToken").asText();
        memberId = account.at("/user/id").asText();
        workspace = "/api/v1/workspaces/" + send(201, "POST", "/api/v1/workspaces", admin, Map.of("name", "Team"), null, null).id();
        send(201, "POST", workspace + "/members", admin, Map.of("email", "member@example.test", "role", "MEMBER"), null, null);
        project = workspace + "/projects/" + send(201, "POST", workspace + "/projects", admin, Map.of("name", "Project"), null, null).id();
        send(201, "POST", project + "/members", admin, Map.of("userId", memberId), null, null);
        tasks = project + "/tasks";
        task = tasks + "/" + send(201, "POST", tasks, admin, taskBody("Original", "TODO"), null, null).id();
    }

    @Test
    void requiresAnExactVersionAndDoesNotLoseChangesFromAnOlderForm() throws Exception {
        var initial = get(task);
        assertThat(initial.etag).contains(initial.id()).endsWith(":0\"");
        send(428, "PUT", task, admin, taskBody("Missing", "DONE"), null, null);
        send(400, "PUT", task, admin, taskBody("Wildcard", "DONE"), "*", null);
        send(400, "PUT", task, admin, taskBody("Weak", "DONE"), "W/" + initial.etag, null);
        var changed = send(200, "PUT", task, member, taskBody("Newer", "IN_PROGRESS"), initial.etag, null);
        assertThat(changed.etag).isNotEqualTo(initial.etag);
        send(412, "PUT", task, admin, taskBody("Old form", "DONE"), initial.etag, null);
        send(412, "DELETE", task, admin, null, initial.etag, null);
        assertThat(get(task).body.get("title").asText()).isEqualTo("Newer");
        assertThat(get(task + "/history").body.get("totalElements").asInt()).isEqualTo(2);
    }

    @Test
    void protectsAssignmentAndForeignResourceEtags() throws Exception {
        var initial = get(task);
        send(412, "PUT", task, admin, taskBody("Foreign", "DONE"), get(project).etag, null);
        send(428, "PUT", task + "/assignee", admin, Map.of("userId", memberId), null, null);
        var assigned = send(200, "PUT", task + "/assignee", admin, Map.of("userId", memberId), initial.etag, null);
        assertThat(assigned.etag).isNotEqualTo(initial.etag);
        send(412, "PUT", task, admin, taskBody("Old", "DONE"), initial.etag, null);
        send(204, "DELETE", project + "/members/" + memberId, admin, null, null, null);
        assertThat(get(task).etag).isNotEqualTo(assigned.etag);
        send(412, "PUT", task + "/assignee", admin, Map.of(), assigned.etag, null);
    }

    @Test
    void workspaceProjectAndCommentFormsAlsoRequireCurrentVersions() throws Exception {
        for (String path : List.of(workspace, project)) {
            var before = get(path);
            send(428, "PUT", path, admin, Map.of("name", "Updated"), null, null);
            send(428, "DELETE", path, admin, null, null, null);
            send(200, "PUT", path, admin, Map.of("name", "Updated"), before.etag, null);
            send(412, "DELETE", path, admin, null, before.etag, null);
        }
        var comment = send(201, "POST", task + "/comments", member, Map.of("body", "Original"), null, null);
        String path = task + "/comments/" + comment.id();
        assertThat(send(200, "GET", path, member, null, null, null).etag).isEqualTo(comment.etag);
        send(428, "PUT", path, member, Map.of("body", "Edited"), null, null);
        send(403, "PUT", path, admin, Map.of("body", "Not mine"), comment.etag, null);
        var edited = send(200, "PUT", path, member, Map.of("body", "Edited"), comment.etag, null);
        send(412, "DELETE", path, admin, null, comment.etag, null);
        send(204, "DELETE", path, admin, null, edited.etag, null);
    }

    @Test
    void replaysTaskCreationWithoutDuplicateTaskOrHistory() throws Exception {
        String key = UUID.randomUUID().toString();
        var first = send(201, "POST", tasks, member, taskBody("Retry", "TODO"), null, key);
        var retry = send(201, "POST", tasks, member, taskBody("Retry", "TODO"), null, key);
        assertThat(retry).isEqualTo(first);
        assertThat(get(tasks).body.get("totalElements").asInt()).isEqualTo(2);
        assertThat(get(tasks + "/" + first.id() + "/history").body.get("totalElements").asInt()).isEqualTo(1);
        send(409, "POST", tasks, member, taskBody("Different", "TODO"), null, key);
        send(400, "POST", tasks, member, taskBody("Bad key", "TODO"), null, "not-a-uuid");
    }

    @Test
    void replaysOriginalResponseAfterEditingOrDeletingResourceWithoutResurrectingIt() throws Exception {
        String key = UUID.randomUUID().toString();
        var first = send(201, "POST", tasks, admin, taskBody("Retry", "TODO"), null, key);
        String path = tasks + "/" + first.id();
        var updated = send(200, "PUT", path, admin, taskBody("Edited", "DONE"), first.etag, null);
        assertThat(send(201, "POST", tasks, admin, taskBody("Retry", "TODO"), null, key)).isEqualTo(first);
        send(204, "DELETE", path, admin, null, updated.etag, null);
        assertThat(send(201, "POST", tasks, admin, taskBody("Retry", "TODO"), null, key)).isEqualTo(first);
        send(404, "GET", path, admin, null, null, null);
        assertThat(get(tasks).body.get("totalElements").asInt()).isEqualTo(1);
    }

    @Test
    void commentRetriesAreScopedByAuthorAndTaskAndRetainDeletedResponses() throws Exception {
        String key = UUID.randomUUID().toString();
        String comments = task + "/comments";
        var first = send(201, "POST", comments, member, Map.of("body", "Retry"), null, key);
        assertThat(send(201, "POST", comments, member, Map.of("body", "Retry"), null, key)).isEqualTo(first);
        send(409, "POST", comments, member, Map.of("body", "Changed"), null, key);
        var otherAuthor = send(201, "POST", comments, admin, Map.of("body", "Retry"), null, key);
        assertThat(otherAuthor.id()).isNotEqualTo(first.id());
        var otherTask = send(201, "POST", tasks, member, taskBody("Other", "TODO"), null, key);
        var otherComment = send(201, "POST", tasks + "/" + otherTask.id() + "/comments", member, Map.of("body", "Retry"), null, key);
        assertThat(otherComment.id()).isNotEqualTo(first.id());
        send(204, "DELETE", comments + "/" + first.id(), member, null, first.etag, null);
        assertThat(send(201, "POST", comments, member, Map.of("body", "Retry"), null, key)).isEqualTo(first);
        assertThat(get(comments).body.get("totalElements").asInt()).isEqualTo(1);
    }

    @Test
    void retriesRecheckMembershipAndFailedRequestsDoNotConsumeKeys() throws Exception {
        String key = UUID.randomUUID().toString();
        send(400, "POST", tasks, member, taskBody("", "TODO"), null, key);
        var created = send(201, "POST", tasks, member, taskBody("Valid", "TODO"), null, key);
        send(204, "DELETE", project + "/members/" + memberId, admin, null, null, null);
        send(404, "POST", tasks, member, taskBody("Valid", "TODO"), null, key);
        assertThat(get(tasks + "/" + created.id()).body.get("title").asText()).isEqualTo("Valid");
    }

    @Test
    void rollbackDoesNotLeaveResourceHistoryOrReservedKey() throws Exception {
        String key = UUID.randomUUID().toString();
        jdbc.execute("ALTER TABLE task_status_history ADD CONSTRAINT test_reject_done CHECK (to_status <> 'DONE')");
        try {
            send(409, "POST", tasks, admin, taskBody("Roll back", "DONE"), null, key);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM creation_requests", Long.class)).isZero();
            assertThat(get(tasks).body.get("totalElements").asInt()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM task_status_history", Long.class)).isEqualTo(1);
        } finally {
            jdbc.execute("ALTER TABLE task_status_history DROP CONSTRAINT test_reject_done");
        }
        send(201, "POST", tasks, admin, taskBody("Retry after failure", "TODO"), null, key);
    }

    @Test
    void concurrentRetriesCreateExactlyOneTaskAndOneComment() throws Exception {
        for (String path : List.of(tasks, task + "/comments")) {
            String key = UUID.randomUUID().toString();
            Object body = path.equals(tasks) ? taskBody("Concurrent", "TODO") : Map.of("body", "Concurrent");
            var start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var futures = List.of(1, 2).stream().map(i -> pool.submit(() -> {
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timeout");
                    return send(201, "POST", path, member, body, null, key);
                })).toList();
                start.countDown();
                assertThat(futures.get(0).get(30, TimeUnit.SECONDS)).isEqualTo(futures.get(1).get(30, TimeUnit.SECONDS));
            }
        }
        assertThat(get(tasks).body.get("totalElements").asInt()).isEqualTo(2);
        assertThat(get(task + "/comments").body.get("totalElements").asInt()).isEqualTo(1);
    }

    @Test
    void concurrentEditorsUsingTheSameEtagCannotBothCommit() throws Exception {
        String etag = get(task).etag;
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = List.of("IN_PROGRESS", "DONE").stream().map(next -> pool.submit(() -> {
                start.await();
                return mvc.perform(request(HttpMethod.PUT, task).header("Authorization", "Bearer " + member)
                        .header("If-Match", etag).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(taskBody("Race", next))))
                        .andReturn().getResponse().getStatus();
            })).toList();
            start.countDown();
            assertThat(List.of(futures.get(0).get(30, TimeUnit.SECONDS), futures.get(1).get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 412);
        }
        assertThat(get(task + "/history").body.get("totalElements").asInt()).isEqualTo(2);
    }

    @Test
    void archiveIsReadOnlyPreservesDataAndCanBeRestoredByAdmin() throws Exception {
        var comment = send(201, "POST", task + "/comments", member, Map.of("body", "Keep"), null, null);
        String etag = get(project).etag;
        send(403, "PUT", project + "/archive", member, Map.of("archived", true), etag, null);
        send(428, "PUT", project + "/archive", admin, Map.of("archived", true), null, null);
        var archived = send(200, "PUT", project + "/archive", admin, Map.of("archived", true), etag, null);
        assertThat(archived.body.get("archived").asBoolean()).isTrue();
        assertThat(get(workspace + "/projects").body.get("totalElements").asInt()).isZero();
        assertThat(get(workspace + "/projects?archived=true").body.get("totalElements").asInt()).isEqualTo(1);
        assertThat(get(task + "/comments").body.get("totalElements").asInt()).isEqualTo(1);
        assertThat(get(task + "/history").body.get("totalElements").asInt()).isEqualTo(1);
        send(409, "PUT", project, admin, Map.of("name", "Change"), archived.etag, null);
        send(409, "POST", tasks, admin, taskBody("New", "TODO"), null, null);
        send(409, "PUT", task, member, taskBody("Changed", "DONE"), get(task).etag, null);
        send(409, "PUT", task + "/assignee", admin, Map.of("userId", memberId), get(task).etag, null);
        send(409, "DELETE", task, admin, null, get(task).etag, null);
        send(409, "POST", task + "/comments", member, Map.of("body", "New"), null, null);
        send(409, "PUT", task + "/comments/" + comment.id(), member, Map.of("body", "Changed"), comment.etag, null);
        send(409, "DELETE", task + "/comments/" + comment.id(), admin, null, comment.etag, null);
        send(409, "DELETE", project + "/members/" + memberId, admin, null, null, null);
        send(409, "POST", project + "/members", admin, Map.of("userId", memberId), null, null);
        send(412, "PUT", project + "/archive", admin, Map.of("archived", false), etag, null);
        send(200, "PUT", project + "/archive", admin, Map.of("archived", false), archived.etag, null);
        send(200, "PUT", task, member, taskBody("Restored", "DONE"), get(task).etag, null);
    }

    @Test
    void deletingArchivedProjectCascadesItsDataAndReplayRecords() throws Exception {
        send(201, "POST", tasks, admin, taskBody("Cached", "TODO"), null, UUID.randomUUID().toString());
        var archived = send(200, "PUT", project + "/archive", admin, Map.of("archived", true), get(project).etag, null);
        send(204, "DELETE", project, admin, null, archived.etag, null);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM creation_requests", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tasks", Long.class)).isZero();
    }

    private Map<String, String> taskBody(String title, String state) { return Map.of("title", title, "status", state, "priority", "HIGH"); }
    private Reply get(String path) throws Exception { return send(200, "GET", path, admin, null, null, null); }
    private Reply register(String name) throws Exception {
        return send(201, "POST", "/api/v1/auth/register", null,
                Map.of("email", name + "@example.test", "displayName", name, "password", "Reliability-password-123"), null, null);
    }
    private Reply send(int expected, String method, String path, String token, Object payload, String etag, String key) throws Exception {
        var builder = request(HttpMethod.valueOf(method), path);
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (etag != null) builder.header("If-Match", etag);
        if (key != null) builder.header("Idempotency-Key", key);
        if (payload != null) builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload));
        var response = mvc.perform(builder).andExpect(status().is(expected)).andReturn().getResponse();
        if (expected >= 400) assertThat(response.getContentType()).startsWith("application/problem+json");
        return new Reply(response.getContentAsString().isEmpty() ? null : json.readTree(response.getContentAsString()), response.getHeader("ETag"));
    }
}
