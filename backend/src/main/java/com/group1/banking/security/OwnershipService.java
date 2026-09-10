package com.group1.banking.security;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service("ownershipService")
public class OwnershipService {

    public boolean canAccessCustomer(Authentication authentication, Long customerId) {
        if (authentication == null || !(authentication.getPrincipal() instanceof CustomUserPrincipal principal)) {
            return false;
        }
        boolean isAdmin = principal.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_BANK_ADMINISTRATOR"));
        return isAdmin || (principal.getCustomerId() != null && principal.getCustomerId().equals(customerId));
    }
}
