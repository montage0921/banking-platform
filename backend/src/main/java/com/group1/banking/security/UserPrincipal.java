package com.group1.banking.security;

import java.util.Collection;
import java.util.List;

/**
 * JWT-authenticated user principal.
 * userId is a String to hold Group 1's UUID sub claim.
 * roles is a List<String> to hold Group 1's roles array claim.
 */
public class UserPrincipal {

    private final String userId;
    private final String username;
    private final List<String> roles;
    private final Collection<String> permissions;
    private final Long customerId;

    public UserPrincipal(String userId, String username, List<String> roles,
                         Collection<String> permissions, Long customerId) {
        this.userId = userId;
        this.username = username;
        this.roles = roles;
        this.permissions = permissions;
        this.customerId = customerId;
    }

    public String getUserId() { return userId; }
    public String getUsername() { return username; }
    public List<String> getRoles() { return roles; }

    /** Backward-compatible accessor returning the first role as a String. */
    public String getRole() {
        return (roles != null && !roles.isEmpty()) ? roles.get(0) : null;
    }

    public Collection<String> getPermissions() { return permissions; }
    public Long getCustomerId() { return customerId; }

    public boolean hasPermission(String permission) {
        return permissions != null && permissions.contains(permission);
    }

    public boolean isAdmin() { return roles != null && roles.contains("BANK_ADMINISTRATOR"); }
    public boolean isCustomer() { return roles != null && roles.contains("RETAIL_CUSTOMER"); }
}
