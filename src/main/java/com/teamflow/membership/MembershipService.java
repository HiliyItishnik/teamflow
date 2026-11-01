package com.teamflow.membership;

import com.teamflow.common.ConflictException;
import com.teamflow.common.PageResponse;
import com.teamflow.common.Pagination;
import com.teamflow.common.ResourceNotFoundException;
import com.teamflow.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class MembershipService {
    private final WorkspaceMemberRepository members;
    private final UserRepository users;
    private final WorkspaceAccess access;

    public MembershipService(WorkspaceMemberRepository members, UserRepository users, WorkspaceAccess access) {
        this.members = members;
        this.users = users;
        this.access = access;
    }

    public PageResponse<MemberResponse> list(UUID workspaceId, int page, int size) {
        access.requireMember(workspaceId);
        return PageResponse.from(members.findAllByWorkspaceId(workspaceId, Pagination.of(page, size)).map(MemberResponse::from));
    }

    @Transactional
    public MemberResponse add(UUID workspaceId, AddMemberRequest request) {
        var workspace = access.lockAndRequireAdmin(workspaceId);
        var user = users.findByEmail(request.email())
                .orElseThrow(() -> new ResourceNotFoundException("Registered user not found."));
        if (members.existsByWorkspaceIdAndUserId(workspaceId, user.getId())) {
            throw new ConflictException("User is already a workspace member.");
        }
        return MemberResponse.from(members.saveAndFlush(new WorkspaceMember(workspace, user, request.role())));
    }

    @Transactional
    public MemberResponse changeRole(UUID workspaceId, UUID userId, ChangeRoleRequest request) {
        access.lockAndRequireAdmin(workspaceId);
        WorkspaceMember member = requireMember(workspaceId, userId);
        if (request.role() != WorkspaceRole.ADMIN) preventRemovingLastAdmin(workspaceId, member);
        member.changeRole(request.role());
        members.flush();
        return MemberResponse.from(member);
    }

    @Transactional
    public void remove(UUID workspaceId, UUID userId) {
        access.lockAndRequireAdmin(workspaceId);
        WorkspaceMember member = requireMember(workspaceId, userId);
        preventRemovingLastAdmin(workspaceId, member);
        members.delete(member);
    }

    private WorkspaceMember requireMember(UUID workspaceId, UUID userId) {
        return members.findByWorkspaceIdAndUserId(workspaceId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Workspace member not found."));
    }

    private void preventRemovingLastAdmin(UUID workspaceId, WorkspaceMember member) {
        if (member.getRole() == WorkspaceRole.ADMIN && members.countByWorkspaceIdAndRole(workspaceId, WorkspaceRole.ADMIN) <= 1) {
            throw new ConflictException("A workspace must retain at least one administrator.");
        }
    }
}
