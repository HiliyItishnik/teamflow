package com.teamflow.filter;

import com.teamflow.task.TaskPriority;
import com.teamflow.task.TaskStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record SavedFilterRequest(@NotBlank @Size(max = 80) String name, @Size(max = 200) String q,
                                 TaskStatus status, TaskPriority priority, UUID assigneeId, boolean overdue) {}
