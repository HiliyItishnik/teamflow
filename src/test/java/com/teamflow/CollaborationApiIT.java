package com.teamflow;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CollaborationApiIT extends PostgresIntegrationTest {
    private static final String ROOT = "/api/v1/workspaces";
    record Actor(String id, String token) {}
    private Actor admin;
    private Actor member;
    private Actor other;
    private String workspace;
    private String project;
    private String task;

    @BeforeEach
    void setupTeam() throws Exception {
        clearDatabase();
        admin = register("admin");
        member = register("member");
        other = register("other");
        workspace = createPath(ROOT, admin, Map.of("name", "Team"));
        call(HttpMethod.POST, workspace + "/members", admin, Map.of("email", "member@example.test", "role", "MEMBER"))
                .andExpect(status().isCreated());
        call(HttpMethod.POST, workspace + "/members", admin, Map.of("email", "other@example.test", "role", "MEMBER"))
                .andExpect(status().isCreated());
        project = createPath(workspace + "/projects", admin, Map.of("name", "Project"));
        task = createPath(project + "/tasks", admin, taskBody("TODO"));
    }

    @Test
    void projectMembershipFiltersListsAndGuardsEveryNestedResource() throws Exception {
        String hiddenProject = createPath(workspace + "/projects", admin, Map.of("name", "Hidden"));
        String comment = createPath(task + "/comments", admin, Map.of("body", "Admin comment"));
        call(HttpMethod.GET, workspace + "/projects", member, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        for (String path : List.of(project, project + "/members", project + "/tasks", task, task + "/comments", task + "/history")) {
            call(HttpMethod.GET, path, member, null).andExpect(status().isNotFound());
        }
        call(HttpMethod.POST, project + "/tasks", member, taskBody("TODO")).andExpect(status().isNotFound());
        call(HttpMethod.PUT, task, member, taskBody("DONE")).andExpect(status().isNotFound());
        call(HttpMethod.DELETE, task, member, null).andExpect(status().isNotFound());
        call(HttpMethod.PUT, task + "/assignee", member, Map.of("userId", admin.id())).andExpect(status().isNotFound());
        call(HttpMethod.POST, task + "/comments", member, Map.of("body", "Hidden")).andExpect(status().isNotFound());
        call(HttpMethod.PUT, comment, member, Map.of("body", "Hidden")).andExpect(status().isNotFound());
        call(HttpMethod.DELETE, comment, member, null).andExpect(status().isNotFound());
        addToProject(member);
        call(HttpMethod.GET, workspace + "/projects?size=1", member, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].id").value(id(project)));
        call(HttpMethod.GET, hiddenProject, member, null).andExpect(status().isNotFound());
        call(HttpMethod.GET, task, member, null).andExpect(status().isOk());
        call(HttpMethod.GET, workspace + "/projects", admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        call(HttpMethod.GET, project + "/members?size=1", member, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void onlyAdminManagesProjectMembershipAndOnlyWorkspaceMembersAreEligible() throws Exception {
        addToProject(member);
        call(HttpMethod.POST, project + "/members", member, Map.of("userId", other.id())).andExpect(status().isForbidden());
        call(HttpMethod.DELETE, project + "/members/" + admin.id(), member, null).andExpect(status().isForbidden());
        call(HttpMethod.POST, project + "/members", admin, Map.of("userId", member.id())).andExpect(status().isConflict());
        Actor outsider = register("outsider");
        call(HttpMethod.POST, project + "/members", admin, Map.of("userId", outsider.id())).andExpect(status().isNotFound());
        call(HttpMethod.POST, project + "/members", admin, Map.of()).andExpect(status().isBadRequest());
        call(HttpMethod.GET, project + "/members?size=101", admin, null).andExpect(status().isBadRequest());
        String alienWorkspace = createPath(ROOT, outsider, Map.of("name", "Other team"));
        String wrongPath = project.replace(workspace, alienWorkspace);
        call(HttpMethod.POST, wrongPath + "/members", outsider, Map.of("userId", outsider.id())).andExpect(status().isNotFound());
        call(HttpMethod.DELETE, wrongPath + "/members/" + admin.id(), outsider, null).andExpect(status().isNotFound());
    }

    @Test
    void assignmentRequiresProjectMembershipSupportsFilteringAndExplicitUnassignment() throws Exception {
        addToProject(member);
        call(HttpMethod.PUT, task + "/assignee", member, Map.of("userId", member.id())).andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeId").value(member.id()));
        call(HttpMethod.PUT, task, member, taskBody("IN_PROGRESS")).andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeId").value(member.id()));
        createPath(project + "/tasks", admin, taskBody("TODO"));
        call(HttpMethod.GET, project + "/tasks?assigneeId=" + member.id() + "&status=IN_PROGRESS", member, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        call(HttpMethod.PUT, task + "/assignee", admin, Map.of("userId", other.id())).andExpect(status().isNotFound());
        call(HttpMethod.GET, task, admin, null).andExpect(jsonPath("$.assigneeId").value(member.id()));
        call(HttpMethod.PUT, task + "/assignee", admin, json.readTree("{\"userId\":null}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.assigneeId").doesNotExist());
        call(HttpMethod.GET, project + "/tasks?assigneeId=" + member.id(), member, null)
                .andExpect(jsonPath("$.totalElements").value(0));
        call(HttpMethod.PUT, task + "/assignee", admin, Map.of("userId", "invalid"))
                .andExpect(status().isBadRequest());
        call(HttpMethod.GET, project + "/tasks?assigneeId=invalid", admin, null).andExpect(status().isBadRequest());
    }

    @Test
    void removingProjectMemberUnassignsTasksRevokesAccessAndPreservesDiscussion() throws Exception {
        addToProject(member);
        call(HttpMethod.PUT, task + "/assignee", admin, Map.of("userId", member.id())).andExpect(status().isOk());
        String comment = createPath(task + "/comments", member, Map.of("body", "Keep this"));
        call(HttpMethod.PUT, task, member, taskBody("DONE")).andExpect(status().isOk());
        Long beforeRemoval = jdbc.queryForObject("SELECT version FROM tasks WHERE id = ?", Long.class, UUID.fromString(id(task)));
        call(HttpMethod.DELETE, project + "/members/" + member.id(), admin, null).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT version FROM tasks WHERE id = ?", Long.class, UUID.fromString(id(task))))
                .isEqualTo(beforeRemoval + 1);
        call(HttpMethod.GET, task, admin, null).andExpect(status().isOk()).andExpect(jsonPath("$.assigneeId").doesNotExist());
        call(HttpMethod.GET, task + "/comments", admin, null).andExpect(jsonPath("$.content[0].body").value("Keep this"));
        call(HttpMethod.GET, task + "/history", admin, null).andExpect(jsonPath("$.totalElements").value(2));
        call(HttpMethod.GET, task, member, null).andExpect(status().isNotFound());
        call(HttpMethod.DELETE, comment, member, null).andExpect(status().isNotFound());
        call(HttpMethod.GET, workspace, member, null).andExpect(status().isOk());
        addToProject(member);
        assertThat(jdbc.update("UPDATE tasks SET assignee_id = ?, version = version + 1 WHERE id = ? AND version = ?",
                UUID.fromString(member.id()), UUID.fromString(id(task)), beforeRemoval)).isZero();
    }

    @Test
    void workspaceRemovalClearsProjectMembershipsAndAssignmentsWithoutRestoringThemOnRejoin() throws Exception {
        addToProject(member);
        String secondProject = createPath(workspace + "/projects", admin, Map.of("name", "Second"));
        call(HttpMethod.POST, secondProject + "/members", admin, Map.of("userId", member.id())).andExpect(status().isCreated());
        String secondTask = createPath(secondProject + "/tasks", admin, taskBody("TODO"));
        for (String path : List.of(task, secondTask)) {
            call(HttpMethod.PUT, path + "/assignee", admin, Map.of("userId", member.id())).andExpect(status().isOk());
        }
        call(HttpMethod.DELETE, workspace + "/members/" + member.id(), admin, null).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM project_members WHERE user_id = ?", Long.class, UUID.fromString(member.id()))).isZero();
        for (String path : List.of(task, secondTask)) {
            call(HttpMethod.GET, path, admin, null).andExpect(jsonPath("$.assigneeId").doesNotExist());
        }
        call(HttpMethod.POST, workspace + "/members", admin, Map.of("email", "member@example.test", "role", "MEMBER"))
                .andExpect(status().isCreated());
        call(HttpMethod.GET, workspace + "/projects", member, null).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void commentAuthorControlsEditsAndAdminCanModerateWithoutImpersonatingAuthor() throws Exception {
        addToProject(member);
        addToProject(other);
        String comment = createPath(task + "/comments", member, Map.of("body", " First comment "));
        call(HttpMethod.GET, task + "/comments", other, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].body").value("First comment"))
                .andExpect(jsonPath("$.content[0].authorId").value(member.id()));
        call(HttpMethod.PUT, comment, other, Map.of("body", "Changed")).andExpect(status().isForbidden());
        call(HttpMethod.DELETE, comment, other, null).andExpect(status().isForbidden());
        call(HttpMethod.PUT, comment, admin, Map.of("body", "Impersonation")).andExpect(status().isForbidden());
        call(HttpMethod.PUT, comment, member, Map.of("body", "Edited")).andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("Edited"));
        createPath(task + "/comments", other, Map.of("body", "Second comment"));
        call(HttpMethod.GET, task + "/comments?size=1", member, null).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].body").value("Edited"));
        call(HttpMethod.GET, task + "/comments?size=1&page=1", member, null)
                .andExpect(jsonPath("$.content[0].body").value("Second comment"));
        String secondTask = createPath(project + "/tasks", admin, taskBody("TODO"));
        String wrongComment = secondTask + "/comments/" + id(comment);
        call(HttpMethod.PUT, wrongComment, member, Map.of("body", "Wrong task")).andExpect(status().isNotFound());
        call(HttpMethod.DELETE, wrongComment, admin, null).andExpect(status().isNotFound());
        call(HttpMethod.POST, task + "/comments", member, Map.of("body", " ")).andExpect(status().isBadRequest());
        call(HttpMethod.POST, task + "/comments", member, Map.of("body", "x".repeat(5001))).andExpect(status().isBadRequest());
        call(HttpMethod.POST, task + "/comments", member, Map.of("body", "Spoof", "authorId", admin.id())).andExpect(status().isBadRequest());
        call(HttpMethod.GET, task + "/comments?size=101", member, null).andExpect(status().isBadRequest());
        call(HttpMethod.DELETE, comment, admin, null).andExpect(status().isNoContent());
        String own = createPath(task + "/comments", member, Map.of("body", "Own"));
        call(HttpMethod.DELETE, own, member, null).andExpect(status().isNoContent());
    }

    @Test
    void historyRecordsOnlyRealStatusChangesWithActorAndStableVersionOrder() throws Exception {
        addToProject(member);
        call(HttpMethod.PUT, task, member, Map.of("title", "Metadata edit", "status", "TODO", "priority", "LOW"))
                .andExpect(status().isOk());
        call(HttpMethod.PUT, task + "/assignee", member, Map.of("userId", member.id())).andExpect(status().isOk());
        call(HttpMethod.GET, task + "/history", member, null).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].eventType").value("CREATED"))
                .andExpect(jsonPath("$.content[0].fromStatus").doesNotExist())
                .andExpect(jsonPath("$.content[0].actorId").value(admin.id()));
        call(HttpMethod.PUT, task, member, taskBody("IN_PROGRESS")).andExpect(status().isOk());
        call(HttpMethod.PUT, task, admin, taskBody("DONE")).andExpect(status().isOk());
        call(HttpMethod.PUT, task, admin, taskBody("DONE")).andExpect(status().isOk());
        call(HttpMethod.PUT, task, member, Map.of("title", " ", "status", "TODO", "priority", "LOW")).andExpect(status().isBadRequest());
        JsonNode history = body(call(HttpMethod.GET, task + "/history", member, null).andExpect(status().isOk()));
        assertThat(history.get("totalElements").asInt()).isEqualTo(3);
        assertThat(history.at("/content/1/fromStatus").asText()).isEqualTo("TODO");
        assertThat(history.at("/content/1/toStatus").asText()).isEqualTo("IN_PROGRESS");
        assertThat(history.at("/content/1/actorId").asText()).isEqualTo(member.id());
        assertThat(history.at("/content/2/fromStatus").asText()).isEqualTo("IN_PROGRESS");
        assertThat(history.at("/content/2/toStatus").asText()).isEqualTo("DONE");
        assertThat(history.at("/content/1/taskVersion").asLong()).isGreaterThan(0);
        assertThat(history.at("/content/2/taskVersion").asLong()).isGreaterThan(history.at("/content/1/taskVersion").asLong());
        call(HttpMethod.GET, task + "/history?size=1&page=1", member, null)
                .andExpect(jsonPath("$.content[0].toStatus").value("IN_PROGRESS"));
        call(HttpMethod.POST, task + "/history", admin, Map.of()).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void deletingUserPreservesAuthorNamesAndClearsNullableReferences() throws Exception {
        addToProject(member);
        createPath(task + "/comments", member, Map.of("body", "Remember author"));
        call(HttpMethod.PUT, task, member, taskBody("DONE")).andExpect(status().isOk());
        call(HttpMethod.PUT, task + "/assignee", admin, Map.of("userId", member.id())).andExpect(status().isOk());
        jdbc.update("DELETE FROM users WHERE id = ?", UUID.fromString(member.id()));
        call(HttpMethod.GET, task + "/comments", admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].authorId").doesNotExist()).andExpect(jsonPath("$.content[0].authorName").value("member"));
        call(HttpMethod.GET, task + "/history", admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[1].actorId").doesNotExist()).andExpect(jsonPath("$.content[1].actorName").value("member"));
        call(HttpMethod.GET, task, admin, null).andExpect(jsonPath("$.assigneeId").doesNotExist());
    }

    @Test
    void taskAndProjectDeletionCascadeToCommentsHistoryAndMemberships() throws Exception {
        createPath(task + "/comments", admin, Map.of("body", "Delete with task"));
        call(HttpMethod.DELETE, task, admin, null).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task_comments", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task_status_history", Long.class)).isZero();
        String nextTask = createPath(project + "/tasks", admin, taskBody("TODO"));
        createPath(nextTask + "/comments", admin, Map.of("body", "Delete with project"));
        call(HttpMethod.DELETE, project, admin, null).andExpect(status().isNoContent());
        for (String table : List.of("task_comments", "task_status_history", "project_members", "tasks")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class)).isZero();
        }
    }

    @Test
    void databaseRejectsAssignmentsAndMembershipsAcrossProjectAndWorkspaceBoundaries() throws Exception {
        assertThatThrownBy(() -> jdbc.update("UPDATE tasks SET assignee_id = ? WHERE id = ?",
                UUID.fromString(other.id()), UUID.fromString(id(task)))).isInstanceOf(DataIntegrityViolationException.class);
        String otherWorkspace = createPath(ROOT, other, Map.of("name", "Other team"));
        assertThatThrownBy(() -> jdbc.update("INSERT INTO project_members (id, workspace_id, project_id, user_id, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, now(), now(), 0)", UUID.randomUUID(), UUID.fromString(id(otherWorkspace)),
                UUID.fromString(id(project)), UUID.fromString(other.id()))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void concurrentStatusUpdatesKeepHistoryConsistentWithCommittedState() throws Exception {
        addToProject(member);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of("IN_PROGRESS", "DONE").stream().map(next -> executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timeout");
                return call(HttpMethod.PUT, task, member, taskBody(next)).andReturn().getResponse().getStatus();
            })).toList();
            start.countDown();
            List<Integer> results = List.of(futures.get(0).get(30, TimeUnit.SECONDS), futures.get(1).get(30, TimeUnit.SECONDS));
            assertThat(results).allMatch(code -> code == 200 || code == 412).contains(200);
            JsonNode events = body(call(HttpMethod.GET, task + "/history", admin, null)).get("content");
            assertThat(events.size()).isEqualTo(1 + results.stream().filter(code -> code == 200).count());
            for (int i = 1; i < events.size(); i++) {
                assertThat(events.get(i).get("fromStatus")).isEqualTo(events.get(i - 1).get("toStatus"));
                assertThat(events.get(i).get("taskVersion").asLong()).isGreaterThan(events.get(i - 1).get("taskVersion").asLong());
            }
            assertThat(body(call(HttpMethod.GET, task, admin, null)).get("status")).isEqualTo(events.get(events.size() - 1).get("toStatus"));
        }
    }

    @Test
    void assignmentAndRemovalRaceCannotLeaveAnIneligibleAssignee() throws Exception {
        addToProject(member);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var assigning = executor.submit(() -> {
                start.await();
                return call(HttpMethod.PUT, task + "/assignee", admin, Map.of("userId", member.id())).andReturn().getResponse().getStatus();
            });
            var removing = executor.submit(() -> {
                start.await();
                return call(HttpMethod.DELETE, project + "/members/" + member.id(), admin, null).andReturn().getResponse().getStatus();
            });
            start.countDown();
            assertThat(assigning.get(30, TimeUnit.SECONDS)).isIn(200, 404, 412);
            assertThat(removing.get(30, TimeUnit.SECONDS)).isEqualTo(204);
        }
        call(HttpMethod.GET, task, admin, null).andExpect(jsonPath("$.assigneeId").doesNotExist());
    }

    private Actor register(String name) throws Exception {
        JsonNode result = body(call(HttpMethod.POST, "/api/v1/auth/register", null,
                Map.of("email", name + "@example.test", "displayName", name, "password", "Collaboration-password-123"))
                .andExpect(status().isCreated()));
        return new Actor(result.at("/user/id").asText(), result.get("accessToken").asText());
    }

    private void addToProject(Actor actor) throws Exception {
        call(HttpMethod.POST, project + "/members", admin, Map.of("userId", actor.id())).andExpect(status().isCreated());
    }

    private ResultActions call(HttpMethod method, String path, Actor actor, Object payload) throws Exception {
        var builder = request(method, path);
        if (actor != null) builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + actor.token());
        if (payload != null) builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload));
        return performWithCurrentEtag(builder);
    }

    private JsonNode body(ResultActions action) throws Exception {
        return json.readTree(action.andReturn().getResponse().getContentAsString());
    }

    private String createPath(String path, Actor actor, Object payload) throws Exception {
        return path + "/" + body(call(HttpMethod.POST, path, actor, payload).andExpect(status().isCreated())).get("id").asText();
    }

    private Map<String, String> taskBody(String status) { return Map.of("title", "Task", "status", status, "priority", "HIGH"); }
    private String id(String path) { return path.substring(path.lastIndexOf('/') + 1); }
}
