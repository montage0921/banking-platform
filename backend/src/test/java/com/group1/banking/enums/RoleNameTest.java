package com.group1.banking.enums;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;

class RoleNameTest {

    @Test
    void definesExactlyTheFourCanonicalRoles() {
        assertThat(RoleName.values()).containsExactly(
                RoleName.BANK_ADMINISTRATOR,
                RoleName.RISK_ANALYST,
                RoleName.COMPLIANCE_AUDIT_OBSERVER,
                RoleName.RETAIL_CUSTOMER);
        assertThat(Arrays.stream(RoleName.values()).map(Enum::name).distinct().count())
                .isEqualTo(4);
    }

    @Test
    void assignsTheRequiredDisplayNames() {
        assertThat(RoleName.BANK_ADMINISTRATOR.getDisplayName()).isEqualTo("Bank Administrator");
        assertThat(RoleName.RISK_ANALYST.getDisplayName()).isEqualTo("Risk Analyst");
        assertThat(RoleName.COMPLIANCE_AUDIT_OBSERVER.getDisplayName()).isEqualTo("Compliance/Audit Observer");
        assertThat(RoleName.RETAIL_CUSTOMER.getDisplayName()).isEqualTo("Retail Customer");
    }

    @Test
    void hasNoLegacyOrProductOwnerRoles() {
        assertThat(new HashSet<>(Arrays.stream(RoleName.values()).map(Enum::name).toList()))
                .doesNotContain("ADMIN", "CUSTOMER", "PRODUCT_OWNER");
    }
}
