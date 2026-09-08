package com.group1.banking.security;

import com.group1.banking.entity.User;
import com.group1.banking.enums.Permission;
import com.group1.banking.enums.RoleName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CustomUserPrincipalTest {

    @Test
    void preservesAdministratorAuthorities() {
        CustomUserPrincipal principal = principalFor(RoleName.BANK_ADMINISTRATOR);

        assertThat(authorities(principal)).contains(
                "ROLE_BANK_ADMINISTRATOR",
                Permission.CUSTOMER_CREATE.name(),
                Permission.CUSTOMER_READ.name(),
                Permission.CUSTOMER_UPDATE.name(),
                Permission.CUSTOMER_DELETE.name());
    }

    @Test
    void preservesRetailCustomerAuthorities() {
        CustomUserPrincipal principal = principalFor(RoleName.RETAIL_CUSTOMER);

        assertThat(authorities(principal)).contains(
                "ROLE_RETAIL_CUSTOMER",
                Permission.CUSTOMER_CREATE.name(),
                Permission.CUSTOMER_READ.name(),
                Permission.CUSTOMER_UPDATE.name())
                .doesNotContain(Permission.CUSTOMER_DELETE.name());
    }

    @Test
    void newRolesReceiveOnlyTheirRoleAuthority() {
        assertThat(authorities(principalFor(RoleName.RISK_ANALYST)))
                .containsExactly("ROLE_RISK_ANALYST");
        assertThat(authorities(principalFor(RoleName.COMPLIANCE_AUDIT_OBSERVER)))
                .containsExactly("ROLE_COMPLIANCE_AUDIT_OBSERVER");
    }

    private CustomUserPrincipal principalFor(RoleName role) {
        User user = new User();
        user.setUserId(UUID.randomUUID());
        user.setRoles(List.of(role));
        user.setActive(true);
        return new CustomUserPrincipal(user);
    }

    private List<String> authorities(CustomUserPrincipal principal) {
        return principal.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .sorted()
                .toList();
    }
}
