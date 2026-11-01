package com.teamflow;

import com.teamflow.common.ResourceNotFoundException;
import com.teamflow.project.ProjectController;
import com.teamflow.project.ProjectService;
import com.teamflow.task.TaskController;
import com.teamflow.task.TaskService;
import com.teamflow.workspace.WorkspaceController;
import com.teamflow.workspace.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({WorkspaceController.class, ProjectController.class, TaskController.class})
class ApiValidationTest {
    @Autowired MockMvc mvc;
    @MockitoBean WorkspaceService workspaces;
    @MockitoBean ProjectService projects;
    @MockitoBean TaskService tasks;

    private static final String ROOT = "/api/v1/workspaces";
    private static final String PROJECTS = ROOT + "/" + UUID.randomUUID() + "/projects";
    private static final String TASKS = PROJECTS + "/" + UUID.randomUUID() + "/tasks";

    @ParameterizedTest
    @ValueSource(strings = {"?page=-1", "?size=0", "?size=101", "?page=abc"})
    void rejectsInvalidPagination(String query) throws Exception {
        for (String path : new String[]{ROOT, PROJECTS, TASKS}) {
            mvc.perform(get(path + query))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }
        verifyNoInteractions(workspaces, projects, tasks);
    }

    @Test
    void reportsFieldErrorsForBlankName() throws Exception {
        mvc.perform(post(ROOT).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists());
        verifyNoInteractions(workspaces);
    }

    @Test
    void rejectsOversizedProjectName() throws Exception {
        mvc.perform(post(PROJECTS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + "x".repeat(121) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists());
        verifyNoInteractions(projects);
    }

    @Test
    void reportsMissingTaskFields() throws Exception {
        mvc.perform(post(TASKS).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.title").exists())
                .andExpect(jsonPath("$.errors.status").exists())
                .andExpect(jsonPath("$.errors.priority").exists());
        verifyNoInteractions(tasks);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{", "{\"name\":\"Workspace\",\"ownerId\":\"injected\"}", "null"
    })
    void rejectsMalformedOrUnexpectedBody(String body) throws Exception {
        mvc.perform(post(ROOT).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(workspaces);
    }

    @Test
    void rejectsUnknownTaskStatus() throws Exception {
        mvc.perform(get(TASKS).param("status", "BROKEN")).andExpect(status().isBadRequest());
        mvc.perform(post(TASKS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"API\",\"status\":\"BROKEN\",\"priority\":\"HIGH\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(tasks);
    }

    @Test
    void invalidUuidIsBadRequest() throws Exception {
        mvc.perform(get(ROOT + "/not-a-uuid")).andExpect(status().isBadRequest());
        verifyNoInteractions(workspaces);
    }

    @Test
    void notFoundUsesProblemDetails() throws Exception {
        when(workspaces.get(any())).thenThrow(new ResourceNotFoundException("Workspace not found"));
        String path = ROOT + "/" + UUID.randomUUID();
        mvc.perform(get(path))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Workspace not found"))
                .andExpect(jsonPath("$.instance").value(path));
    }
}
