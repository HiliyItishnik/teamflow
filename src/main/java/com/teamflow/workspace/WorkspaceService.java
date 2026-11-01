package com.teamflow.workspace;

import com.teamflow.common.PageResponse;
import com.teamflow.common.Pagination;
import com.teamflow.common.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class WorkspaceService {
    private final WorkspaceRepository repository;

    public WorkspaceService(WorkspaceRepository repository) {
        this.repository = repository;
    }

    public PageResponse<WorkspaceResponse> list(int page, int size) {
        return PageResponse.from(repository.findAll(Pagination.of(page, size)).map(WorkspaceResponse::from));
    }

    public WorkspaceResponse get(UUID id) {
        return WorkspaceResponse.from(requireWorkspace(id));
    }

    @Transactional
    public WorkspaceResponse create(WorkspaceRequest request) {
        return WorkspaceResponse.from(repository.saveAndFlush(new Workspace(request.name(), request.description())));
    }

    @Transactional
    public WorkspaceResponse update(UUID id, WorkspaceRequest request) {
        Workspace workspace = requireWorkspace(id);
        workspace.update(request.name(), request.description());
        repository.flush();
        return WorkspaceResponse.from(workspace);
    }

    @Transactional
    public void delete(UUID id) {
        repository.delete(requireWorkspace(id));
    }

    public Workspace requireWorkspace(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Workspace not found: " + id));
    }
}
