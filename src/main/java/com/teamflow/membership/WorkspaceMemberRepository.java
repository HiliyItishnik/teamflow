package com.teamflow.membership;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, UUID> {
    Optional<WorkspaceMember> findByWorkspaceIdAndUserId(UUID workspaceId, UUID userId);
    boolean existsByWorkspaceIdAndUserId(UUID workspaceId, UUID userId);
    long countByWorkspaceIdAndRole(UUID workspaceId, WorkspaceRole role);

    @EntityGraph(attributePaths = "user")
    Page<WorkspaceMember> findAllByWorkspaceId(UUID workspaceId, Pageable pageable);
}
