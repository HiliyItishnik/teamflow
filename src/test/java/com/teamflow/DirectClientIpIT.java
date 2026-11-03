package com.teamflow;

import com.teamflow.security.SecretTokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DirectClientIpIT extends PostgresIntegrationTest {
    @LocalServerPort int port;

    @Test void defaultLocalLaunchIgnoresAllForwardedHeaders() throws Exception {
        clearDatabase();
        try (var client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/refresh"))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .header("X-Forwarded-For", "198.51.100.10").header("X-Forwarded-Proto", "https")
                    .header("Forwarded", "for=203.0.113.5;proto=https")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"unknown-test-token\"}")).build();
            assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
            assertThat(jdbc.queryForObject("SELECT bucket_hash FROM auth_rate_limits", String.class))
                    .isEqualTo(SecretTokens.hash("auth-ip:127.0.0.1"));
        }
    }
}
