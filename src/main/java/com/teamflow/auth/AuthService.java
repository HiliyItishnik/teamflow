package com.teamflow.auth;

import com.teamflow.common.ConflictException;
import com.teamflow.security.CurrentUser;
import com.teamflow.security.TokenService;
import com.teamflow.user.User;
import com.teamflow.user.UserRepository;
import com.teamflow.user.UserResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AuthenticationManager authentication;
    private final TokenService tokens;
    private final CurrentUser currentUser;

    public AuthService(UserRepository users, PasswordEncoder encoder, AuthenticationManager authentication,
                       TokenService tokens, CurrentUser currentUser) {
        this.users = users;
        this.encoder = encoder;
        this.authentication = authentication;
        this.tokens = tokens;
        this.currentUser = currentUser;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (users.existsByEmail(request.email())) throw new ConflictException("Email is already registered.");
        User user = users.saveAndFlush(new User(request.email(), request.displayName(), encoder.encode(request.password())));
        return tokens.issue(user);
    }

    public AuthResponse login(LoginRequest request) {
        authentication.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(request.email(), request.password()));
        return tokens.issue(users.findByEmail(request.email()).orElseThrow());
    }

    public UserResponse me() { return UserResponse.from(currentUser.get()); }
}
