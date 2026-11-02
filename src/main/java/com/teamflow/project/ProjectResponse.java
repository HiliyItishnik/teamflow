package com.teamflow.project;

import com.teamflow.common.VersionedResponse;
import java.time.Instant;
import java.util.UUID;

public record ProjectResponse(UUID id, UUID workspaceId, String name, String description,
                              Instant createdAt, Instant updatedAt, long version, boolean archived) implements VersionedResponse {
    public static ProjectResponse from(Project project) {
        return new ProjectResponse(project.getId(), project.getWorkspace().getId(), project.getName(),
                project.getDescription(), project.getCreatedAt(), project.getUpdatedAt(), project.getVersion(), project.isArchived());
    }
}
