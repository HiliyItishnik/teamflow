package com.teamflow.project;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {
    Page<Project> findAllByWorkspaceId(UUID workspaceId, Pageable pageable);
    Optional<Project> findByIdAndWorkspaceId(UUID id, UUID workspaceId);
}
