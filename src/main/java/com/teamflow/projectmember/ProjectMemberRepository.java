package com.teamflow.projectmember;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, UUID> {
    boolean existsByProjectIdAndUserId(UUID projectId, UUID userId);
    @EntityGraph(attributePaths = "user")
    Optional<ProjectMember> findByProjectIdAndUserId(UUID projectId, UUID userId);
    @EntityGraph(attributePaths = "user")
    Page<ProjectMember> findAllByProjectId(UUID projectId, Pageable pageable);
}
