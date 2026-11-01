package com.teamflow.security;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Base64;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityConfigTest {
    private final SecurityConfig config = new SecurityConfig();

    @Test
    void refusesWeakOrMalformedSigningKeys() {
        assertThatThrownBy(() -> config.jwtKey(new JwtProperties("not-base64!", Duration.ofMinutes(30))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> config.jwtKey(new JwtProperties(Base64.getEncoder().encodeToString(new byte[16]), Duration.ofMinutes(30))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesInvalidTokenLifetimes() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        for (Duration ttl : new Duration[]{Duration.ZERO, Duration.ofSeconds(-1), Duration.ofDays(2)}) {
            assertThatThrownBy(() -> config.jwtKey(new JwtProperties(key, ttl))).isInstanceOf(IllegalStateException.class);
        }
    }
}
