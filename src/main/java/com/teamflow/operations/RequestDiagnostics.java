package com.teamflow.operations;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestDiagnostics extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-ID";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Generate locally: an untrusted caller cannot inject log content or choose someone else's ID.
        String id = UUID.randomUUID().toString();
        request.setAttribute("requestId", id);
        response.setHeader(HEADER, id);
        String previous = MDC.get("requestId");
        MDC.put("requestId", id);
        long start = System.nanoTime();
        boolean completed = false;
        try {
            chain.doFilter(request, response);
            completed = true;
        } finally {
            Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            // No raw path, query, body, headers, IP or account identifiers in the access log.
            LoggerFactory.getLogger(RequestDiagnostics.class).info("HTTP method={} route={} status={} durationMs={}",
                    request.getMethod(), route == null ? "unmatched" : route, completed ? response.getStatus() : 500,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            if (previous == null) MDC.remove("requestId"); else MDC.put("requestId", previous);
        }
    }
}
