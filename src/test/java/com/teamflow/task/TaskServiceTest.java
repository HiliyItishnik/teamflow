package com.teamflow.task;

import com.teamflow.common.ResourceNotFoundException;
import com.teamflow.project.Project;
import com.teamflow.project.ProjectService;
import com.teamflow.projectmember.ProjectMemberRepository;
import com.teamflow.history.TaskStatusChangeRepository;
import com.teamflow.security.CurrentUser;
import com.teamflow.membership.WorkspaceAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {
    @Mock TaskRepository repository;
    @Mock ProjectService projects;
    @Mock Project project;
    @Mock ProjectMemberRepository members;
    @Mock TaskStatusChangeRepository history;
    @Mock CurrentUser currentUser;
    @Mock WorkspaceAccess access;
    @InjectMocks TaskService service;

    private final UUID workspaceId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private final TaskRequest request = new TaskRequest("API", null, TaskStatus.TODO, TaskPriority.HIGH, null);

    enum Operation { READ, UPDATE, DELETE }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void cannotAccessTaskThroughAnotherWorkspace(Operation operation) {
        when(projects.requireProject(workspaceId, projectId))
                .thenThrow(new ResourceNotFoundException("Project not found"));

        assertThatThrownBy(() -> execute(operation)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void cannotAccessTaskThroughAnotherProject(Operation operation) {
        when(projects.requireProject(workspaceId, projectId)).thenReturn(project);
        when(repository.findByIdAndProjectId(taskId, projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> execute(operation)).isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).delete(any(Task.class));
        verify(repository, never()).flush();
    }

    @Test
    void cannotCreateTaskInProjectOutsideWorkspace() {
        when(projects.requireWritableProject(workspaceId, projectId))
                .thenThrow(new ResourceNotFoundException("Project not found"));

        assertThatThrownBy(() -> service.create(workspaceId, projectId, request, null))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(repository);
    }

    private void execute(Operation operation) {
        switch (operation) {
            case READ -> service.get(workspaceId, projectId, taskId);
            case UPDATE -> service.update(workspaceId, projectId, taskId, request, null);
            case DELETE -> service.delete(workspaceId, projectId, taskId, null);
        }
    }
}
