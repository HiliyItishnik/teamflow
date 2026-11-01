package com.teamflow.task;

import com.teamflow.common.PageResponse;
import com.teamflow.common.Pagination;
import com.teamflow.common.ResourceNotFoundException;
import com.teamflow.project.Project;
import com.teamflow.project.ProjectService;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class TaskService {
    private final TaskRepository repository;
    private final ProjectService projects;

    public TaskService(TaskRepository repository, ProjectService projects) {
        this.repository = repository;
        this.projects = projects;
    }

    public PageResponse<TaskResponse> list(UUID workspaceId, UUID projectId,
            TaskStatus status, TaskPriority priority, int page, int size) {
        projects.requireProject(workspaceId, projectId);
        Specification<Task> filter = (root, query, cb) -> cb.equal(root.get("project").get("id"), projectId);
        if (status != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (priority != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("priority"), priority));
        }
        return PageResponse.from(repository.findAll(filter, Pagination.of(page, size)).map(TaskResponse::from));
    }

    public TaskResponse get(UUID workspaceId, UUID projectId, UUID taskId) {
        return TaskResponse.from(requireTask(workspaceId, projectId, taskId));
    }

    @Transactional
    public TaskResponse create(UUID workspaceId, UUID projectId, TaskRequest request) {
        Project project = projects.requireProject(workspaceId, projectId);
        Task task = new Task(project, request.title(), request.description(), request.status(),
                request.priority(), request.dueDate());
        return TaskResponse.from(repository.saveAndFlush(task));
    }

    @Transactional
    public TaskResponse update(UUID workspaceId, UUID projectId, UUID taskId, TaskRequest request) {
        Task task = requireTask(workspaceId, projectId, taskId);
        task.update(request.title(), request.description(), request.status(), request.priority(), request.dueDate());
        repository.flush();
        return TaskResponse.from(task);
    }

    @Transactional
    public void delete(UUID workspaceId, UUID projectId, UUID taskId) {
        repository.delete(requireTask(workspaceId, projectId, taskId));
    }

    private Task requireTask(UUID workspaceId, UUID projectId, UUID taskId) {
        projects.requireProject(workspaceId, projectId);
        return repository.findByIdAndProjectId(taskId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found in project: " + taskId));
    }
}
