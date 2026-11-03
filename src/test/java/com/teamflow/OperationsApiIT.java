package com.teamflow;

import com.teamflow.operations.QueueMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@TestPropertySource(properties = {"management.endpoints.web.exposure.include=health,info,metrics,prometheus",
        "teamflow.operations.key=integration-operations-key-not-for-deployment"})
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
class OperationsApiIT extends PostgresIntegrationTest {
    private static final String KEY = "integration-operations-key-not-for-deployment";
    @Autowired QueueMetrics queue;
    @Autowired MeterRegistry metrics;
    @BeforeEach void setup() { clearDatabase(); }

    @Test void generatesUniqueRequestIdsForSuccessAndSecurityErrors() throws Exception {
        var ok = mvc.perform(get("/actuator/health").header("X-Request-ID", "untrusted-client-id"))
                .andExpect(status().isOk()).andReturn().getResponse();
        String id = ok.getHeader("X-Request-ID");
        assertThat(UUID.fromString(id).toString()).isEqualTo(id);
        var denied = mvc.perform(get("/api/v1/workspaces")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.requestId").exists()).andReturn().getResponse();
        assertThat(denied.getHeader("X-Request-ID")).isNotEqualTo(id);
        assertThat(json.readTree(denied.getContentAsString()).path("requestId").asText()).isEqualTo(denied.getHeader("X-Request-ID"));
    }

    @Test void validationAndMalformedJsonCarrySameIdInBodyAndHeader() throws Exception {
        for (String body : new String[]{"{}", "{bad-json"}) {
            var response = mvc.perform(post("/api/v1/auth/register").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andReturn().getResponse();
            assertThat(json.readTree(response.getContentAsString()).path("requestId").asText())
                    .isEqualTo(response.getHeader("X-Request-ID"));
        }
    }

    @Test void applicationErrorsCarryRequestId() throws Exception {
        String token = register();
        var response = mvc.perform(get("/api/v1/workspaces/" + UUID.randomUUID()).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound()).andReturn().getResponse();
        assertThat(json.readTree(response.getContentAsString()).path("requestId").asText())
                .isEqualTo(response.getHeader("X-Request-ID"));
    }

    @Test void ordinaryUserTokenCannotReadOperationalMetricsOrInfo() throws Exception {
        String token = register();
        for (String path : new String[]{"/actuator/metrics", "/actuator/metrics/jvm.memory.used", "/actuator/prometheus", "/actuator/info"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("X-Operations-Key", "wrong")).andExpect(status().isUnauthorized());
        }
    }

    @Test void operatorCanScrapeMetricsWithoutUserSession() throws Exception {
        mvc.perform(get("/actuator/metrics").header("X-Operations-Key", KEY)).andExpect(status().isOk())
                .andExpect(jsonPath("$.names").isArray());
        mvc.perform(get("/actuator/prometheus").header("X-Operations-Key", KEY)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("teamflow_jobs")));
        mvc.perform(get("/actuator/env").header("X-Operations-Key", KEY)).andExpect(status().isUnauthorized());
    }

    @Test void queueSnapshotDistinguishesPendingLeasedAndFailed() {
        jdbc.update("INSERT INTO background_jobs(id,kind,email) VALUES (?, 'PASSWORD_RECOVERY', 'queue@example.test')", UUID.randomUUID());
        jdbc.update("INSERT INTO background_jobs(id,kind,email,leased_until) VALUES (?, 'PASSWORD_RECOVERY', 'queue@example.test', now()+interval '1 minute')", UUID.randomUUID());
        jdbc.update("INSERT INTO background_jobs(id,kind,email,failed_at) VALUES (?, 'PASSWORD_RECOVERY', 'queue@example.test', now())", UUID.randomUUID());
        queue.refresh();
        for (String state : new String[]{"pending", "leased", "failed"})
            assertThat(metrics.get("teamflow.jobs").tag("state", state).gauge().value()).isEqualTo(1);
        assertThat(metrics.get("teamflow.jobs.oldest.pending.seconds").gauge().value()).isGreaterThanOrEqualTo(0);
    }

    private String register() throws Exception {
        var response = mvc.perform(post("/api/v1/auth/register").contentType("application/json")
                .content(json.writeValueAsString(Map.of("email", "ops@example.test", "displayName", "Operator test", "password", "Local-test-password-123"))))
                .andExpect(status().isCreated()).andReturn().getResponse();
        return json.readTree(response.getContentAsString()).path("accessToken").asText();
    }
}
