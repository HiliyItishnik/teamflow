package com.teamflow.projectmember;

import com.teamflow.common.ConflictException;
import com.teamflow.common.PageResponse;
import com.teamflow.common.Pagination;
import com.teamflow.common.ResourceNotFoundException;
import com.teamflow.membership.WorkspaceAccess;
import com.teamflow.membership.WorkspaceMemberRepository;
import com.teamflow.project.ProjectService;
import com.teamflow.user.UserResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ProjectMembershipService {
    private final ProjectMemberRepository members;
    private final WorkspaceMemberRepository workspaceMembers;
    private final ProjectService projects;
    private final WorkspaceAccess access;

    public ProjectMembershipService(ProjectMemberRepository members, WorkspaceMemberRepository workspaceMembers,
                                    ProjectService projects, WorkspaceAccess access) {
        this.members = members;
        this.workspaceMembers = workspaceMembers;
        this.projects = projects;
        this.access = access;
    }

    public PageResponse<UserResponse> list(UUID workspaceId, UUID projectId, int page, int size) {
        projects.requireProject(workspaceId, projectId);
        return PageResponse.from(members.findAllByProjectId(projectId, Pagination.of(page, size))
                .map(member -> UserResponse.from(member.getUser())));
    }

    @Transactional
    public UserResponse add(UUID workspaceId, UUID projectId, AddProjectMemberRequest request) {
        access.lockAndRequireAdmin(workspaceId);
        var project = projects.requireProject(workspaceId, projectId);
        var workspaceMember = workspaceMembers.findByWorkspaceIdAndUserId(workspaceId, request.userId())
                .orElseThrow(() -> new ResourceNotFoundException("Workspace member not found."));
        if (members.existsByProjectIdAndUserId(projectId, request.userId())) {
            throw new ConflictException("User is already a project member.");
        }
        var member = members.saveAndFlush(new ProjectMember(project, workspaceMember.getUser()));
        return UserResponse.from(member.getUser());
    }

    @Transactional
    public void remove(UUID workspaceId, UUID projectId, UUID userId) {
        access.lockAndRequireAdmin(workspaceId);
        projects.requireProject(workspaceId, projectId);
        var member = members.findByProjectIdAndUserId(projectId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Project member not found."));
        members.delete(member);
    }
}
