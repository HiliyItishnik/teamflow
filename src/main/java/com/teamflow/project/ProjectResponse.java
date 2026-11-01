package com.teamflow.project;

import java.time.Instant;
import java.util.UUID;

public record ProjectResponse(UUID id, UUID workspaceId, String name, String description,
                              Instant createdAt, Instant updatedAt) {
    public static ProjectResponse from(Project project) {
        return new ProjectResponse(project.getId(), project.getWorkspace().getId(), project.getName(),
                project.getDescription(), project.getCreatedAt(), project.getUpdatedAt());
    }
}
