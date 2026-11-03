package com.teamflow.filter;

import com.teamflow.common.*;
import com.teamflow.membership.WorkspaceAccess;
import com.teamflow.project.ProjectService;
import com.teamflow.security.CurrentUser;
import com.teamflow.task.TaskPriority;
import com.teamflow.task.TaskStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class SavedFilterService {
    private final JdbcTemplate jdbc;
    private final ProjectService projects;
    private final WorkspaceAccess access;
    private final CurrentUser current;
    public SavedFilterService(JdbcTemplate jdbc, ProjectService projects, WorkspaceAccess access, CurrentUser current) {
        this.jdbc = jdbc; this.projects = projects; this.access = access; this.current = current;
    }
    private static final RowMapper<SavedFilterResponse> MAPPER = (rs, n) -> new SavedFilterResponse(
            rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class), rs.getString("name"), rs.getString("query"),
            rs.getString("status") == null ? null : TaskStatus.valueOf(rs.getString("status")),
            rs.getString("priority") == null ? null : TaskPriority.valueOf(rs.getString("priority")),
            rs.getObject("assignee_id", UUID.class), rs.getBoolean("overdue"), rs.getLong("version"));

    public List<SavedFilterResponse> list(UUID workspaceId, UUID projectId) {
        projects.requireProject(workspaceId, projectId);
        return jdbc.query("SELECT * FROM saved_filters WHERE project_id = ? AND user_id = ? ORDER BY created_at DESC, id DESC LIMIT 50",
                MAPPER, projectId, current.id());
    }

    @Transactional
    public SavedFilterResponse create(UUID workspaceId, UUID projectId, SavedFilterRequest request) {
        access.lockAndRequireMember(workspaceId);
        projects.requireProject(workspaceId, projectId);
        UUID owner = current.id();
        if (jdbc.queryForObject("SELECT count(*) FROM saved_filters WHERE project_id = ? AND user_id = ?", Integer.class, projectId, owner) >= 50) {
            throw new ConflictException("At most 50 saved filters per project.");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO saved_filters (id, project_id, user_id, name, query, status, priority, assignee_id, overdue, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())
                """, id, projectId, owner, request.name().strip(), request.q(), name(request.status()), name(request.priority()), request.assigneeId(), request.overdue());
        return get(projectId, id);
    }

    @Transactional
    public SavedFilterResponse update(UUID workspaceId, UUID projectId, UUID id, SavedFilterRequest request, String ifMatch) {
        access.lockAndRequireMember(workspaceId);
        projects.requireProject(workspaceId, projectId);
        check(get(projectId, id), ifMatch);
        jdbc.update("UPDATE saved_filters SET name = ?, query = ?, status = ?, priority = ?, assignee_id = ?, overdue = ?, version = version + 1, updated_at = now() WHERE id = ?",
                request.name().strip(), request.q(), name(request.status()), name(request.priority()), request.assigneeId(), request.overdue(), id);
        return get(projectId, id);
    }

    @Transactional
    public void delete(UUID workspaceId, UUID projectId, UUID id, String ifMatch) {
        access.lockAndRequireMember(workspaceId);
        projects.requireProject(workspaceId, projectId);
        check(get(projectId, id), ifMatch);
        jdbc.update("DELETE FROM saved_filters WHERE id = ?", id);
    }

    private SavedFilterResponse get(UUID projectId, UUID id) {
        var rows = jdbc.query("SELECT * FROM saved_filters WHERE id = ? AND project_id = ? AND user_id = ?", MAPPER, id, projectId, current.id());
        if (rows.isEmpty()) throw new ResourceNotFoundException("Saved filter not found.");
        return rows.getFirst();
    }
    private String name(Enum<?> value) { return value == null ? null : value.name(); }
    private void check(SavedFilterResponse value, String header) {
        VersionCondition.check(value.id(), value.version(), header);
    }
}
