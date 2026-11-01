package com.teamflow.comment;

import com.teamflow.common.PageResponse;
import com.teamflow.common.ResourceNotFoundException;
import com.teamflow.membership.WorkspaceAccess;
import com.teamflow.security.CurrentUser;
import com.teamflow.task.TaskService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class CommentService {
    private final TaskCommentRepository comments;
    private final TaskService tasks;
    private final CurrentUser currentUser;
    private final WorkspaceAccess access;

    public CommentService(TaskCommentRepository comments, TaskService tasks, CurrentUser currentUser, WorkspaceAccess access) {
        this.comments = comments;
        this.tasks = tasks;
        this.currentUser = currentUser;
        this.access = access;
    }

    public PageResponse<CommentResponse> list(UUID workspaceId, UUID projectId, UUID taskId, int page, int size) {
        tasks.requireTask(workspaceId, projectId, taskId);
        return PageResponse.from(comments.findAllByTaskId(taskId,
                PageRequest.of(page, size, Sort.by("createdAt", "id"))).map(CommentResponse::from));
    }

    @Transactional
    public CommentResponse add(UUID workspaceId, UUID projectId, UUID taskId, CommentRequest request) {
        var task = tasks.requireTask(workspaceId, projectId, taskId);
        return CommentResponse.from(comments.saveAndFlush(new TaskComment(task, currentUser.get(), request.body())));
    }

    @Transactional
    public CommentResponse edit(UUID workspaceId, UUID projectId, UUID taskId, UUID commentId, CommentRequest request) {
        tasks.requireTask(workspaceId, projectId, taskId);
        var comment = requireComment(taskId, commentId);
        if (!isAuthor(comment)) throw new AccessDeniedException("Only the author can edit a comment.");
        comment.edit(request.body());
        comments.flush();
        return CommentResponse.from(comment);
    }

    @Transactional
    public void delete(UUID workspaceId, UUID projectId, UUID taskId, UUID commentId) {
        tasks.requireTask(workspaceId, projectId, taskId);
        var comment = requireComment(taskId, commentId);
        if (!isAuthor(comment)) access.requireAdmin(workspaceId);
        comments.delete(comment);
    }

    private boolean isAuthor(TaskComment comment) {
        return comment.getAuthor() != null && comment.getAuthor().getId().equals(currentUser.id());
    }

    private TaskComment requireComment(UUID taskId, UUID commentId) {
        return comments.findByIdAndTaskId(commentId, taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Comment not found in task."));
    }
}
