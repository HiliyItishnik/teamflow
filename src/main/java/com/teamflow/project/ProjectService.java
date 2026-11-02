package com.teamflow.project;

import com.teamflow.common.PageResponse;
import com.teamflow.common.VersionCondition;
import com.teamflow.common.ConflictException;
import com.teamflow.common.Pagination;
import com.teamflow.common.ResourceNotFoundException;
import com.teamflow.workspace.WorkspaceService;
import com.teamflow.membership.WorkspaceAccess;
import com.teamflow.membership.WorkspaceRole;
import com.teamflow.projectmember.ProjectMember;
import com.teamflow.projectmember.ProjectMemberRepository;
import com.teamflow.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ProjectService {
    private final ProjectRepository repository;
    private final WorkspaceService workspaces;
    private final WorkspaceAccess access;
    private final ProjectMemberRepository members;
    private final CurrentUser currentUser;

    public ProjectService(ProjectRepository repository, WorkspaceService workspaces, WorkspaceAccess access,
                          ProjectMemberRepository members, CurrentUser currentUser) {
        this.repository = repository;
        this.workspaces = workspaces;
        this.access = access;
        this.members = members;
        this.currentUser = currentUser;
    }

    public PageResponse<ProjectResponse> list(UUID workspaceId, boolean archived, int page, int size) {
        var membership = access.requireMember(workspaceId);
        var pageable = Pagination.of(page, size);
        var result = membership.getRole() == WorkspaceRole.ADMIN
                ? repository.findAllByWorkspaceIdAndArchived(workspaceId, archived, pageable)
                : repository.findAllForUser(workspaceId, membership.getUser().getId(), archived, pageable);
        return PageResponse.from(result.map(ProjectResponse::from));
    }

    public ProjectResponse get(UUID workspaceId, UUID projectId) {
        return ProjectResponse.from(requireProject(workspaceId, projectId));
    }

    @Transactional
    public ProjectResponse create(UUID workspaceId, ProjectRequest request) {
        Project project = new Project(access.lockAndRequireAdmin(workspaceId), request.name(), request.description());
        repository.saveAndFlush(project);
        members.save(new ProjectMember(project, currentUser.get()));
        return ProjectResponse.from(project);
    }

    @Transactional
    public ProjectResponse update(UUID workspaceId, UUID projectId, ProjectRequest request, String ifMatch) {
        access.lockAndRequireAdmin(workspaceId);
        Project project = requireWritableProject(workspaceId, projectId);
        VersionCondition.check(project, ifMatch);
        project.update(request.name(), request.description());
        repository.flush();
        return ProjectResponse.from(project);
    }

    @Transactional
    public void delete(UUID workspaceId, UUID projectId, String ifMatch) {
        access.lockAndRequireAdmin(workspaceId);
        Project project = requireProject(workspaceId, projectId);
        VersionCondition.check(project, ifMatch);
        repository.deleteWithDependents(project.getId());
    }

    @Transactional
    public ProjectResponse archive(UUID workspaceId, UUID projectId, ArchiveRequest request, String ifMatch) {
        access.lockAndRequireAdmin(workspaceId);
        Project project = requireProject(workspaceId, projectId);
        VersionCondition.check(project, ifMatch);
        project.setArchived(request.archived());
        repository.flush();
        return ProjectResponse.from(project);
    }

    public Project requireWritableProject(UUID workspaceId, UUID projectId) {
        access.lockAndRequireMember(workspaceId);
        Project project = requireProject(workspaceId, projectId);
        if (project.isArchived()) throw new ConflictException("Project is archived. Restore it before making changes.");
        return project;
    }

    public Project requireProject(UUID workspaceId, UUID projectId) {
        var membership = access.requireMember(workspaceId);
        Project project = repository.findByIdAndWorkspaceId(projectId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found in workspace: " + projectId));
        if (membership.getRole() != WorkspaceRole.ADMIN
                && !members.existsByProjectIdAndUserId(projectId, membership.getUser().getId())) {
            throw new ResourceNotFoundException("Project not found in workspace: " + projectId);
        }
        return project;
    }
}
