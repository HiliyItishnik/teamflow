package com.teamflow.auth;

import com.teamflow.security.SecretTokens;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
public class PasswordRecoveryService {
    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    private final PasswordResetMail mail;
    private final PasswordEncoder passwords;

    public PasswordRecoveryService(JdbcTemplate jdbc, SessionService sessions, PasswordResetMail mail, PasswordEncoder passwords) {
        this.jdbc = jdbc; this.sessions = sessions; this.mail = mail; this.passwords = passwords;
    }

    @Transactional
    public void deliverQueued(String email, long sequence) {
        var owners = jdbc.queryForList("SELECT id FROM users WHERE email = ? FOR UPDATE", UUID.class, email);
        if (owners.isEmpty()) return;
        UUID owner = owners.getFirst();
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM background_jobs WHERE kind = 'PASSWORD_RECOVERY' AND email = ? AND sequence > ?)",
                Boolean.class, email, sequence))) return;
        String token = SecretTokens.generate();
        jdbc.update("DELETE FROM password_resets WHERE user_id = ?", owner);
        jdbc.update("INSERT INTO password_resets (token_hash, user_id, expires_at) VALUES (?, ?, now() + interval '15 minutes')",
                SecretTokens.hash(token), owner);
        // SMTP has bounded timeouts. Failure rolls back token issuance; no credentials are logged.
        mail.send(email, token);
    }

    @Transactional
    public void reset(ResetPasswordRequest request) {
        String hash = SecretTokens.hash(request.token());
        var owners = jdbc.queryForList("SELECT user_id FROM password_resets WHERE token_hash = ?", UUID.class, hash);
        if (owners.isEmpty()) throw new BadCredentialsException("Invalid reset token.");
        UUID owner = owners.getFirst();
        sessions.lockUser(owner);
        if (jdbc.update("DELETE FROM password_resets WHERE token_hash = ? AND expires_at > now()", hash) != 1) {
            throw new BadCredentialsException("Invalid reset token.");
        }
        jdbc.update("UPDATE users SET password_hash = ?, version = version + 1, updated_at = now() WHERE id = ?",
                passwords.encode(request.password()), owner);
        jdbc.update("DELETE FROM password_resets WHERE user_id = ?", owner);
        jdbc.update("UPDATE auth_sessions SET revoked_at = coalesce(revoked_at, now()) WHERE user_id = ?", owner);
    }
}
