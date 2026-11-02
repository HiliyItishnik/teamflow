package com.teamflow.security;

import com.teamflow.user.User;
import com.teamflow.user.UserRepository;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class CurrentUser {
    private final UserRepository users;
    public CurrentUser(UserRepository users) { this.users = users; }

    public User get() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token) || !token.isAuthenticated()) {
            throw new BadCredentialsException("Authentication required.");
        }
        UUID id = UUID.fromString(token.getToken().getSubject());
        return users.findById(id).orElseThrow(() -> new BadCredentialsException("Invalid credentials."));
    }

    public UUID id() { return get().getId(); }

    public UUID sessionId() {
        var authentication = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        return UUID.fromString(authentication.getToken().getClaimAsString("sid"));
    }
}
