package com.teamflow.notification;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(UUID id, String kind, String actorName, UUID workspaceId, UUID projectId,
                                   UUID taskId, String taskTitle, Instant createdAt, Instant readAt) {}
