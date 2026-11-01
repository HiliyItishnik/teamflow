package com.teamflow.comment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface TaskCommentRepository extends JpaRepository<TaskComment, UUID> {
    Page<TaskComment> findAllByTaskId(UUID taskId, Pageable pageable);
    Optional<TaskComment> findByIdAndTaskId(UUID id, UUID taskId);
}
