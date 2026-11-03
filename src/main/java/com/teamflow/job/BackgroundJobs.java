package com.teamflow.job;

import com.teamflow.task.Task;
import com.teamflow.user.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
public class BackgroundJobs {
    private final JdbcTemplate jdbc;
    public BackgroundJobs(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void assigned(Task task, User actor) {
        if (task.getAssignee() != null && !task.getAssignee().getId().equals(actor.getId())) {
            enqueue("ASSIGNED", task.getId(), task.getAssignee().getId(), actor.getDisplayName());
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void commented(Task task, User actor) {
        for (UUID recipient : jdbc.queryForList("SELECT user_id FROM project_members WHERE project_id = ? AND user_id <> ?",
                UUID.class, task.getProject().getId(), actor.getId())) {
            enqueue("COMMENTED", task.getId(), recipient, actor.getDisplayName());
        }
    }

    private void enqueue(String kind, UUID task, UUID recipient, String actor) {
        jdbc.update("INSERT INTO background_jobs (id, kind, task_id, recipient_id, actor_name) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), kind, task, recipient, actor);
    }

    @Transactional
    public void recoverPassword(String email) {
        // Identical durable enqueue for known and unknown accounts; no token is stored in the queue.
        jdbc.update("INSERT INTO background_jobs (id, kind, email) VALUES (?, 'PASSWORD_RECOVERY', ?)", UUID.randomUUID(), email);
    }
}
