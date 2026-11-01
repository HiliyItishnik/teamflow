package com.teamflow.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.teamflow.user.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @ValidPassword @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String password
) {
    public LoginRequest { if (email != null) email = User.normalizeEmail(email); }
    @Override public String toString() { return "LoginRequest[credentials redacted]"; }
}
