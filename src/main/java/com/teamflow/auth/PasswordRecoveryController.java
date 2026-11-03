package com.teamflow.auth;

import com.teamflow.common.RequestConditionException;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth/password")
@SecurityRequirements
public class PasswordRecoveryController {
    private final PasswordRecoveryService service;
    private final PasswordResetMail mail;
    private final com.teamflow.job.BackgroundJobs jobs;
    private final AuthRateLimiter limiter;
    public PasswordRecoveryController(PasswordRecoveryService service, PasswordResetMail mail,
                                      com.teamflow.job.BackgroundJobs jobs, AuthRateLimiter limiter) {
        this.service = service; this.mail = mail; this.jobs = jobs; this.limiter = limiter;
    }

    @PostMapping("/forgot")
    public ResponseEntity<Map<String, String>> forgot(@Valid @RequestBody RecoveryEmailRequest request, HttpServletRequest http) {
        limiter.checkIp(http.getRemoteAddr());
        if (!mail.available()) throw unavailable();
        try {
            limiter.check("reset-email", request.email(), 3, 900);
            jobs.recoverPassword(request.email());
        } catch (TooManyAttemptsException ignored) {
            // Same response for known/unknown accounts and account-level throttling.
        }
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
