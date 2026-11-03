package com.teamflow.operations;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;
import jakarta.servlet.ServletException;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import static org.assertj.core.api.Assertions.*;

class RequestDiagnosticsTest {
    @Test void logsRouteTemplateWithoutSensitiveValuesAndRestoresMdc() throws Exception {
        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(RequestDiagnostics.class);
        var events = new ListAppender<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) { event.prepareForDeferredProcessing(); super.append(event); }
        };
        events.start(); logger.addAppender(events);
        MDC.put("requestId", "outer-context");
        try {
            var request = new MockHttpServletRequest("GET", "/api/v1/tasks/private-task-id");
            request.setQueryString("token=private-secret");
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/tasks/{id}");
            var response = new MockHttpServletResponse();
            new RequestDiagnostics().doFilter(request, response, (req, res) -> res.getWriter().write("OK"));
            assertThat(events.list).hasSize(1);
            assertThat(events.list.getFirst().getFormattedMessage()).contains("route=/api/v1/tasks/{id}")
                    .doesNotContain("private-task-id", "private-secret");
            assertThat(events.list.getFirst().getMDCPropertyMap().get("requestId")).isEqualTo(response.getHeader("X-Request-ID"));
            assertThat(MDC.get("requestId")).isEqualTo("outer-context");
        } finally { MDC.remove("requestId"); logger.detachAppender(events); events.stop(); }
    }

    @Test void clearsMdcWhenRequestThrows() {
        MDC.remove("requestId");
        assertThatThrownBy(() -> new RequestDiagnostics().doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (request, response) -> { throw new ServletException("failure"); })).isInstanceOf(ServletException.class);
        assertThat(MDC.get("requestId")).isNull();
    }
}
