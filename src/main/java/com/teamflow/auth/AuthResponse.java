package com.teamflow.auth;

import com.teamflow.user.UserResponse;

public record AuthResponse(String accessToken, String tokenType, long expiresIn, UserResponse user,
                           String refreshToken, java.util.UUID sessionId, java.time.Instant refreshExpiresAt) {
    @Override public String toString() { return "AuthResponse[token redacted]"; }
}
