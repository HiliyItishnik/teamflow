package com.teamflow.user;

import com.teamflow.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Locale;

@Entity
@Table(name = "users")
public class User extends BaseEntity {
    @Column(nullable = false, unique = true, length = 254)
    private String email;
    @Column(nullable = false, length = 120)
    private String displayName;
    @Column(nullable = false, length = 100)
    private String passwordHash;

    protected User() {}

    public User(String email, String displayName, String passwordHash) {
        this.email = normalizeEmail(email);
        this.displayName = displayName.strip();
        this.passwordHash = passwordHash;
    }

    public static String normalizeEmail(String email) { return email.strip().toLowerCase(Locale.ROOT); }
    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }
    public String getPasswordHash() { return passwordHash; }
}
