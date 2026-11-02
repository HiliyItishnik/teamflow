ALTER TABLE projects ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX idx_projects_workspace_archived_created ON projects(workspace_id, archived, created_at DESC, id DESC);

-- Serialized with the workspace lock and committed atomically with the created resource.
-- Keep responses after task/comment deletion so a delayed retry cannot recreate deleted data.
CREATE TABLE creation_requests (
    actor_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    scope VARCHAR(100) NOT NULL,
    request_key UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (actor_id, scope, request_key)
);
CREATE INDEX idx_creation_requests_project ON creation_requests(project_id);
