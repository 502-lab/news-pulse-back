package com.newscurator.security;

import com.newscurator.domain.enums.AccountRole;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public class CustomUserDetails implements UserDetails {

    private final UUID accountId;
    private final AccountRole role;
    private final boolean emailVerified;

    public CustomUserDetails(UUID accountId, AccountRole role, boolean emailVerified) {
        this.accountId = accountId;
        this.role = role;
        this.emailVerified = emailVerified;
    }

    public UUID getAccountId() { return accountId; }
    public AccountRole getRole() { return role; }
    public boolean isEmailVerified() { return emailVerified; }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override public String getPassword() { return ""; }
    @Override public String getUsername() { return accountId.toString(); }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return true; }
}
