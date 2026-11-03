package com.teamflow.operations;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class QueueMetrics {
    private final JdbcTemplate jdbc;
    private final MeterRegistry registry;
    private volatile Map<String, Double> snapshot = Map.of("pending", 0d, "leased", 0d, "failed", 0d);
    private volatile double oldestCreatedEpoch;

    public QueueMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.registry = registry;
        for (String state : snapshot.keySet()) Gauge.builder("teamflow.jobs", this, value -> value.snapshot.get(state))
                .tag("state", state).description("Последний снимок очереди PostgreSQL").register(registry);
        Gauge.builder("teamflow.jobs.oldest.pending.seconds", this,
                value -> value.oldestCreatedEpoch == 0 ? 0 : Math.max(0, System.currentTimeMillis() / 1000d - value.oldestCreatedEpoch))
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${teamflow.operations.queue-poll-ms:30000}", initialDelay = 5000)
    public void refresh() {
        try {
            var row = jdbc.queryForMap("""
                    SELECT count(*) FILTER (WHERE failed_at IS NULL AND (leased_until IS NULL OR leased_until <= clock_timestamp())) AS pending,
                           count(*) FILTER (WHERE failed_at IS NULL AND leased_until > clock_timestamp()) AS leased,
                           count(*) FILTER (WHERE failed_at IS NOT NULL) AS failed,
                           extract(epoch FROM min(created_at) FILTER (WHERE failed_at IS NULL AND (leased_until IS NULL OR leased_until <= clock_timestamp()))) AS oldest
                    FROM background_jobs WHERE completed_at IS NULL
                    """);
            snapshot = Map.of("pending", ((Number) row.get("pending")).doubleValue(),
                    "leased", ((Number) row.get("leased")).doubleValue(), "failed", ((Number) row.get("failed")).doubleValue());
            oldestCreatedEpoch = row.get("oldest") == null ? 0 : ((Number) row.get("oldest")).doubleValue();
        } catch (RuntimeException exception) {
            registry.counter("teamflow.jobs.snapshot.errors").increment();
            LoggerFactory.getLogger(QueueMetrics.class).warn("Queue metrics snapshot failed: {}", exception.getClass().getSimpleName());
        }
    }
}
