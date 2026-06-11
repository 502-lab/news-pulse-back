package com.newscurator.domain;

import com.newscurator.domain.enums.AccountRole;
import com.newscurator.domain.enums.AccountStatus;
import com.newscurator.domain.enums.SignupType;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "accounts")
@Getter
@NoArgsConstructor
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(unique = true)
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountRole role = AccountRole.USER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status = AccountStatus.ACTIVE;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = false;

    @Column(name = "onboarding_completed", nullable = false)
    private boolean onboardingCompleted = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "signup_type", nullable = false, length = 20)
    private SignupType signupType;

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount = 0;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Builder
    public Account(String email, String passwordHash, AccountRole role, AccountStatus status,
                   boolean emailVerified, boolean onboardingCompleted, SignupType signupType) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role != null ? role : AccountRole.USER;
        this.status = status != null ? status : AccountStatus.ACTIVE;
        this.emailVerified = emailVerified;
        this.onboardingCompleted = onboardingCompleted;
        this.signupType = signupType;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = Instant.now();
    }

    public void verifyEmail() {
        this.emailVerified = true;
    }

    public void recordLoginFailure(Instant lockUntil) {
        this.failedLoginCount++;
        if (this.failedLoginCount >= 5) {
            this.lockedUntil = lockUntil;
        }
    }

    public void resetLoginFailure() {
        this.failedLoginCount = 0;
        this.lockedUntil = null;
    }

    public boolean isLocked() {
        return lockedUntil != null && lockedUntil.isAfter(Instant.now());
    }

    public boolean isSuspended() {
        return status == AccountStatus.SUSPENDED;
    }
}
