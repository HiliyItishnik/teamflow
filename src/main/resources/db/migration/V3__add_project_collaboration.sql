ALTER TABLE projects ADD CONSTRAINT uq_projects_id_workspace UNIQUE (id, workspace_id);

CREATE TABLE project_members (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    user_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (project_id, user_id),
    FOREIGN KEY (project_id, workspace_id) REFERENCES projects(id, workspace_id) ON DELETE CASCADE,
    FOREIGN KEY (workspace_id, user_id) REFERENCES workspace_members(workspace_id, user_id) ON DELETE CASCADE
);
CREATE INDEX idx_project_members_workspace_user ON project_members(workspace_id, user_id);

-- Preserve the access that existing workspace members had before project membership existed.
INSERT INTO project_members (id, workspace_id, project_id, user_id, created_at, updated_at, version)
SELECT gen_random_uuid(), p.workspace_id, p.id, m.user_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
FROM projects p JOIN workspace_members m ON m.workspace_id = p.workspace_id;

ALTER TABLE tasks ADD COLUMN assignee_id UUID;
ALTER TABLE tasks ADD CONSTRAINT fk_tasks_project_assignee
    FOREIGN KEY (project_id, assignee_id) REFERENCES project_members(project_id, user_id)
    ON DELETE SET NULL (assignee_id);
CREATE INDEX idx_tasks_project_assignee ON tasks(project_id, assignee_id);

-- FK-driven unassignment bypasses Hibernate. Advance the optimistic version so
-- a stale task update cannot restore the old assignment after a member rejoins.
CREATE FUNCTION touch_task_on_assignment_change() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    NEW.version := OLD.version + 1;
    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_task_assignment_version
    BEFORE UPDATE OF assignee_id ON tasks
    FOR EACH ROW WHEN (OLD.assignee_id IS DISTINCT FROM NEW.assignee_id AND NEW.version = OLD.version)
    EXECUTE FUNCTION touch_task_on_assignment_change();

CREATE TABLE task_comments (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    author_id UUID REFERENCES users(id) ON DELETE SET NULL,
    author_name VARCHAR(120) NOT NULL,
    body VARCHAR(5000) NOT NULL CHECK (length(trim(body)) > 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_task_comments_task_created ON task_comments(task_id, created_at, id);
CREATE INDEX idx_task_comments_author ON task_comments(author_id);

CREATE TABLE task_status_history (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    task_version BIGINT NOT NULL,
    event_type VARCHAR(20) NOT NULL CHECK (event_type IN ('CREATED', 'STATUS_CHANGED', 'MIGRATED')),
    from_status VARCHAR(20) CHECK (from_status IN ('TODO', 'IN_PROGRESS', 'DONE')),
    to_status VARCHAR(20) NOT NULL CHECK (to_status IN ('TODO', 'IN_PROGRESS', 'DONE')),
    actor_id UUID REFERENCES users(id) ON DELETE SET NULL,
    actor_name VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (task_id, task_version),
    CHECK ((event_type = 'STATUS_CHANGED' AND from_status IS NOT NULL AND from_status <> to_status)
        OR (event_type IN ('CREATED', 'MIGRATED') AND from_status IS NULL))
);
CREATE INDEX idx_task_status_history_actor ON task_status_history(actor_id);

-- This is an observed baseline, not a reconstruction of earlier status changes.
INSERT INTO task_status_history (id, task_id, task_version, event_type, from_status, to_status,
                                actor_id, actor_name, created_at, updated_at, version)
SELECT gen_random_uuid(), id, version, 'MIGRATED', NULL, status, NULL, 'Migration',
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0 FROM tasks;
