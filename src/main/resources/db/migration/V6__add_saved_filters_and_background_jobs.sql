CREATE TABLE saved_filters (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(80) NOT NULL CHECK (length(trim(name)) > 0),
    query VARCHAR(200),
    status VARCHAR(20) CHECK (status IN ('TODO', 'IN_PROGRESS', 'DONE')),
    priority VARCHAR(20) CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH')),
    assignee_id UUID,
    overdue BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (project_id, user_id, name)
);
CREATE INDEX idx_saved_filters_owner ON saved_filters(project_id, user_id, created_at DESC, id DESC);

CREATE TABLE background_jobs (
    id UUID PRIMARY KEY,
    sequence BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    kind VARCHAR(30) NOT NULL CHECK (kind IN ('ASSIGNED', 'COMMENTED', 'PASSWORD_RECOVERY')),
    task_id UUID REFERENCES tasks(id) ON DELETE CASCADE,
    recipient_id UUID REFERENCES users(id) ON DELETE CASCADE,
    actor_name VARCHAR(120),
    email VARCHAR(254),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    available_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    attempts INTEGER NOT NULL DEFAULT 0,
    claim_id UUID,
    leased_until TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    failed_at TIMESTAMPTZ,
    last_error VARCHAR(80),
    CHECK ((kind = 'PASSWORD_RECOVERY' AND email IS NOT NULL AND task_id IS NULL AND recipient_id IS NULL)
        OR (kind <> 'PASSWORD_RECOVERY' AND email IS NULL AND task_id IS NOT NULL AND recipient_id IS NOT NULL))
);
CREATE INDEX idx_background_jobs_pending ON background_jobs(available_at, sequence)
    WHERE completed_at IS NULL AND failed_at IS NULL;
CREATE INDEX idx_background_jobs_email ON background_jobs(email, sequence DESC) WHERE email IS NOT NULL;

CREATE TABLE notifications (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE REFERENCES background_jobs(id) ON DELETE CASCADE,
    read_at TIMESTAMPTZ
);
CREATE INDEX idx_background_jobs_recipient ON background_jobs(recipient_id, created_at DESC, id DESC);
CREATE INDEX idx_tasks_project_due ON tasks(project_id, due_date) WHERE status <> 'DONE' AND due_date IS NOT NULL;
