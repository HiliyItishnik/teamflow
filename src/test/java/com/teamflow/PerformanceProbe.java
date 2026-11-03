package com.teamflow;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import javax.sql.DataSource;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Run explicitly with -Pperformance. Uses only the disposable integration-test database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.stat=OFF", "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF",
        "logging.level.com.teamflow.operations.RequestDiagnostics=WARN"})
class PerformanceProbe extends PostgresIntegrationTest {
    @LocalServerPort int port;
    @Autowired EntityManagerFactory entities;
    @Autowired DataSource dataSource;
    @Autowired MeterRegistry metrics;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private String token;
    private String tasks;
    private final Map<String, Object> report = new LinkedHashMap<>();

    @Test void measureLargeBoardSqlLatencyAndWorkspaceLock() throws Exception {
        String id = UUID.randomUUID().toString();
        var auth = call("POST", "/api/v1/auth/register", Map.of("email", "perf-" + id + "@example.test", "displayName", "Performance", "password", "Local-probe-password-123"), 201);
        token = auth.path("accessToken").asText();
        String workspace = call("POST", "/api/v1/workspaces", Map.of("name", "Performance " + id), 201).path("id").asText();
        String projects = "/api/v1/workspaces/" + workspace + "/projects";
        String project = call("POST", projects, Map.of("name", "Large board"), 201).path("id").asText();
        tasks = projects + "/" + project + "/tasks";
        List<UUID> projectIds = new ArrayList<>(); projectIds.add(UUID.fromString(project));
        for (int i = 1; i < 10; i++) projectIds.add(UUID.fromString(call("POST", projects, Map.of("name", "Other " + i), 201).path("id").asText()));
        jdbc.update("""
                INSERT INTO users(id,email,display_name,password_hash,created_at,updated_at,version)
                SELECT gen_random_uuid(), 'perf-fixture-' || ? || '-' || n || '@example.test', 'Fixture ' || n,
                  u.password_hash, now(), now(), 0 FROM generate_series(1,100) n CROSS JOIN users u WHERE u.id=?
                """, id, UUID.fromString(auth.path("user").path("id").asText()));
        jdbc.update("""
                INSERT INTO workspace_members(id,workspace_id,user_id,role,created_at,updated_at,version)
                SELECT gen_random_uuid(), ?, id, 'MEMBER', now(), now(), 0 FROM users WHERE email LIKE ?
                """, UUID.fromString(workspace), "perf-fixture-" + id + "-%");
        for (UUID projectId : projectIds) jdbc.update("""
                INSERT INTO project_members(id,workspace_id,project_id,user_id,created_at,updated_at,version)
                SELECT gen_random_uuid(), ?, ?, id, now(), now(), 0 FROM users WHERE email LIKE ?
                """, UUID.fromString(workspace), projectId, "perf-fixture-" + id + "-%");
        for (UUID projectId : projectIds) jdbc.update("""
                INSERT INTO tasks(id,project_id,title,description,status,priority,due_date,created_at,updated_at,version,assignee_id)
                SELECT gen_random_uuid(), ?, 'Task ' || n, CASE WHEN n % 100 = 0 THEN 'Search needle' ELSE 'Description' END,
                  CASE WHEN n % 3 = 0 THEN 'DONE' WHEN n % 3 = 1 THEN 'TODO' ELSE 'IN_PROGRESS' END,
                  CASE WHEN n % 2 = 0 THEN 'HIGH' ELSE 'LOW' END,
                  (now() AT TIME ZONE 'UTC')::date - (n % 30), now()-n*interval '1 second', now(), 0, a.ids[1+(n%101)]
                FROM generate_series(1,10000) n CROSS JOIN
                  (SELECT array_agg(user_id ORDER BY user_id) ids FROM project_members WHERE project_id=?) a
                """, projectId, projectId);
        jdbc.execute("ANALYZE tasks");
        report.put("measuredAtUtc", Instant.now().toString());
        report.put("java", System.getProperty("java.version"));
        report.put("os", System.getProperty("os.name"));
        report.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        report.put("postgres", jdbc.queryForObject("SHOW server_version", String.class));
        report.put("fixture", Map.of("projects", 10, "tasks", 100000, "tasksPerProject", 10000, "assignees", 101, "concurrency", 1, "warmupPerScenario", 5, "requestsPerScenario", 100));
        List<Map<String, Object>> scenarios = new ArrayList<>();
        var statistics = entities.unwrap(SessionFactory.class).getStatistics();
        for (String query : List.of("?size=1", "?size=30", "?size=100", "?q=needle&size=30", "?overdue=true&priority=HIGH&size=30", "?page=300&size=30")) {
            for (int i = 0; i < 5; i++) call("GET", tasks + query, null, 200);
            statistics.clear();
            double[] elapsed = new double[100];
            for (int i = 0; i < elapsed.length; i++) {
                long start = System.nanoTime(); call("GET", tasks + query, null, 200);
                elapsed[i] = (System.nanoTime() - start) / 1_000_000d;
            }
            Arrays.sort(elapsed);
            double statements = statistics.getPrepareStatementCount() / 100d;
            assertThat(statements).as("Hibernate statements per board request").isLessThanOrEqualTo(8);
            scenarios.add(Map.of("query", query, "p50Ms", elapsed[49], "p95Ms", elapsed[94], "p99Ms", elapsed[98], "hibernateStatementsPerRequest", statements));
        }
        report.put("http", scenarios);
        Map<String, Object> plans = new LinkedHashMap<>();
        for (var entry : Map.of("board", "", "search", " AND (lower(title) LIKE '%needle%' OR lower(description) LIKE '%needle%')", "overdue", " AND due_date < (now() AT TIME ZONE 'UTC')::date AND status <> 'DONE' AND priority='HIGH'").entrySet()) {
            plans.put(entry.getKey(), json.readTree(jdbc.queryForObject("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) SELECT * FROM tasks WHERE project_id=?"
                    + entry.getValue() + " ORDER BY created_at DESC,id DESC OFFSET 0 ROWS FETCH FIRST 30 ROWS ONLY", String.class, UUID.fromString(project))));
        }
        report.put("explain", plans);
        measureLock(UUID.fromString(workspace));
        Path output = Path.of("target/performance/report.json"); Files.createDirectories(output.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        System.out.println("Performance report: " + output.toAbsolutePath());
    }

    private void measureLock(UUID workspace) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement("SELECT id FROM workspaces WHERE id=? FOR UPDATE")) {
                statement.setObject(1, workspace); statement.executeQuery().close();
            }
            long start = System.nanoTime();
            var waiting = executor.submit(() -> call("POST", tasks, Map.of("title", "Lock measurement", "status", "TODO", "priority", "HIGH"), 201));
            boolean observed = false;
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (System.nanoTime() < deadline) {
                    observed = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%workspaces%' AND pid<>pg_backend_pid())", Boolean.class));
                    if (observed) break;
                    Thread.sleep(10);
                }
                assertThat(observed).as("PostgreSQL observed the HTTP writer waiting for workspace lock").isTrue();
                Thread.sleep(400);
            } finally { blocker.rollback(); }
            waiting.get(15, TimeUnit.SECONDS);
            var timer = metrics.get("teamflow.workspace.lock").timer();
            report.put("lock", Map.of("observedInPgStatActivity", observed, "heldAfterObservationMs", 400,
                    "requestMs", (System.nanoTime()-start)/1_000_000d, "maxWorkspaceLockMs", timer.max(TimeUnit.MILLISECONDS)));
        } finally { executor.shutdownNow(); }
    }

    private JsonNode call(String method, String path, Object body, int expected) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(30));
        if (token != null) request.header("Authorization", "Bearer " + token);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).header("Content-Type", "application/json");
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(method + " " + path).isEqualTo(expected);
        return json.readTree(response.body());
    }
}
