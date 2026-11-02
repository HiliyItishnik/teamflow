package com.teamflow.auth;

import com.teamflow.common.RequestConditionException;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

@RestController
@RequestMapping("/api/v1/auth/password")
@SecurityRequirements
public class PasswordRecoveryController {
    private final PasswordRecoveryService service;
    private final PasswordResetMail mail;
    private final ThreadPoolTaskExecutor executor;
    private final AuthRateLimiter limiter;
    public PasswordRecoveryController(PasswordRecoveryService service, PasswordResetMail mail,
                                      ThreadPoolTaskExecutor recoveryExecutor, AuthRateLimiter limiter) {
        this.service = service; this.mail = mail; this.executor = recoveryExecutor; this.limiter = limiter;
    }

    @PostMapping("/forgot")
    public ResponseEntity<Map<String, String>> forgot(@Valid @RequestBody RecoveryEmailRequest request, HttpServletRequest http) {
        limiter.checkIp(http.getRemoteAddr());
        if (!mail.available()) throw unavailable();
        try {
            limiter.check("reset-email", request.email(), 3, 900);
            executor.execute(() -> {
                try { service.deliver(request.email()); }
                catch (RuntimeException exception) {
                    LoggerFactory.getLogger(PasswordRecoveryController.class)
                            .warn("Password recovery delivery failed; no reset token was committed.");
                }
            });
        } catch (TooManyAttemptsException ignored) {
            // Same response for known/unknown accounts and account-level throttling.
        } catch (RejectedExecutionException exception) { throw unavailable(); }
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .body(Map.of("message", "Если аккаунт существует, письмо с инструкцией будет отправлено."));
    }

    @PostMapping("/reset")
    public ResponseEntity<Void> reset(@Valid @RequestBody ResetPasswordRequest request, HttpServletRequest http) {
        limiter.checkIp(http.getRemoteAddr());
        service.reset(request);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private RequestConditionException unavailable() {
        return new RequestConditionException(HttpStatus.SERVICE_UNAVAILABLE, "Password recovery is temporarily unavailable.");
    }
}
