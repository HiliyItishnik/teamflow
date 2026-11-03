package com.teamflow.job;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
public class JobStore {
    public record Claim(UUID id, UUID claimId) {}
    private final JdbcTemplate jdbc;
    public JobStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public Claim claim() {
        // A crash on the last attempt must eventually become a visible terminal failure.
        jdbc.update("UPDATE background_jobs SET failed_at = now(), last_error = 'LeaseExpired' WHERE completed_at IS NULL AND failed_at IS NULL AND attempts >= 5 AND leased_until < clock_timestamp()");
        UUID token = UUID.randomUUID();
        var rows = jdbc.query("""
                UPDATE background_jobs SET claim_id = ?, leased_until = clock_timestamp() + interval '60 seconds', attempts = attempts + 1
                WHERE id = (SELECT id FROM background_jobs WHERE completed_at IS NULL AND failed_at IS NULL AND attempts < 5
                  AND available_at <= clock_timestamp() AND (leased_until IS NULL OR leased_until < clock_timestamp())
                  ORDER BY available_at, sequence FOR UPDATE SKIP LOCKED LIMIT 1)
                RETURNING id
                """, (rs, n) -> new Claim(rs.getObject("id", UUID.class), token), token);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Transactional
    public void failed(Claim claim, RuntimeException error) {
        // Keep only the error class: exception messages may contain addresses or credentials.
        String category = error.getClass().getSimpleName();
        if (category.length() > 80) category = category.substring(0, 80);
        jdbc.update("""
                UPDATE background_jobs SET last_error = ?, claim_id = NULL, leased_until = NULL,
                  failed_at = CASE WHEN attempts >= 5 THEN now() ELSE NULL END,
                  available_at = clock_timestamp() + (power(2, attempts) * interval '1 second')
                WHERE id = ? AND claim_id = ? AND completed_at IS NULL
                """, category, claim.id(), claim.claimId());
    }
}
