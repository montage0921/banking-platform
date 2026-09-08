package com.group1.banking.enums;

public enum RoleName {
    BANK_ADMINISTRATOR("Bank Administrator"),
    RISK_ANALYST("Risk Analyst"),
    COMPLIANCE_AUDIT_OBSERVER("Compliance/Audit Observer"),
    RETAIL_CUSTOMER("Retail Customer");

    private final String displayName;

    RoleName(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
