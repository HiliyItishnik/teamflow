package com.teamflow.filter;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}/filters")
public class SavedFilterController {
    private final SavedFilterService service;
    public SavedFilterController(SavedFilterService service) { this.service = service; }
    @GetMapping
    public List<SavedFilterResponse> list(@PathVariable UUID workspaceId, @PathVariable UUID projectId) { return service.list(workspaceId, projectId); }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SavedFilterResponse create(@PathVariable UUID workspaceId, @PathVariable UUID projectId, @Valid @RequestBody SavedFilterRequest request) {
        return service.create(workspaceId, projectId, request);
    }
    @PutMapping("/{id}")
    public SavedFilterResponse update(@PathVariable UUID workspaceId, @PathVariable UUID projectId, @PathVariable UUID id,
            @Valid @RequestBody SavedFilterRequest request, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.update(workspaceId, projectId, id, request, ifMatch);
    }
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID workspaceId, @PathVariable UUID projectId, @PathVariable UUID id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) { service.delete(workspaceId, projectId, id, ifMatch); }
}
