package com.teamflow.membership;

import jakarta.validation.constraints.NotNull;

public record ChangeRoleRequest(@NotNull WorkspaceRole role) {}
