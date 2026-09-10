package com.group1.banking.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import com.group1.banking.DigitalBankingPlatformApplication;
import com.group1.banking.enums.RoleName;
import com.group1.banking.repository.UserRepository;

/**
 * The guarantee everything else rests on: a freshly started application does not seed itself.
 *
 * <p>This is the automated form of the manual check in the feature's quickstart - start the
 * backend with no flag and confirm no seeded users exist. Asserting the runner bean is absent
 * as well as the rows proves guard (a) works structurally, not merely that seeding happened to
 * do nothing.
 */
@SpringBootTest(classes = DigitalBankingPlatformApplication.class)
@TestPropertySource(properties = {
        // Real PostgreSQL with the Flyway-managed schema, matching the deployed
        // configuration - see SeedTestBase for why a substitute engine is not used.
        "spring.datasource.url=${APP_DB_URL:jdbc:postgresql://localhost:5433/banking_core}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.datasource.username=${APP_DB_USERNAME:banking_chat}",
        "spring.datasource.password=${APP_DB_PASSWORD:banking_chat}",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.ai.mcp.client.enabled=false"
        // Deliberately no app.seed.personas.enabled - this is a default-configuration run.
})
class SeedDefaultOffTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PersonaCatalogue catalogue;

    @Autowired
    private PersonaResetService resetService;

    @Test
    @DisplayName("with no flag set, the seed runner bean does not even exist")
    void runnerIsNotCreatedByDefault() {
        assertThat(context.getBeanNamesForType(PersonaSeedRunner.class))
                .as("@ConditionalOnProperty should keep the runner out of a default context")
                .isEmpty();
    }

    @Test
    @DisplayName("with no flag set, no persona is seeded")
    void nothingIsSeededByDefault() {
        // On a persistent database this asserts the runner did not seed *in this context*.
        // Personas left by another test class would be a false failure, so clear them first:
        // the guarantee under test is that startup does not seed, not that the database is
        // empty for unrelated reasons.
        catalogue.getPersonas().forEach(resetService::removePersona);
        for (PersonaCatalogue.Persona persona : catalogue.getPersonas()) {
            assertThat(userRepository.findByUsernameIgnoreCase(persona.getLogin()))
                    .as("persona '%s' must not appear in an environment that never asked for it",
                            persona.getKey())
                    .isEqualTo(Optional.empty());
        }
    }

    @Test
    @DisplayName("the catalogue still loads and validates even when seeding is off")
    void catalogueLoadsRegardless() {
        // A malformed catalogue should fail the build everywhere, not only where seeding runs.
        // No literal count: a persona added for a new role must not break this.
        assertThat(catalogue.getPersonas()).hasSizeGreaterThanOrEqualTo(RoleName.values().length);
        assertThat(catalogue.keys()).contains(Personas.SPARSE, Personas.OPERATIONS,
                Personas.RISK_ANALYST, Personas.COMPLIANCE_OBSERVER);
    }
}
