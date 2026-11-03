package com.teamflow.notification;

import com.teamflow.common.PageResponse;
import com.teamflow.common.ResourceNotFoundException;
import com.teamflow.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class NotificationService {
    // Re-check access on delivery AND on every read, including unread counts.
    public static final String VISIBLE = """
            EXISTS (SELECT 1 FROM workspace_members wm WHERE wm.workspace_id = p.workspace_id AND wm.user_id = j.recipient_id
              AND (wm.role = 'ADMIN' OR EXISTS (SELECT 1 FROM project_members pm WHERE pm.project_id = p.id AND pm.user_id = j.recipient_id)))
            """;
    private static final String FROM = " FROM notifications n JOIN background_jobs j ON j.id = n.event_id "
            + "JOIN tasks t ON t.id = j.task_id JOIN projects p ON p.id = t.project_id WHERE j.recipient_id = ? AND " + VISIBLE;
    private final JdbcTemplate jdbc;
    private final CurrentUser current;
    public NotificationService(JdbcTemplate jdbc, CurrentUser current) { this.jdbc = jdbc; this.current = current; }

    public PageResponse<NotificationResponse> list(boolean unread, int page, int size) {
        UUID user = current.id();
        String clause = FROM + (unread ? " AND n.read_at IS NULL" : "");
        long count = jdbc.queryForObject("SELECT count(*)" + clause, Long.class, user);
        var items = jdbc.query("SELECT n.id, j.kind, j.actor_name, p.workspace_id, t.project_id, t.id AS task_id, t.title, j.created_at, n.read_at"
                + clause + " ORDER BY j.created_at DESC, n.id DESC LIMIT ? OFFSET ?", (rs, n) -> new NotificationResponse(
                rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("actor_name"), rs.getObject("workspace_id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getObject("task_id", UUID.class), rs.getString("title"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant()), user, size, (long) page * size);
        return new PageResponse<>(items, page, size, count, (int) Math.ceil((double) count / size));
    }

    public long unreadCount() { return jdbc.queryForObject("SELECT count(*)" + FROM + " AND n.read_at IS NULL", Long.class, current.id()); }

    @Transactional
    public void read(UUID id) {
        int updated = jdbc.update("UPDATE notifications SET read_at = coalesce(read_at, now()) WHERE id IN (SELECT n.id"
                + FROM + " AND n.id = ?)", current.id(), id);
        if (updated == 0) throw new ResourceNotFoundException("Notification not found.");
    }

    @Transactional
    public void readAll() {
        jdbc.update("UPDATE notifications SET read_at = now() WHERE read_at IS NULL AND id IN (SELECT n.id" + FROM + ")", current.id());
    }
}
