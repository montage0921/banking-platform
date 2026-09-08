package com.group1.banking.seed;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.group1.banking.entity.User;
import com.group1.banking.repository.UserRepository;
import com.group1.banking.seed.PersonaCatalogue.Persona;

/**
 * The only way a test may name a persona or read an expected value.
 *
 * <p>One parse path, so a catalogue schema change is absorbed in one place instead of in every
 * feature's tests. Two rules for callers:
 *
 * <ul>
 *   <li><b>Never restate an expected value in a test.</b> Read it from the persona's
 *       {@code expectations} block, so a catalogue change fails loudly rather than leaving two
 *       copies that disagree.</li>
 *   <li><b>Never hardcode an identifier.</b> Customer and account ids are generated and
 *       deliberately not pinned; resolve them from the reserved login identity.</li>
 * </ul>
 */
@Component
public class Personas {

    public static final String SALARIED = "salaried";
    public static final String GOAL_SAVER = "goalSaver";
    public static final String OPERATIONS = "operations";
    public static final String SPARSE = "sparse";

    @Autowired
    private PersonaCatalogue catalogue;

    @Autowired
    private UserRepository userRepository;

    public Persona byKey(String key) {
        return catalogue.byKey(key);
    }

    public PersonaCatalogue catalogue() {
        return catalogue;
    }

    /** Resolves the generated customer id from the persona's login. Never pin this value. */
    public Long customerIdOf(String personaKey) {
        Persona persona = byKey(personaKey);
        User user = userRepository.findByUsernameIgnoreCase(persona.getLogin())
                .orElseThrow(() -> new IllegalStateException(
                        "Persona '" + personaKey + "' (" + persona.getLogin()
                                + ") is not seeded in this environment."));
        return user.getCustomerId();
    }

    public boolean isSeeded(String personaKey) {
        return userRepository.existsByUsernameIgnoreCase(byKey(personaKey).getLogin());
    }
}
