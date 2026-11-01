package com.teamflow.history;

import com.teamflow.task.TaskStatus;
import java.time.Instant;
import java.util.UUID;

public record StatusChangeResponse(UUID id, long taskVersion, StatusEventType eventType, TaskStatus fromStatus,
                                   TaskStatus toStatus, UUID actorId, String actorName, Instant changedAt) {
    public static StatusChangeResponse from(TaskStatusChange change) {
        return new StatusChangeResponse(change.getId(), change.getTaskVersion(), change.getEventType(),
                change.getFromStatus(), change.getToStatus(), change.getActor() == null ? null : change.getActor().getId(),
                change.getActorName(), change.getCreatedAt());
    }
}
