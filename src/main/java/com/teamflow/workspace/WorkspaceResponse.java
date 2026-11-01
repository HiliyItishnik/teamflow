package com.teamflow.workspace;

import java.time.Instant;
import java.util.UUID;

public record WorkspaceResponse(UUID id, String name, String description, Instant createdAt, Instant updatedAt) {
    public static WorkspaceResponse from(Workspace workspace) {
        return new WorkspaceResponse(workspace.getId(), workspace.getName(), workspace.getDescription(),
                workspace.getCreatedAt(), workspace.getUpdatedAt());
    }
}
