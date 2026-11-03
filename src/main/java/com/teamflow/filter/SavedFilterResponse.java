package com.teamflow.filter;

import com.teamflow.common.VersionedResponse;
import com.teamflow.task.TaskPriority;
import com.teamflow.task.TaskStatus;
import java.util.UUID;

public record SavedFilterResponse(UUID id, UUID projectId, String name, String q, TaskStatus status,
                                  TaskPriority priority, UUID assigneeId, boolean overdue, long version) implements VersionedResponse {}
