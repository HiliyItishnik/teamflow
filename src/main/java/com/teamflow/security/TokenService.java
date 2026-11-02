package com.teamflow.security;

import com.teamflow.auth.AuthResponse;
import com.teamflow.user.User;
import com.teamflow.user.UserResponse;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class TokenService {
    public static final String ISSUER = "teamflow";
    public static final String AUDIENCE = "teamflow-api";
    private final JwtEncoder encoder;
    private final JwtProperties properties;

    public TokenService(JwtEncoder encoder, JwtProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    public AuthResponse issue(User user, UUID sessionId, String refreshToken, Instant refreshExpiresAt) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(ISSUER).audience(List.of(AUDIENCE))
                .subject(user.getId().toString()).claim("sid", sessionId.toString()).id(UUID.randomUUID().toString())
                .issuedAt(now).expiresAt(now.plus(properties.timeToLive())).build();
        var token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims));
        return new AuthResponse(token.getTokenValue(), "Bearer", properties.timeToLive().toSeconds(), UserResponse.from(user),
                refreshToken, sessionId, refreshExpiresAt);
    }
}
