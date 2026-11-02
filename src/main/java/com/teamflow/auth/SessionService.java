package com.teamflow.auth;

import com.teamflow.common.ResourceNotFoundException;
import com.teamflow.security.CurrentUser;
import com.teamflow.security.SecretTokens;
import com.teamflow.security.TokenService;
import com.teamflow.user.User;
import com.teamflow.user.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
public class SessionService {
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final TokenService tokens;
    private final CurrentUser current;

    public SessionService(JdbcTemplate jdbc, UserRepository users, TokenService tokens, CurrentUser current) {
        this.jdbc = jdbc;
        this.users = users;
        this.tokens = tokens;
        this.current = current;
    }

    @Transactional
    public AuthResponse create(User user) {
        lockUser(user.getId());
        UUID id = UUID.randomUUID();
        Instant expires = Instant.now().truncatedTo(ChronoUnit.MICROS).plus(30, ChronoUnit.DAYS);
        jdbc.update("INSERT INTO auth_sessions (id, user_id, expires_at, created_at, last_used_at) VALUES (?, ?, ?, clock_timestamp(), clock_timestamp())",
                id, user.getId(), Timestamp.from(expires));
        // Keep at most 20 active sessions per account; oldest sessions are revoked first.
        jdbc.update("""
                UPDATE auth_sessions SET revoked_at = now() WHERE id IN (
                  SELECT id FROM auth_sessions WHERE user_id = ? AND revoked_at IS NULL AND expires_at > now()
                  ORDER BY created_at DESC, id DESC OFFSET 20)
                """, user.getId());
        return issue(user, id, expires);
    }

    // Reuse must commit revocation even though the response is 401.
    @Transactional(noRollbackFor = BadCredentialsException.class)
    public AuthResponse refresh(String token) {
        String hash = SecretTokens.hash(token);
        var owners = jdbc.queryForList("""
                SELECT s.user_id FROM refresh_tokens t JOIN auth_sessions s ON s.id = t.session_id
                WHERE t.token_hash = ?
                """, UUID.class, hash);
        if (owners.isEmpty()) throw invalid();
        UUID owner = owners.getFirst();
        lockUser(owner);
        var rows = jdbc.queryForList("""
                SELECT s.id, s.expires_at, s.revoked_at, t.used_at FROM refresh_tokens t
                JOIN auth_sessions s ON s.id = t.session_id WHERE t.token_hash = ? FOR UPDATE OF s, t
                """, hash);
        if (rows.isEmpty()) throw invalid();
        var row = rows.getFirst();
        UUID id = (UUID) row.get("id");
        Instant expires = ((Timestamp) row.get("expires_at")).toInstant();
        if (row.get("used_at") != null) {
            jdbc.update("UPDATE auth_sessions SET revoked_at = coalesce(revoked_at, now()) WHERE id = ?", id);
            throw invalid();
        }
        if (row.get("revoked_at") != null || !expires.isAfter(Instant.now())) throw invalid();
        jdbc.update("UPDATE refresh_tokens SET used_at = now() WHERE token_hash = ?", hash);
        jdbc.update("UPDATE auth_sessions SET last_used_at = now() WHERE id = ?", id);
        return issue(users.findById(owner).orElseThrow(SessionService::invalid), id, expires);
    }

    private AuthResponse issue(User user, UUID id, Instant expires) {
        String refresh = SecretTokens.generate();
        jdbc.update("INSERT INTO refresh_tokens (token_hash, session_id) VALUES (?, ?)", SecretTokens.hash(refresh), id);
        return tokens.issue(user, id, refresh, expires);
    }

    @Transactional(readOnly = true)
    public List<SessionResponse> list() {
        UUID owner = current.id();
        UUID currentId = current.sessionId();
        return jdbc.query("""
                SELECT id, created_at, expires_at, last_used_at FROM auth_sessions
                WHERE user_id = ? AND revoked_at IS NULL AND expires_at > now() ORDER BY created_at DESC, id DESC LIMIT 20
                """, (rs, n) -> new SessionResponse(rs.getObject("id", UUID.class),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("last_used_at").toInstant(), currentId.equals(rs.getObject("id", UUID.class))), owner);
    }

    @Transactional
    public void revoke(UUID id) {
        UUID owner = current.id();
        lockUser(owner);
        if (jdbc.update("UPDATE auth_sessions SET revoked_at = coalesce(revoked_at, now()) WHERE id = ? AND user_id = ?",
                id, owner) == 0) throw new ResourceNotFoundException("Session not found.");
    }

    @Transactional
    public void logout() { revoke(current.sessionId()); }

    @Transactional
    public void revokeAll() {
        UUID owner = current.id();
        lockUser(owner);
        jdbc.update("UPDATE auth_sessions SET revoked_at = coalesce(revoked_at, now()) WHERE user_id = ?", owner);
    }

    public void lockUser(UUID id) {
        if (jdbc.queryForList("SELECT id FROM users WHERE id = ? FOR UPDATE", UUID.class, id).isEmpty()) throw invalid();
    }

    public void lockByEmail(String email) {
        jdbc.queryForList("SELECT id FROM users WHERE email = ? FOR UPDATE", UUID.class, email);
    }

    private static BadCredentialsException invalid() { return new BadCredentialsException("Invalid credentials."); }
}
