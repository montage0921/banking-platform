package com.group1.banking.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.group1.banking.entity.User;
import com.group1.banking.enums.Permission;
import com.group1.banking.enums.RoleName;

import java.security.Principal;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class CustomUserPrincipal implements Principal {

    private final UUID userId;
    private final Long customerId;
    private final boolean active;
    private final Set<GrantedAuthority> authorities;

    public CustomUserPrincipal(User user) {
        this.userId = user.getUserId();
        this.customerId = user.getCustomerId();
        this.active = user.isActive();
        this.authorities = buildAuthorities(user);
    }

    private Set<GrantedAuthority> buildAuthorities(User user) {
        Set<GrantedAuthority> result = new HashSet<>();
        for (RoleName role : user.getRoles()) {
            result.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
            if (role == RoleName.RETAIL_CUSTOMER) {
                result.add(new SimpleGrantedAuthority(Permission.CUSTOMER_CREATE.name()));
                result.add(new SimpleGrantedAuthority(Permission.CUSTOMER_READ.name()));
                result.add(new SimpleGrantedAuthority(Permission.CUSTOMER_UPDATE.name()));
            }
            if (role == RoleName.BANK_ADMINISTRATOR) {
                result.add(new SimpleGrantedAuthority(Permission.CUSTOMER_CREATE.name()));
                result.add(new SimpleGrantedAuthority(Permission.CUSTOMER_READ.name()));
                result.add(new SimpleGrantedAuthority(Permission.CUSTOMER_UPDATE.name()));
                result.add(new SimpleGrantedAuthority(Permission.CUSTOMER_DELETE.name()));
            }
        }
        return result;
    }

    public UUID getUserId() {
        return userId;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public boolean isActive() {
        return active;
    }

    @Override
    public String getName() {
        return userId.toString();
    }

    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

}
