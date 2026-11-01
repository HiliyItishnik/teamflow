package com.teamflow.comment;

import com.teamflow.common.PageResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}/tasks/{taskId}/comments")
@Tag(name = "Task comments")
public class CommentController {
    private final CommentService service;
    public CommentController(CommentService service) { this.service = service; }

    @GetMapping
    public PageResponse<CommentResponse> list(@PathVariable UUID workspaceId, @PathVariable UUID projectId,
            @PathVariable UUID taskId, @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(workspaceId, projectId, taskId, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CommentResponse add(@PathVariable UUID workspaceId, @PathVariable UUID projectId,
                               @PathVariable UUID taskId, @Valid @RequestBody CommentRequest request) {
        return service.add(workspaceId, projectId, taskId, request);
    }

    @PutMapping("/{commentId}")
    public CommentResponse edit(@PathVariable UUID workspaceId, @PathVariable UUID projectId,
            @PathVariable UUID taskId, @PathVariable UUID commentId, @Valid @RequestBody CommentRequest request) {
        return service.edit(workspaceId, projectId, taskId, commentId, request);
    }

    @DeleteMapping("/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID workspaceId, @PathVariable UUID projectId,
                       @PathVariable UUID taskId, @PathVariable UUID commentId) {
        service.delete(workspaceId, projectId, taskId, commentId);
    }
}
