package com.teamflow.membership;

import com.teamflow.user.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AddMemberRequest(@NotBlank @Email @Size(max = 254) String email, @NotNull WorkspaceRole role) {
    public AddMemberRequest { if (email != null) email = User.normalizeEmail(email); }
}
