package com.group1.banking.seed;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.group1.banking.DigitalBankingPlatformApplication;

/**
 * Shared setup for the seeding tests.
 *
 * <p>Runs against its own in-memory database with {@code create-drop}, following the pattern
 * already established by {@code AuthCustomerIntegrationTest}, so these tests never touch the
 * developer's local {@code ./data/digitalbankdb} file.
 *
 * <p>The enabling flag is left <em>off</em> here and seeding is invoked explicitly in each test.
 * That keeps the default-off guarantee under test rather than quietly bypassed, and lets a test
 * observe the before-and-after of a seed run.
 */
@SpringBootTest(classes = DigitalBankingPlatformApplication.class)
@Import(Personas.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:seedtestdb;DB_CLOSE_DELAY=-1",
        "spring.datasource.driverClassName=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.seed.personas.enabled=false",
        "spring.ai.mcp.client.enabled=false"
})
abstract class SeedTestBase {

    @Autowired
    protected PersonaCatalogue catalogue;

    @Autowired
    protected PersonaSeeder seeder;

    @Autowired
    protected PersonaResetService resetService;

    @Autowired
    protected Personas personas;

    /** Every test starts from a clean, freshly seeded baseline. */
    @BeforeEach
    void seedBaseline() {
        resetService.resetAll();
    }
}
