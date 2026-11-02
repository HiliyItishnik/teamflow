package com.teamflow.auth;

import com.teamflow.user.UserResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication")
public class AuthController {
    private final AuthService service;
    private final SessionService sessions;
    private final AuthRateLimiter limiter;
    public AuthController(AuthService service, SessionService sessions, AuthRateLimiter limiter) {
        this.service = service; this.sessions = sessions; this.limiter = limiter;
    }

    @PostMapping("/register")
    @SecurityRequirements
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        limiter.checkIp(http.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(service.register(request));
    }

    @PostMapping("/login")
    @SecurityRequirements
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        limiter.checkIp(http.getRemoteAddr());
        limiter.check("login-email", request.email(), 10, 900);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.login(request));
    }

    @GetMapping("/me")
    public UserResponse me() { return service.me(); }

    @PostMapping("/refresh")
    @SecurityRequirements
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody SecretRequest request, HttpServletRequest http) {
        limiter.checkIp(http.getRemoteAddr());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(sessions.refresh(request.token()));
    }

    @GetMapping("/sessions")
    public ResponseEntity<List<SessionResponse>> sessions() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(sessions.list());
    }

    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<Void> revoke(@PathVariable UUID id) {
        sessions.revoke(id); return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/sessions")
    public ResponseEntity<Void> revokeAll() {
        sessions.revokeAll(); return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        sessions.logout(); return ResponseEntity.noContent().build();
    }
}
