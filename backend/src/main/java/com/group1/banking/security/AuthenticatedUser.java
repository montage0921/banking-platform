package com.group1.banking.security;

import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public record AuthenticatedUser(
        String userId,
        Long customerId,
        List<String> roles,
        List<String> permissions) {

    public boolean isAdmin() {
        return roles.contains("BANK_ADMINISTRATOR");
    }

    public Collection<? extends GrantedAuthority> authorities() {
        return permissions.stream().map(SimpleGrantedAuthority::new).toList();
    }
}
