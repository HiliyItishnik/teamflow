package com.teamflow.invitation;

import com.teamflow.membership.WorkspaceRole;
import java.time.Instant;
import java.util.UUID;

public record InvitationResponse(UUID id, UUID workspaceId, String email, WorkspaceRole role,
                                 Instant createdAt, Instant expiresAt, Instant acceptedAt, Instant revokedAt) {}
