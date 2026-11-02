package com.teamflow.auth;

import com.teamflow.security.SecretTokens;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthRateLimiter {
    private final JdbcTemplate jdbc;
    public AuthRateLimiter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // Separate transaction: failed authentication must not roll back its attempt counter.
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = TooManyAttemptsException.class)
    public void check(String scope, String identity, int limit, int seconds) {
        var result = jdbc.queryForMap("""
                INSERT INTO auth_rate_limits (bucket_hash, attempts, expires_at)
                VALUES (?, 1, clock_timestamp() + (? * interval '1 second'))
                ON CONFLICT (bucket_hash) DO UPDATE SET
                  attempts = CASE WHEN auth_rate_limits.expires_at <= clock_timestamp() THEN 1
                                  ELSE least(auth_rate_limits.attempts + 1, ?) END,
                  expires_at = CASE WHEN auth_rate_limits.expires_at <= clock_timestamp()
                                    THEN clock_timestamp() + (? * interval '1 second') ELSE auth_rate_limits.expires_at END
                RETURNING attempts, greatest(1, ceil(extract(epoch FROM expires_at - clock_timestamp()))) AS wait
                """, SecretTokens.hash(scope + ":" + identity), seconds, limit + 1, seconds);
        if (((Number) result.get("attempts")).intValue() > limit) {
            throw new TooManyAttemptsException(((Number) result.get("wait")).longValue());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = TooManyAttemptsException.class)
    public void checkIp(String address) { check("auth-ip", address, 60, 60); }
}
