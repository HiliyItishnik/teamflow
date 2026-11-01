package com.teamflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import java.security.SecureRandom;
import java.util.Base64;

@SpringBootTest
@AutoConfigureMockMvc
abstract class PostgresIntegrationTest {
    private static final String TEST_KEY;
    static {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        TEST_KEY = Base64.getEncoder().encodeToString(bytes);
    }

    private static final class ContainerHolder {
        static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
        static { POSTGRES.start(); }
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("teamflow.jwt.secret", () -> TEST_KEY);
        String externalUrl = System.getenv("TEAMFLOW_TEST_DB_URL");
        if (externalUrl != null && !externalUrl.isBlank()) {
            registry.add("spring.datasource.url", () -> externalUrl);
            registry.add("spring.datasource.username", () -> System.getenv("TEAMFLOW_TEST_DB_USERNAME"));
            registry.add("spring.datasource.password", () -> System.getenv("TEAMFLOW_TEST_DB_PASSWORD"));
        } else {
            var postgres = ContainerHolder.POSTGRES;
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
        }
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected WebApplicationContext context;

    protected void clearDatabase() { jdbc.execute("TRUNCATE TABLE workspaces, users CASCADE"); }
}
