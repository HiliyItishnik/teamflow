package com.teamflow.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SecretRequest(@NotBlank @Size(max = 128)
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String token) {
    @Override public String toString() { return "SecretRequest[redacted]"; }
}
