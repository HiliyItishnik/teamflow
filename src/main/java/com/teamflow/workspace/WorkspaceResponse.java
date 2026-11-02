package com.teamflow.workspace;

import com.teamflow.common.VersionedResponse;
import java.time.Instant;
import java.util.UUID;

public record WorkspaceResponse(UUID id, String name, String description, Instant createdAt, Instant updatedAt, long version) implements VersionedResponse {
    public static WorkspaceResponse from(Workspace workspace) {
        return new WorkspaceResponse(workspace.getId(), workspace.getName(), workspace.getDescription(),
                workspace.getCreatedAt(), workspace.getUpdatedAt(), workspace.getVersion());
    }
}
