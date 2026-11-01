package com.teamflow.comment;

import java.time.Instant;
import java.util.UUID;

public record CommentResponse(UUID id, UUID authorId, String authorName, String body,
                               Instant createdAt, Instant updatedAt) {
    public static CommentResponse from(TaskComment comment) {
        return new CommentResponse(comment.getId(), comment.getAuthor() == null ? null : comment.getAuthor().getId(),
                comment.getAuthorName(), comment.getBody(), comment.getCreatedAt(), comment.getUpdatedAt());
    }
}
