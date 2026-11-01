package com.teamflow.task;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record TaskResponse(UUID id, UUID projectId, String title, String description,
                           TaskStatus status, TaskPriority priority, LocalDate dueDate, UUID assigneeId,
                           Instant createdAt, Instant updatedAt) {
    public static TaskResponse from(Task task) {
        return new TaskResponse(task.getId(), task.getProject().getId(), task.getTitle(), task.getDescription(),
                task.getStatus(), task.getPriority(), task.getDueDate(),
                task.getAssignee() == null ? null : task.getAssignee().getId(), task.getCreatedAt(), task.getUpdatedAt());
    }
}
