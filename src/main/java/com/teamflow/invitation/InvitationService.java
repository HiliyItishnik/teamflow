package com.teamflow.invitation;

import com.teamflow.common.ConflictException;
import com.teamflow.common.PageResponse;
import com.teamflow.common.Pagination;
import com.teamflow.common.ResourceNotFoundException;
import com.teamflow.membership.*;
import com.teamflow.security.CurrentUser;
import com.teamflow.security.SecretTokens;
import com.teamflow.workspace.WorkspaceRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class InvitationService {
    private final JdbcTemplate jdbc;
    private final WorkspaceAccess access;
    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberRepository members;
    private final CurrentUser current;

    public InvitationService(JdbcTemplate jdbc, WorkspaceAccess access, WorkspaceRepository workspaces,
                             WorkspaceMemberRepository members, CurrentUser current) {
        this.jdbc = jdbc; this.access = access; this.workspaces = workspaces; this.members = members; this.current = current;
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static final RowMapper<InvitationResponse> MAPPER = (rs, n) -> new InvitationResponse(
            rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class), rs.getString("email"),
            WorkspaceRole.valueOf(rs.getString("role")), instant(rs.getTimestamp("created_at")),
            instant(rs.getTimestamp("expires_at")), instant(rs.getTimestamp("accepted_at")), instant(rs.getTimestamp("revoked_at")));

    @Transactional
    public InvitationCreated create(UUID workspaceId, InvitationRequest request) {
        access.lockAndRequireAdmin(workspaceId);
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM workspace_members m JOIN users u ON u.id = m.user_id
                WHERE m.workspace_id = ? AND u.email = ?)
                """, Boolean.class, workspaceId, request.email()))) throw new ConflictException("User is already a workspace member.");
        // Resending replaces any previous pending invitation to this email.
        jdbc.update("UPDATE workspace_invitations SET revoked_at = now() WHERE workspace_id = ? AND email = ? "
                + "AND accepted_at IS NULL AND revoked_at IS NULL", workspaceId, request.email());
        UUID id = UUID.randomUUID();
        String token = SecretTokens.generate();
        jdbc.update("""
                INSERT INTO workspace_invitations (id, workspace_id, email, role, token_hash, expires_at)
                VALUES (?, ?, ?, ?, ?, now() + interval '7 days')
                """, id, workspaceId, request.email(), request.role().name(), SecretTokens.hash(token));
        return new InvitationCreated(jdbc.queryForObject("SELECT * FROM workspace_invitations WHERE id = ?", MAPPER, id), token);
    }

    public PageResponse<InvitationResponse> list(UUID workspaceId, int page, int size) {
        access.requireAdmin(workspaceId);
        var pageable = Pagination.of(page, size);
        long count = jdbc.queryForObject("SELECT count(*) FROM workspace_invitations WHERE workspace_id = ?", Long.class, workspaceId);
        var items = jdbc.query("SELECT * FROM workspace_invitations WHERE workspace_id = ? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                MAPPER, workspaceId, size, pageable.getOffset());
        return new PageResponse<>(items, page, size, count, (int) Math.ceil((double) count / size));
    }

    @Transactional
    public void revoke(UUID workspaceId, UUID id) {
        access.lockAndRequireAdmin(workspaceId);
        var rows = jdbc.query("SELECT * FROM workspace_invitations WHERE id = ? AND workspace_id = ?", MAPPER, id, workspaceId);
        if (rows.isEmpty()) throw notFound();
        if (rows.getFirst().acceptedAt() != null) throw new ConflictException("Invitation was already accepted. Manage membership instead.");
        jdbc.update("UPDATE workspace_invitations SET revoked_at = coalesce(revoked_at, now()) WHERE id = ?", id);
    }

    @Transactional
    public MemberResponse accept(String token) {
        var user = current.get();
        String hash = SecretTokens.hash(token);
        var scopes = jdbc.queryForList("SELECT workspace_id FROM workspace_invitations WHERE token_hash = ?", UUID.class, hash);
        if (scopes.isEmpty()) throw notFound();
        UUID workspaceId = scopes.getFirst();
        var workspace = workspaces.findByIdForUpdate(workspaceId).orElseThrow(InvitationService::notFound);
        var invitations = jdbc.query("SELECT * FROM workspace_invitations WHERE token_hash = ?", MAPPER, hash);
        if (invitations.isEmpty()) throw notFound();
        var invite = invitations.getFirst();
        if (!invite.email().equals(user.getEmail()) || invite.revokedAt() != null || invite.acceptedAt() != null
                || !invite.expiresAt().isAfter(Instant.now())) throw notFound();
        if (members.existsByWorkspaceIdAndUserId(workspaceId, user.getId())) {
            throw new ConflictException("User is already a workspace member.");
        }
        var result = members.saveAndFlush(new WorkspaceMember(workspace, user, invite.role()));
        jdbc.update("UPDATE workspace_invitations SET accepted_at = now() WHERE id = ?", invite.id());
        return MemberResponse.from(result);
    }

    private static ResourceNotFoundException notFound() { return new ResourceNotFoundException("Invitation not found or no longer valid."); }
}
