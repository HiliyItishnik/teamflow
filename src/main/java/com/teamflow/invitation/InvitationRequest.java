package com.teamflow.invitation;

import com.teamflow.membership.WorkspaceRole;
import com.teamflow.user.User;
import jakarta.validation.constraints.*;

public record InvitationRequest(@NotBlank @Email @Size(max = 254) String email, @NotNull WorkspaceRole role) {
    public InvitationRequest { if (email != null) email = User.normalizeEmail(email); }
}
