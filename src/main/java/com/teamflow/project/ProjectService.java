package com.teamflow.project;

import com.teamflow.common.PageResponse;
import com.teamflow.common.Pagination;
import com.teamflow.common.ResourceNotFoundException;
import com.teamflow.workspace.WorkspaceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ProjectService {
    private final ProjectRepository repository;
    private final WorkspaceService workspaces;

    public ProjectService(ProjectRepository repository, WorkspaceService workspaces) {
        this.repository = repository;
        this.workspaces = workspaces;
    }

    public PageResponse<ProjectResponse> list(UUID workspaceId, int page, int size) {
        workspaces.requireWorkspace(workspaceId);
        return PageResponse.from(repository.findAllByWorkspaceId(workspaceId, Pagination.of(page, size))
                .map(ProjectResponse::from));
    }

    public ProjectResponse get(UUID workspaceId, UUID projectId) {
        return ProjectResponse.from(requireProject(workspaceId, projectId));
    }

    @Transactional
    public ProjectResponse create(UUID workspaceId, ProjectRequest request) {
        Project project = new Project(workspaces.requireAdminWorkspace(workspaceId), request.name(), request.description());
        return ProjectResponse.from(repository.saveAndFlush(project));
    }

    @Transactional
    public ProjectResponse update(UUID workspaceId, UUID projectId, ProjectRequest request) {
        Project project = requireProject(workspaceId, projectId);
        workspaces.requireAdminWorkspace(workspaceId);
        project.update(request.name(), request.description());
        repository.flush();
        return ProjectResponse.from(project);
    }

    @Transactional
    public void delete(UUID workspaceId, UUID projectId) {
        Project project = requireProject(workspaceId, projectId);
        workspaces.requireAdminWorkspace(workspaceId);
        repository.delete(project);
    }

    public Project requireProject(UUID workspaceId, UUID projectId) {
        workspaces.requireWorkspace(workspaceId);
        return repository.findByIdAndWorkspaceId(projectId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found in workspace: " + projectId));
    }
}
