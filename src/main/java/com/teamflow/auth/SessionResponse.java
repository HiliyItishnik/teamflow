package com.teamflow.auth;

import java.time.Instant;
import java.util.UUID;

public record SessionResponse(UUID id, Instant createdAt, Instant expiresAt, Instant lastUsedAt, boolean current) {}
