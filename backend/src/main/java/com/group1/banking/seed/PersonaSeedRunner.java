package com.group1.banking.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The only automatic entry point into seeding.
 *
 * <p>Guard (a) is enforced structurally by {@code @ConditionalOnProperty}: with the flag absent
 * or false this bean is never created, so no seeding code exists in the running application at
 * all. Guard (b), the production refusal, is enforced by {@link SeedGuard} and applies even
 * when the flag is true.
 *
 * <p>Deliberately not exposed over HTTP. Adding a seed endpoint would pull in an authorization
 * matcher, a DTO, error-code mapping and a CORS decision - a contract surface this feature does
 * not need. Reset is invoked from tests and, for a QA or demo environment, from an explicit
 * call rather than a request.
 */
@Component
@ConditionalOnProperty(name = "app.seed.personas.enabled", havingValue = "true")
public class PersonaSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PersonaSeedRunner.class);

    private final SeedGuard guard;
    private final PersonaSeeder seeder;

    public PersonaSeedRunner(SeedGuard guard, PersonaSeeder seeder) {
        this.guard = guard;
        this.seeder = seeder;
    }

    @Override
    public void run(ApplicationArguments args) {
        String refusal = guard.refusalReason();
        if (refusal != null) {
            // Loud, and phrased so an operator can tell "deliberately prevented" from "broken".
            // An environment with no personas otherwise reads exactly like a seeding bug.
            log.warn("PERSONA SEEDING REFUSED - {}", refusal);
            return;
        }
        seeder.seedAll();
    }
}
