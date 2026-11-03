package com.teamflow.task;

import com.teamflow.common.PageResponse;
import com.teamflow.history.StatusChangeResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}/tasks")
@Tag(name = "Tasks")
public class TaskController {
    private final TaskService service;

    public TaskController(TaskService service) { this.service = service; }

    @GetMapping
    public PageResponse<TaskResponse> list(@PathVariable UUID workspaceId, @PathVariable UUID projectId,
            @RequestParam(required = false) TaskStatus status,
            @RequestParam(required = false) TaskPriority priority,
            @RequestParam(required = false) UUID assigneeId,
            @RequestParam(required = false) @Size(max = 200) String q,
            @RequestParam(defaultValue = "false") boolean overdue,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(workspaceId, projectId, status, priority, assigneeId, q, overdue, page, size);
    }

    @GetMapping("/{taskId}")
    public TaskResponse get(@PathVariable UUID workspaceId, @PathVariable UUID projectId, @PathVariable UUID taskId) {
        return service.get(workspaceId, projectId, taskId);
    }

    @PostMapping
    public ResponseEntity<TaskResponse> create(@PathVariable UUID workspaceId, @PathVariable UUID projectId,
            @Valid @RequestBody TaskRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        TaskResponse response = service.create(workspaceId, projectId, request, key);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(response.id()).toUri();
        return ResponseEntity.created(location).body(response);
    }

    @PutMapping("/{taskId}")
    public TaskResponse update(@PathVariable UUID workspaceId, @PathVariable UUID projectId,
            @PathVariable UUID taskId, @Valid @RequestBody TaskRequest request, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.update(workspaceId, projectId, taskId, request, ifMatch);
    }

    @DeleteMapping("/{taskId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID workspaceId, @PathVariable UUID projectId, @PathVariable UUID taskId, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        service.delete(workspaceId, projectId, taskId, ifMatch);
    }

    @PutMapping("/{taskId}/assignee")
    public TaskResponse assign(@PathVariable UUID workspaceId, @PathVariable UUID projectId,
                               @PathVariable UUID taskId, @Valid @RequestBody AssignmentRequest request, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.assign(workspaceId, projectId, taskId, request, ifMatch);
    }

    @GetMapping("/{taskId}/history")
    public PageResponse<StatusChangeResponse> history(@PathVariable UUID workspaceId, @PathVariable UUID projectId,
            @PathVariable UUID taskId, @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.history(workspaceId, projectId, taskId, page, size);
    }
}
