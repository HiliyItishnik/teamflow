package com.teamflow.membership;

import com.teamflow.common.ResourceNotFoundException;
import com.teamflow.security.CurrentUser;
import com.teamflow.workspace.Workspace;
import com.teamflow.workspace.WorkspaceRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class WorkspaceAccess {
    private final WorkspaceMemberRepository members;
    private final WorkspaceRepository workspaces;
    private final CurrentUser currentUser;

    public WorkspaceAccess(WorkspaceMemberRepository members, WorkspaceRepository workspaces, CurrentUser currentUser) {
        this.members = members;
        this.workspaces = workspaces;
        this.currentUser = currentUser;
    }

    public WorkspaceMember requireMember(UUID workspaceId) {
        return members.findByWorkspaceIdAndUserId(workspaceId, currentUser.id())
                .orElseThrow(() -> new ResourceNotFoundException("Workspace not found: " + workspaceId));
    }

    public Workspace requireAdmin(UUID workspaceId) {
        WorkspaceMember member = requireMember(workspaceId);
        if (member.getRole() != WorkspaceRole.ADMIN) throw new AccessDeniedException("Administrator role required.");
        return member.getWorkspace();
    }

    // Called inside a write transaction. All membership mutations take the same lock
    // before checking the caller's role, preserving the last-admin invariant under concurrency.
    public Workspace lockAndRequireAdmin(UUID workspaceId) {
        Workspace workspace = workspaces.findByIdForUpdate(workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Workspace not found: " + workspaceId));
        requireAdmin(workspaceId);
        return workspace;
    }
}
