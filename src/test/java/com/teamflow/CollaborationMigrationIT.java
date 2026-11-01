package com.teamflow;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import javax.sql.DataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class CollaborationMigrationIT extends PostgresIntegrationTest {
    @Autowired DataSource dataSource;

    @Test
    void upgradesExistingTeamsWithoutInventingHistoricalTransitions() {
        String schema = "migration_test_" + UUID.randomUUID().toString().replace("-", "");
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("2").load().migrate();
            UUID user = UUID.randomUUID();
            UUID workspace = UUID.randomUUID();
            UUID project = UUID.randomUUID();
            UUID task = UUID.randomUUID();
            jdbc.update("INSERT INTO " + schema + ".users (id,email,display_name,password_hash,created_at,updated_at,version) "
                    + "VALUES (?, 'old@example.test', 'Existing user', 'unused-test-hash', now(), now(), 0)", user);
            jdbc.update("INSERT INTO " + schema + ".workspaces (id,name,created_at,updated_at,version) VALUES (?, 'Existing team', now(), now(), 0)", workspace);
            jdbc.update("INSERT INTO " + schema + ".workspace_members (id,workspace_id,user_id,role,created_at,updated_at,version) "
                    + "VALUES (?, ?, ?, 'MEMBER', now(), now(), 0)", UUID.randomUUID(), workspace, user);
            jdbc.update("INSERT INTO " + schema + ".projects (id,workspace_id,name,created_at,updated_at,version) "
                    + "VALUES (?, ?, 'Existing project', now(), now(), 0)", project, workspace);
            jdbc.update("INSERT INTO " + schema + ".tasks (id,project_id,title,status,priority,created_at,updated_at,version) "
                    + "VALUES (?, ?, 'Existing task', 'IN_PROGRESS', 'HIGH', now() - interval '1 day', now(), 7)", task, project);

            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();

            assertThat(jdbc.queryForObject("SELECT user_id FROM " + schema + ".project_members WHERE project_id = ?", UUID.class, project)).isEqualTo(user);
            var event = jdbc.queryForMap("SELECT event_type, from_status, to_status, actor_id, task_version FROM " + schema + ".task_status_history WHERE task_id = ?", task);
            assertThat(event).containsEntry("event_type", "MIGRATED").containsEntry("from_status", null)
                    .containsEntry("to_status", "IN_PROGRESS").containsEntry("actor_id", null).containsEntry("task_version", 7L);
            assertThat(jdbc.queryForObject("SELECT title FROM " + schema + ".tasks WHERE id = ?", String.class, task)).isEqualTo("Existing task");
            assertThat(jdbc.queryForObject("SELECT assignee_id FROM " + schema + ".tasks WHERE id = ?", UUID.class, task)).isNull();
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }
}
