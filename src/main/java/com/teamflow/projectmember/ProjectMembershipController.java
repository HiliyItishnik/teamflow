package com.teamflow.projectmember;

import com.teamflow.common.PageResponse;
import com.teamflow.user.UserResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}/members")
@Tag(name = "Project members")
public class ProjectMembershipController {
    private final ProjectMembershipService service;
    public ProjectMembershipController(ProjectMembershipService service) { this.service = service; }

    @GetMapping
    public PageResponse<UserResponse> list(@PathVariable UUID workspaceId, @PathVariable UUID projectId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(workspaceId, projectId, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse add(@PathVariable UUID workspaceId, @PathVariable UUID projectId,
                            @Valid @RequestBody AddProjectMemberRequest request) {
        return service.add(workspaceId, projectId, request);
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID workspaceId, @PathVariable UUID projectId, @PathVariable UUID userId) {
        service.remove(workspaceId, projectId, userId);
    }
}
