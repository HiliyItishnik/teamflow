package com.teamflow.operations;

import com.teamflow.security.SecurityProblemHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Configuration
public class OperationsSecurity {
    @Bean
    @Order(1)
    SecurityFilterChain operationsFilterChain(HttpSecurity http, SecurityProblemHandler errors,
            @Value("${teamflow.operations.key:}") String key) throws Exception {
        byte[] expected = key.getBytes(StandardCharsets.UTF_8);
        return http.securityMatcher("/actuator/metrics", "/actuator/metrics/**", "/actuator/prometheus", "/actuator/info")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().access((authentication, context) -> {
                    String supplied = context.getRequest().getHeader("X-Operations-Key");
                    boolean valid = expected.length >= 32 && supplied != null && supplied.length() <= 256
                            && MessageDigest.isEqual(expected, supplied.getBytes(StandardCharsets.UTF_8));
                    return new AuthorizationDecision(valid);
                }))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .build();
    }
}
