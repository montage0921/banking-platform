package com.group1.banking.seed;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * The two guards, proven independently.
 *
 * <p>The production cases are the ones that matter: they assert a *refusal* with a reason, not
 * merely that nothing was seeded. An environment with no personas and no explanation is
 * indistinguishable from a broken seeder.
 */
class SeedGuardTest {

    private SeedGuard guard(boolean enabled, String environmentLabel, String... activeProfiles) {
        MockEnvironment environment = new MockEnvironment();
        if (activeProfiles.length > 0) {
            environment.setActiveProfiles(activeProfiles);
        }
        return new SeedGuard(environment, enabled, environmentLabel);
    }

    @Test
    @DisplayName("flag off (the default) blocks seeding")
    void flagOffBlocks() {
        SeedGuard guard = guard(false, "");

        assertThat(guard.isAllowed()).isFalse();
        assertThat(guard.refusalReason()).contains("app.seed.personas.enabled");
        // Routine, not alarming - this is the default state on every machine.
        assertThat(guard.isBlockedByProductionGuard()).isFalse();
    }

    @Test
    @DisplayName("flag on with no production signal allows seeding")
    void flagOnAllows() {
        SeedGuard guard = guard(true, "");

        assertThat(guard.isAllowed()).isTrue();
        assertThat(guard.refusalReason()).isNull();
    }

    @Test
    @DisplayName("flag on but 'prod' profile active refuses, and says so")
    void prodProfileRefuses() {
        SeedGuard guard = guard(true, "", "prod");

        assertThat(guard.isAllowed()).isFalse();
        assertThat(guard.refusalReason())
                .contains("refusing to seed")
                .contains("prod")
                .contains("even though app.seed.personas.enabled is true");
        assertThat(guard.isBlockedByProductionGuard()).isTrue();
    }

    @Test
    @DisplayName("'production' profile refuses too, and matching is case-insensitive")
    void productionProfileRefuses() {
        assertThat(guard(true, "", "production").isAllowed()).isFalse();
        assertThat(guard(true, "", "PROD").isAllowed()).isFalse();
        assertThat(guard(true, "", "eu-west", "Production").isAllowed()).isFalse();
    }

    @Test
    @DisplayName("environment label 'production' refuses even with no profile set")
    void productionLabelRefuses() {
        SeedGuard guard = guard(true, "production");

        assertThat(guard.isAllowed()).isFalse();
        assertThat(guard.refusalReason()).contains("app.seed.personas.environment");
        assertThat(guard.isBlockedByProductionGuard()).isTrue();
    }

    @Test
    @DisplayName("the two guards are independent - neither depends on the other")
    void guardsAreIndependent() {
        // Guard (a) alone blocks, even in an environment guard (b) would happily allow.
        assertThat(guard(false, "").isAllowed()).isFalse();
        // Guard (b) alone blocks, even though guard (a) was explicitly opened.
        assertThat(guard(true, "production").isAllowed()).isFalse();
        assertThat(guard(true, "", "prod").isAllowed()).isFalse();
        // Only with both satisfied does seeding proceed.
        assertThat(guard(true, "qa", "qa").isAllowed()).isTrue();
    }

    @Test
    @DisplayName("a non-production environment label does not block")
    void nonProductionLabelAllows() {
        assertThat(guard(true, "qa").isAllowed()).isTrue();
        assertThat(guard(true, "demo").isAllowed()).isTrue();
        assertThat(guard(true, "  ").isAllowed()).isTrue();
    }
}
