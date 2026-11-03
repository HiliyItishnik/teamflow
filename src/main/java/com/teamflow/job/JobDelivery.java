package com.teamflow.job;

import com.teamflow.auth.PasswordRecoveryService;
import com.teamflow.notification.NotificationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
public class JobDelivery {
    private final JdbcTemplate jdbc;
    private final PasswordRecoveryService recovery;
    public JobDelivery(JdbcTemplate jdbc, PasswordRecoveryService recovery) { this.jdbc = jdbc; this.recovery = recovery; }

    @Transactional
    public void deliver(JobStore.Claim claim) {
        var rows = jdbc.queryForList("SELECT kind, email, sequence FROM background_jobs WHERE id = ? AND claim_id = ? AND completed_at IS NULL AND failed_at IS NULL FOR UPDATE",
                claim.id(), claim.claimId());
        if (rows.isEmpty()) return;
        var job = rows.getFirst();
        if (job.get("kind").equals("PASSWORD_RECOVERY")) {
            recovery.deliverQueued((String) job.get("email"), ((Number) job.get("sequence")).longValue());
        } else {
            jdbc.update("INSERT INTO notifications (id, event_id) SELECT ?, j.id FROM background_jobs j "
                    + "JOIN tasks t ON t.id = j.task_id JOIN projects p ON p.id = t.project_id WHERE j.id = ? AND "
                    + NotificationService.VISIBLE + " ON CONFLICT (event_id) DO NOTHING", UUID.randomUUID(), claim.id());
        }
        jdbc.update("UPDATE background_jobs SET completed_at = now(), claim_id = NULL, leased_until = NULL, last_error = NULL WHERE id = ?", claim.id());
    }
}
