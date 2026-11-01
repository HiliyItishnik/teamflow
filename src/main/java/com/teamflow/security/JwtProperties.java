package com.teamflow.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "teamflow.jwt")
public record JwtProperties(@NotBlank String secret, @NotNull Duration timeToLive) {
    @Override public String toString() { return "JwtProperties[secret redacted]"; }
}
