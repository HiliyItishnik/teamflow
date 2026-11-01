package com.teamflow.workspace;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {
    @Query("select w from Workspace w where exists (select m.id from WorkspaceMember m where m.workspace = w and m.user.id = :userId)")
    Page<Workspace> findAllForUser(UUID userId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Workspace w where w.id = :id")
    Optional<Workspace> findByIdForUpdate(UUID id);

    // PostgreSQL cascades to members, projects and tasks. Clear the persistence context
    // because authorization may have loaded members that reference this workspace.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Workspace w where w.id = :id")
    void deleteWithDependents(UUID id);
}
