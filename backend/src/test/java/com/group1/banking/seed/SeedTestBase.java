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
 * <p>Runs against a real PostgreSQL instance carrying the Flyway-managed schema, because a
 * reproducibility guarantee is only worth the engine it was proven on. Requires a database to
 * be reachable - see the pgvector service in {@code docker-compose.yml}.
 *
 * <p>The enabling flag is left <em>off</em> here and seeding is invoked explicitly in each test.
 * That keeps the default-off guarantee under test rather than quietly bypassed, and lets a test
 * observe the before-and-after of a seed run.
 */
@SpringBootTest(classes = DigitalBankingPlatformApplication.class)
@Import(Personas.class)
@TestPropertySource(properties = {
        // Real PostgreSQL with the Flyway-managed V001 schema - the same engine and schema
        // the deployed application uses. An in-memory substitute would let the seeder pass
        // here and fail on first contact with the real schema; identifier generation already
        // broke once in exactly that way, and it is engine-specific behaviour.
        //
        // The consequence, accepted deliberately: no database means no seed tests, including
        // in CI. Bring one up with the pgvector service in docker-compose.yml.
        "spring.datasource.url=${APP_DB_URL:jdbc:postgresql://localhost:5433/banking_core}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.datasource.username=${APP_DB_USERNAME:banking_chat}",
        "spring.datasource.password=${APP_DB_PASSWORD:banking_chat}",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
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

    /**
     * Every test starts from a clean, freshly seeded baseline.
     *
     * <p>This matters more than it did on H2. With {@code create-drop} the schema was rebuilt
     * per run, so leftover state was impossible; against a persistent PostgreSQL it is the
     * norm. {@code resetAll} deletes and recreates every persona, so a previous run's
     * mutations cannot make a test pass or fail for the wrong reason - which
     * {@code PersonaResetScopeTest} is especially exposed to, since it asserts on mutations
     * it makes itself.
     */
    @BeforeEach
    void seedBaseline() {
        resetService.resetAll();
    }
}
