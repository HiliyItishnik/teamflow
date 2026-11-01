package com.teamflow.membership;

import java.util.UUID;

public record MemberResponse(UUID userId, String email, String displayName, WorkspaceRole role) {
    public static MemberResponse from(WorkspaceMember member) {
        var user = member.getUser();
        return new MemberResponse(user.getId(), user.getEmail(), user.getDisplayName(), member.getRole());
    }
}
