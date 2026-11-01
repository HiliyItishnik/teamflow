package com.teamflow.history;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface TaskStatusChangeRepository extends JpaRepository<TaskStatusChange, UUID> {
    Page<TaskStatusChange> findAllByTaskId(UUID taskId, Pageable pageable);
}
