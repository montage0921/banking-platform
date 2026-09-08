package com.group1.banking.seed;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Decides whether seeding may run, using two independent guards. Either one alone is
 * sufficient to prevent seeding, so a single misconfiguration cannot put fictional customers
 * into a real banking dataset.
 *
 * <ol>
 *   <li><b>Opt-in flag</b> - {@code app.seed.personas.enabled}, default {@code false} in every
 *       environment including a fresh developer machine.</li>
 *   <li><b>Production refusal</b> - refuses when an active Spring profile is {@code prod} or
 *       {@code production}, or when {@code app.seed.personas.environment=production}, even if
 *       the flag above is true.</li>
 * </ol>
 *
 * <p>The flag carries the safe default rather than the profile check, deliberately: this
 * codebase uses no {@code @Profile} annotations anywhere, so no profile is set in any
 * environment. A profile-only guard would look like protection and provide none - it would
 * evaluate the same way in production as on a laptop. The profile check is the backstop for a
 * deployment where someone set the flag by mistake, not the primary defence.
 *
 * <p>A refusal is reported distinctly from a failure. An operator looking at an environment
 * with no personas must be able to tell "deliberately prevented" from "broken".
 */
@Component
public class SeedGuard {

    private static final List<String> PRODUCTION_PROFILES = List.of("prod", "production");
    private static final String PRODUCTION_LABEL = "production";

    private final Environment environment;
    private final boolean enabled;
    private final String environmentLabel;

    public SeedGuard(Environment environment,
                     @Value("${app.seed.personas.enabled:false}") boolean enabled,
                     @Value("${app.seed.personas.environment:}") String environmentLabel) {
        this.environment = environment;
        this.enabled = enabled;
        this.environmentLabel = environmentLabel;
    }

    /**
     * @return the reason seeding must not run, or {@code null} when both guards allow it.
     */
    public String refusalReason() {
        if (!enabled) {
            return "seeding is disabled (app.seed.personas.enabled is false or unset). "
                    + "This is the default in every environment; set it to true to seed a "
                    + "local, CI, QA or demo environment.";
        }

        String activeProductionProfile = Arrays.stream(environment.getActiveProfiles())
                .filter(p -> PRODUCTION_PROFILES.contains(p.toLowerCase(Locale.ROOT)))
                .findFirst()
                .orElse(null);
        if (activeProductionProfile != null) {
            return "refusing to seed: the active Spring profile '" + activeProductionProfile
                    + "' identifies this as a production environment. This refusal stands even "
                    + "though app.seed.personas.enabled is true.";
        }

        if (PRODUCTION_LABEL.equalsIgnoreCase(environmentLabel.trim())) {
            return "refusing to seed: app.seed.personas.environment is '" + environmentLabel
                    + "'. This refusal stands even though app.seed.personas.enabled is true.";
        }

        return null;
    }

    public boolean isAllowed() {
        return refusalReason() == null;
    }

    /**
     * True when seeding was blocked by the production guard specifically, rather than by the
     * flag simply being off. Callers use this to log a prominent warning: a production-shaped
     * environment that was asked to seed is worth surfacing, whereas the default-off case is
     * routine and needs no noise.
     */
    public boolean isBlockedByProductionGuard() {
        return enabled && !isAllowed();
    }
}
