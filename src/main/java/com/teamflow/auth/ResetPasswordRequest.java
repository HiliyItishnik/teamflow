package com.teamflow.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotBlank @Size(max = 128) @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String token,
        @ValidPassword @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String password) {
    @Override public String toString() { return "ResetPasswordRequest[redacted]"; }
}
