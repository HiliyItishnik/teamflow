package com.teamflow.workspace;

import com.teamflow.common.PageResponse;
import com.teamflow.common.VersionCondition;
import com.teamflow.common.Pagination;
import com.teamflow.membership.WorkspaceAccess;
import com.teamflow.membership.WorkspaceMember;
import com.teamflow.membership.WorkspaceMemberRepository;
import com.teamflow.membership.WorkspaceRole;
import com.teamflow.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class WorkspaceService {
    private final WorkspaceRepository repository;
    private final WorkspaceMemberRepository members;
    private final WorkspaceAccess access;
    private final CurrentUser currentUser;

    public WorkspaceService(WorkspaceRepository repository, WorkspaceMemberRepository members,
                            WorkspaceAccess access, CurrentUser currentUser) {
        this.repository = repository;
        this.members = members;
        this.access = access;
        this.currentUser = currentUser;
    }

    public PageResponse<WorkspaceResponse> list(int page, int size) {
        return PageResponse.from(repository.findAllForUser(currentUser.id(), Pagination.of(page, size)).map(WorkspaceResponse::from));
    }

    public WorkspaceResponse get(UUID id) {
        return WorkspaceResponse.from(requireWorkspace(id));
    }

    @Transactional
    public WorkspaceResponse create(WorkspaceRequest request) {
        var user = currentUser.get();
        Workspace workspace = repository.saveAndFlush(new Workspace(request.name(), request.description()));
        members.save(new WorkspaceMember(workspace, user, WorkspaceRole.ADMIN));
        return WorkspaceResponse.from(workspace);
    }

    @Transactional
    public WorkspaceResponse update(UUID id, WorkspaceRequest request, String ifMatch) {
        Workspace workspace = access.lockAndRequireAdmin(id);
        VersionCondition.check(workspace, ifMatch);
        workspace.update(request.name(), request.description());
        repository.flush();
        return WorkspaceResponse.from(workspace);
    }

    @Transactional
    public void delete(UUID id, String ifMatch) {
        Workspace workspace = access.lockAndRequireAdmin(id);
        VersionCondition.check(workspace, ifMatch);
        repository.deleteWithDependents(workspace.getId());
    }

    public Workspace requireWorkspace(UUID id) {
        return access.requireMember(id).getWorkspace();
    }

    public Workspace requireAdminWorkspace(UUID id) { return access.requireAdmin(id); }
}
