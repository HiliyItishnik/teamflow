package com.teamflow.common;

import org.springframework.http.HttpStatus;
import java.util.UUID;

public final class VersionCondition {
    private VersionCondition() {}

    public static String etag(UUID id, long version) { return "\"" + id + ":" + version + "\""; }

    public static void check(BaseEntity entity, String expected) {
        if (expected == null || expected.isBlank()) {
            throw new RequestConditionException(HttpStatus.PRECONDITION_REQUIRED, "Read the resource and send its ETag in If-Match.");
        }
        if (!expected.matches("\"[0-9a-fA-F-]{36}:[0-9]+\"")) {
            throw new RequestConditionException(HttpStatus.BAD_REQUEST, "If-Match must contain one exact resource ETag; wildcards are not supported.");
        }
        if (!etag(entity.getId(), entity.getVersion()).equals(expected)) {
            throw new RequestConditionException(HttpStatus.PRECONDITION_FAILED, "The resource changed. Read it again before editing.");
        }
    }
}
