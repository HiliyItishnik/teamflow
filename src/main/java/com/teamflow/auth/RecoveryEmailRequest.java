package com.teamflow.auth;

import com.teamflow.user.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RecoveryEmailRequest(@NotBlank @Email @Size(max = 254) String email) {
    public RecoveryEmailRequest { if (email != null) email = User.normalizeEmail(email); }
    @Override public String toString() { return "RecoveryEmailRequest[redacted]"; }
}
