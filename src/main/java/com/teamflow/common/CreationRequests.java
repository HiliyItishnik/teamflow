package com.teamflow.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teamflow.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class CreationRequests {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CurrentUser user;
    public CreationRequests(JdbcTemplate jdbc, ObjectMapper json, CurrentUser user) {
        this.jdbc = jdbc; this.json = json; this.user = user;
    }

    // Caller must authorize, check archive state and lock the workspace BEFORE invoking this method.
    @Transactional(propagation = Propagation.MANDATORY)
    public <T> T execute(String key, UUID projectId, String scope, Object payload, Class<T> type, Supplier<T> create) {
        if (key == null) return create.get();
        UUID requestKey;
        try {
            requestKey = UUID.fromString(key);
            if (!requestKey.toString().equalsIgnoreCase(key)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            throw new RequestConditionException(HttpStatus.BAD_REQUEST, "Idempotency-Key must be a UUID.");
        }
        UUID actor = user.id();
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8)));
            var saved = jdbc.queryForList("SELECT request_hash, response_json FROM creation_requests "
                    + "WHERE actor_id = ? AND scope = ? AND request_key = ?", actor, scope, requestKey);
            if (!saved.isEmpty()) {
                if (!hash.equals(saved.getFirst().get("request_hash"))) {
                    throw new ConflictException("Idempotency-Key was already used with a different request.");
                }
                return json.readValue((String) saved.getFirst().get("response_json"), type);
            }
            T response = create.get();
            jdbc.update("INSERT INTO creation_requests(actor_id, project_id, scope, request_key, request_hash, response_json) "
                    + "VALUES (?, ?, ?, ?, ?, ?)", actor, projectId, scope, requestKey, hash, json.writeValueAsString(response));
            return response;
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Cannot serialize an idempotent response.", exception);
        }
    }
}
