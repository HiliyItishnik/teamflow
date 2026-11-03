package com.teamflow;

import com.teamflow.security.SecretTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("proxy")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "TRUSTED_PROXY_ADDRESS=127.0.0.2")
class ProxyClientIpIT extends PostgresIntegrationTest {
    @LocalServerPort int port;
    @BeforeEach void setup() { clearDatabase(); }

    @Test void untrustedConnectionCannotRotateForwardedIpsToBypassLimit() throws Exception {
        try (var client = client("127.0.0.1")) {
            for (int i = 1; i <= 60; i++) assertThat(refresh(client, "198.51.100." + i).statusCode()).isEqualTo(401);
            var limited = refresh(client, "203.0.113.99");
            assertThat(limited.statusCode()).isEqualTo(429);
            assertThat(Integer.parseInt(limited.headers().firstValue("Retry-After").orElseThrow())).isBetween(1, 60);
            assertThat(json.readTree(limited.body()).path("requestId").asText())
                    .isEqualTo(limited.headers().firstValue("X-Request-ID").orElseThrow());
            assertThat(attempts("127.0.0.1")).isEqualTo(61);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_rate_limits", Integer.class)).isEqualTo(1);
        }
    }

    @Test void trustedProxyKeepsClientLimitsSeparate() throws Exception {
        try (var client = client("127.0.0.2")) {
            for (int i = 0; i < 60; i++) assertThat(refresh(client, "198.51.100.10").statusCode()).isEqualTo(401);
            assertThat(refresh(client, "198.51.100.10").statusCode()).isEqualTo(429);
            assertThat(refresh(client, "198.51.100.11").statusCode()).isEqualTo(401);
            assertThat(attempts("198.51.100.10")).isEqualTo(61);
            assertThat(attempts("198.51.100.11")).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_rate_limits", Integer.class)).isEqualTo(2);
        }
    }

    @Test void untrustedConnectionCannotChangeExternalSchemeOrHost() throws Exception {
        try (var client = client("127.0.0.1")) {
            var response = client.send(request("/v3/api-docs").header("X-Forwarded-For", "198.51.100.10")
                    .header("X-Forwarded-Proto", "https").header("X-Forwarded-Host", "attacker.example")
                    .header("Forwarded", "for=198.51.100.10;proto=https;host=attacker.example").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            URI server = URI.create(json.readTree(response.body()).path("servers").get(0).path("url").asText());
            assertThat(server.getScheme()).isEqualTo("http");
            assertThat(server.getHost()).isEqualTo("127.0.0.1");
            assertThat(server.getPort()).isEqualTo(port);
        }
    }

    @Test void trustedProxyConveysHttpsScheme() throws Exception {
        try (var client = client("127.0.0.2")) {
            var response = client.send(request("/v3/api-docs").header("X-Forwarded-For", "198.51.100.12")
                    .header("X-Forwarded-Proto", "https").header("X-Forwarded-Host", "teamflow.example.test")
                    .header("X-Forwarded-Port", "8443").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            URI server = URI.create(json.readTree(response.body()).path("servers").get(0).path("url").asText());
            assertThat(server.getScheme()).isEqualTo("https");
            assertThat(server.getHost()).isEqualTo("teamflow.example.test");
            assertThat(server.getPort()).isEqualTo(8443);
        }
    }

    private HttpResponse<String> refresh(HttpClient client, String forwarded) throws Exception {
        return client.send(request("/api/v1/auth/refresh").header("Content-Type", "application/json")
                .header("X-Forwarded-For", forwarded).header("X-Forwarded-Proto", "https")
                .POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"unknown-test-token\"}")).build(), HttpResponse.BodyHandlers.ofString());
    }
    private int attempts(String address) {
        return jdbc.queryForObject("SELECT attempts FROM auth_rate_limits WHERE bucket_hash=?", Integer.class, SecretTokens.hash("auth-ip:" + address));
    }
    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
    }
    private static HttpClient client(String address) throws Exception {
        return HttpClient.newBuilder().localAddress(InetAddress.getByName(address)).proxy(HttpClient.Builder.NO_PROXY)
                .connectTimeout(Duration.ofSeconds(10)).build();
    }
}
