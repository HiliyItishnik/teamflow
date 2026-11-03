package com.teamflow.task;

import com.teamflow.common.PageResponse;
import com.teamflow.common.VersionCondition;
import com.teamflow.common.CreationRequests;
import com.teamflow.common.Pagination;
import com.teamflow.common.ResourceNotFoundException;
import com.teamflow.project.Project;
import com.teamflow.project.ProjectService;
import com.teamflow.history.StatusChangeResponse;
import com.teamflow.history.StatusEventType;
import com.teamflow.history.TaskStatusChange;
import com.teamflow.history.TaskStatusChangeRepository;
import com.teamflow.projectmember.ProjectMemberRepository;
import com.teamflow.security.CurrentUser;
import com.teamflow.membership.WorkspaceAccess;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class TaskService {
    private final TaskRepository repository;
    private final ProjectService projects;
    private final ProjectMemberRepository members;
    private final TaskStatusChangeRepository history;
    private final CurrentUser currentUser;
    private final WorkspaceAccess access;
    private final CreationRequests creations;
    private final com.teamflow.job.BackgroundJobs jobs;

    public TaskService(TaskRepository repository, ProjectService projects, ProjectMemberRepository members,
                       TaskStatusChangeRepository history, CurrentUser currentUser, WorkspaceAccess access, CreationRequests creations,
                       com.teamflow.job.BackgroundJobs jobs) {
        this.repository = repository;
        this.projects = projects;
        this.members = members;
        this.history = history;
        this.currentUser = currentUser;
        this.access = access;
        this.creations = creations;
        this.jobs = jobs;
    }

    public PageResponse<TaskResponse> list(UUID workspaceId, UUID projectId,
            TaskStatus status, TaskPriority priority, UUID assigneeId, String q, boolean overdue, int page, int size) {
        projects.requireProject(workspaceId, projectId);
        Specification<Task> filter = (root, query, cb) -> cb.equal(root.get("project").get("id"), projectId);
        if (status != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (priority != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("priority"), priority));
        }
        if (assigneeId != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("assignee").get("id"), assigneeId));
        }
        if (q != null && !q.isBlank()) {
            String pattern = "%" + q.strip().toLowerCase(java.util.Locale.ROOT)
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            filter = filter.and((root, query, cb) -> cb.or(cb.like(cb.lower(root.get("title")), pattern, '\\'),
                    cb.like(cb.lower(root.get("description")), pattern, '\\')));
        }
        if (overdue) {
            var today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
            filter = filter.and((root, query, cb) -> cb.and(cb.lessThan(root.get("dueDate"), today),
                    cb.notEqual(root.get("status"), TaskStatus.DONE)));
        }
        return PageResponse.from(repository.findAll(filter, Pagination.of(page, size)).map(TaskResponse::from));
    }

    public TaskResponse get(UUID workspaceId, UUID projectId, UUID taskId) {
        return TaskResponse.from(requireTask(workspaceId, projectId, taskId));
    }

    @Transactional
    public TaskResponse create(UUID workspaceId, UUID projectId, TaskRequest request, String key) {
        Project project = projects.requireWritableProject(workspaceId, projectId);
        return creations.execute(key, projectId, "tasks:" + projectId, request, TaskResponse.class, () -> {
            Task task = new Task(project, request.title(), request.description(), request.status(), request.priority(), request.dueDate());
            repository.saveAndFlush(task);
            history.save(new TaskStatusChange(task, null, currentUser.get(), StatusEventType.CREATED));
            return TaskResponse.from(task);
        });
    }

    @Transactional
    public TaskResponse update(UUID workspaceId, UUID projectId, UUID taskId, TaskRequest request, String ifMatch) {
        Task task = requireWritableTask(workspaceId, projectId, taskId);
        VersionCondition.check(task, ifMatch);
        TaskStatus previous = task.getStatus();
        task.update(request.title(), request.description(), request.status(), request.priority(), request.dueDate());
        repository.flush();
        if (previous != task.getStatus()) {
            history.save(new TaskStatusChange(task, previous, currentUser.get(), StatusEventType.STATUS_CHANGED));
        }
        return TaskResponse.from(task);
    }

    @Transactional
    public void delete(UUID workspaceId, UUID projectId, UUID taskId, String ifMatch) {
        Task task = requireWritableTask(workspaceId, projectId, taskId);
        VersionCondition.check(task, ifMatch);
        repository.delete(task);
    }

    @Transactional
    public TaskResponse assign(UUID workspaceId, UUID projectId, UUID taskId, AssignmentRequest request, String ifMatch) {
        // Same lock order as membership removal, so an assignment cannot race a member's removal.
        Task task = requireWritableTask(workspaceId, projectId, taskId);
        VersionCondition.check(task, ifMatch);
        var user = request.userId() == null ? null : members.findByProjectIdAndUserId(projectId, request.userId())
                .orElseThrow(() -> new ResourceNotFoundException("Project member not found.")).getUser();
        UUID previous = task.getAssignee() == null ? null : task.getAssignee().getId();
        task.assignTo(user);
        repository.flush();
        if (!java.util.Objects.equals(previous, request.userId())) jobs.assigned(task, currentUser.get());
        return TaskResponse.from(task);
    }

    public PageResponse<StatusChangeResponse> history(UUID workspaceId, UUID projectId, UUID taskId, int page, int size) {
        requireTask(workspaceId, projectId, taskId);
        return PageResponse.from(history.findAllByTaskId(taskId,
                PageRequest.of(page, size, Sort.by("taskVersion"))).map(StatusChangeResponse::from));
    }

    public Task requireWritableTask(UUID workspaceId, UUID projectId, UUID taskId) {
        projects.requireWritableProject(workspaceId, projectId);
        return requireTask(workspaceId, projectId, taskId);
    }

    public Task requireTask(UUID workspaceId, UUID projectId, UUID taskId) {
        projects.requireProject(workspaceId, projectId);
        return repository.findByIdAndProjectId(taskId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found in project: " + taskId));
    }
}
