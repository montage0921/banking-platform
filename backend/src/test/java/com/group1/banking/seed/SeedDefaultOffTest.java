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
        "spring.datasource.url=jdbc:h2:mem:defaultoffdb;DB_CLOSE_DELAY=-1",
        "spring.datasource.driverClassName=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
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
        assertThat(catalogue.getPersonas()).hasSize(4);
        assertThat(catalogue.keys()).contains(Personas.SPARSE, Personas.OPERATIONS);
    }
}
