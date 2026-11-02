package com.teamflow.project;

import com.teamflow.common.PageResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
@RequestMapping("/api/v1/workspaces/{workspaceId}/projects")
@Tag(name = "Projects")
public class ProjectController {
    private final ProjectService service;

    public ProjectController(ProjectService service) { this.service = service; }

    @GetMapping
    public PageResponse<ProjectResponse> list(@PathVariable UUID workspaceId,
            @RequestParam(defaultValue = "false") boolean archived,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(workspaceId, archived, page, size);
    }

    @GetMapping("/{projectId}")
    public ProjectResponse get(@PathVariable UUID workspaceId, @PathVariable UUID projectId) {
        return service.get(workspaceId, projectId);
    }

    @PostMapping
    public ResponseEntity<ProjectResponse> create(@PathVariable UUID workspaceId,
            @Valid @RequestBody ProjectRequest request) {
        ProjectResponse response = service.create(workspaceId, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(response.id()).toUri();
        return ResponseEntity.created(location).body(response);
    }

    @PutMapping("/{projectId}")
    public ProjectResponse update(@PathVariable UUID workspaceId, @PathVariable UUID projectId,
            @Valid @RequestBody ProjectRequest request, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.update(workspaceId, projectId, request, ifMatch);
    }

    @PutMapping("/{projectId}/archive")
    public ProjectResponse archive(@PathVariable UUID workspaceId, @PathVariable UUID projectId,
            @Valid @RequestBody ArchiveRequest request,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.archive(workspaceId, projectId, request, ifMatch);
    }

    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID workspaceId, @PathVariable UUID projectId, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        service.delete(workspaceId, projectId, ifMatch);
    }
}
