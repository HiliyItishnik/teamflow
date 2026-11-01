package com.teamflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TeamFlowApiIT {
    private static final String ROOT = "/api/v1/workspaces";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        // An explicit disposable database can be used on machines without Docker.
        String externalUrl = System.getenv("TEAMFLOW_TEST_DB_URL");
        if (externalUrl != null && !externalUrl.isBlank()) {
            registry.add("spring.datasource.url", () -> externalUrl);
            registry.add("spring.datasource.username", () -> System.getenv("TEAMFLOW_TEST_DB_USERNAME"));
            registry.add("spring.datasource.password", () -> System.getenv("TEAMFLOW_TEST_DB_PASSWORD"));
        } else {
            PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
            postgres.start();
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE TABLE workspaces CASCADE");
    }

    @Test
    void completeCrudLifecycle() throws Exception {
        JsonNode workspace = create(ROOT, Map.of("name", " Backend team ", "description", "Learning Java"));
        String workspacePath = ROOT + "/" + workspace.get("id").asText();
        assertThat(workspace.get("name").asText()).isEqualTo("Backend team");
        assertThat(workspace.get("createdAt").asText()).isNotBlank();

        JsonNode project = create(workspacePath + "/projects", Map.of("name", "TeamFlow"));
        String projectPath = workspacePath + "/projects/" + project.get("id").asText();
        JsonNode task = create(projectPath + "/tasks", taskBody("Build API", "TODO", "HIGH"));
        String taskPath = projectPath + "/tasks/" + task.get("id").asText();

        mvc.perform(get(workspacePath)).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Backend team"));
        mvc.perform(get(projectPath)).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("TeamFlow"));
        mvc.perform(get(taskPath)).andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Build API"));

        mvc.perform(put(workspacePath).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Team\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.description").doesNotExist());
        mvc.perform(put(projectPath).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"TeamFlow API\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("TeamFlow API"));
        mvc.perform(put(taskPath).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(taskBody("Build API", "DONE", "MEDIUM"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DONE"));
        mvc.perform(get(taskPath)).andExpect(jsonPath("$.status").value("DONE"));

        mvc.perform(delete(taskPath)).andExpect(status().isNoContent());
        mvc.perform(get(taskPath)).andExpect(status().isNotFound());
        mvc.perform(delete(projectPath)).andExpect(status().isNoContent());
        mvc.perform(get(projectPath)).andExpect(status().isNotFound());
        mvc.perform(delete(workspacePath)).andExpect(status().isNoContent());
        mvc.perform(get(workspacePath)).andExpect(status().isNotFound());
    }

    @Test
    void filtersTasksAndPaginatesWithoutLeakingOtherProjects() throws Exception {
        String workspacePath = workspacePath("Team");
        String firstProject = projectPath(workspacePath, "First");
        String secondProject = projectPath(workspacePath, "Second");
        create(firstProject + "/tasks", taskBody("First high", "TODO", "HIGH"));
        create(firstProject + "/tasks", taskBody("Second high", "TODO", "HIGH"));
        create(firstProject + "/tasks", taskBody("Completed", "DONE", "HIGH"));
        create(firstProject + "/tasks", taskBody("Low", "TODO", "LOW"));
        create(secondProject + "/tasks", taskBody("Other project", "TODO", "HIGH"));

        String filtered = firstProject + "/tasks?status=TODO&priority=HIGH&size=1";
        String firstPage = mvc.perform(get(filtered)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].status").value("TODO"))
                .andReturn().getResponse().getContentAsString();
        String secondPage = mvc.perform(get(filtered + "&page=1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(firstPage).at("/content/0/id").asText())
                .isNotEqualTo(json.readTree(secondPage).at("/content/0/id").asText());
        mvc.perform(get(firstProject + "/tasks?page=20")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
        mvc.perform(get(workspacePath + "/projects?size=1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void rejectsWrongWorkspaceAndProjectOnReadsAndWrites() throws Exception {
        String firstWorkspace = workspacePath("First workspace");
        String secondWorkspace = workspacePath("Second workspace");
        String firstProject = projectPath(firstWorkspace, "First project");
        String secondProject = projectPath(firstWorkspace, "Second project");
        JsonNode task = create(firstProject + "/tasks", taskBody("Private task", "TODO", "HIGH"));
        String wrongWorkspaceProject = firstProject.replace(firstWorkspace, secondWorkspace);
        String wrongWorkspaceTask = wrongWorkspaceProject + "/tasks/" + task.get("id").asText();
        String wrongProjectTask = secondProject + "/tasks/" + task.get("id").asText();

        mvc.perform(get(wrongWorkspaceProject)).andExpect(status().isNotFound());
        mvc.perform(delete(wrongWorkspaceProject)).andExpect(status().isNotFound());
        mvc.perform(put(wrongWorkspaceProject).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Hijacked\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get(wrongWorkspaceProject + "/tasks")).andExpect(status().isNotFound());
        mvc.perform(post(wrongWorkspaceProject + "/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(taskBody("Bad", "TODO", "LOW"))))
                .andExpect(status().isNotFound());

        for (String path : new String[]{wrongWorkspaceTask, wrongProjectTask}) {
            mvc.perform(get(path)).andExpect(status().isNotFound());
            mvc.perform(delete(path)).andExpect(status().isNotFound());
            mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(taskBody("Hijacked", "DONE", "LOW"))))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(get(firstProject + "/tasks/" + task.get("id").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Private task"));
    }

    @Test
    void deletingParentsCascadesOnlyWithinTheirHierarchy() throws Exception {
        String firstWorkspace = workspacePath("First workspace");
        String secondWorkspace = workspacePath("Second workspace");
        String deletedProject = projectPath(firstWorkspace, "Delete");
        String keptProject = projectPath(firstWorkspace, "Keep until workspace deletion");
        String otherProject = projectPath(secondWorkspace, "Other workspace");
        create(deletedProject + "/tasks", taskBody("Delete", "TODO", "HIGH"));
        create(keptProject + "/tasks", taskBody("Keep", "TODO", "HIGH"));
        create(otherProject + "/tasks", taskBody("Other", "TODO", "HIGH"));

        mvc.perform(delete(deletedProject)).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tasks", Long.class)).isEqualTo(2);
        mvc.perform(delete(firstWorkspace)).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM projects", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tasks", Long.class)).isEqualTo(1);
        mvc.perform(get(otherProject + "/tasks")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").value("Other"));
    }

    @Test
    void missingParentReturnsNotFoundEvenForLists() throws Exception {
        mvc.perform(get(ROOT + "/" + UUID.randomUUID() + "/projects")).andExpect(status().isNotFound());
        String workspace = workspacePath("Team");
        mvc.perform(get(workspace + "/projects/" + UUID.randomUUID() + "/tasks"))
                .andExpect(status().isNotFound());
    }

    @Test
    void databaseConstraintsAndMigrationAreApplied() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.table_constraints "
                + "WHERE table_schema = 'public' AND constraint_type = 'FOREIGN KEY'", Long.class)).isEqualTo(2);
    }

    @Test
    void healthAndOpenApiAreAvailable() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("TeamFlow API"))
                .andExpect(jsonPath("$.paths['/api/v1/workspaces']").exists());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    private String workspacePath(String name) throws Exception {
        return ROOT + "/" + create(ROOT, Map.of("name", name)).get("id").asText();
    }

    private String projectPath(String workspacePath, String name) throws Exception {
        String path = workspacePath + "/projects";
        return path + "/" + create(path, Map.of("name", name)).get("id").asText();
    }

    private JsonNode create(String path, Object body) throws Exception {
        String response = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private Map<String, String> taskBody(String title, String status, String priority) {
        return Map.of("title", title, "status", status, "priority", priority, "dueDate", "2026-12-31");
    }
}
