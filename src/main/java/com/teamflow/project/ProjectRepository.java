package com.teamflow.project;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {
    Page<Project> findAllByWorkspaceIdAndArchived(UUID workspaceId, boolean archived, Pageable pageable);
    Optional<Project> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    @Query("select p from Project p where p.workspace.id = :workspaceId and p.archived = :archived and exists "
            + "(select m.id from ProjectMember m where m.project = p and m.user.id = :userId)")
    Page<Project> findAllForUser(UUID workspaceId, UUID userId, boolean archived, Pageable pageable);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Project p where p.id = :id")
    void deleteWithDependents(UUID id);
}
