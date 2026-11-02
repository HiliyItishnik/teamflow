package com.teamflow.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
public class AuthRetention {
    private final JdbcTemplate jdbc;
    public AuthRetention(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // Expired sessions can no longer authenticate; keep their token history one extra day.
    @Scheduled(initialDelay = 3600000, fixedDelay = 3600000)
    public void cleanExpired() {
        jdbc.update("DELETE FROM auth_sessions WHERE expires_at < now() - interval '1 day'");
        jdbc.update("DELETE FROM password_resets WHERE expires_at < now()");
        jdbc.update("DELETE FROM auth_rate_limits WHERE expires_at < now() - interval '1 day'");
        jdbc.update("DELETE FROM workspace_invitations WHERE expires_at < now() - interval '30 days'");
    }
}
