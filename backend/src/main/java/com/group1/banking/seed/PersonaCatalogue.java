package com.group1.banking.seed;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.group1.banking.entity.AccountStatus;
import com.group1.banking.entity.AccountType;
import com.group1.banking.entity.TransactionDirection;
import com.group1.banking.entity.TransactionStatus;
import com.group1.banking.enums.RiskScoreLevel;
import com.group1.banking.enums.RiskScoreStatus;
import com.group1.banking.enums.RoleName;
import com.group1.banking.enums.SavingsGoalStatus;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The persona catalogue: the single source of truth for the seeded baseline dataset.
 *
 * <p>Bound from {@code classpath:personas/voltio-personas.yaml}, which is hand-edited and
 * never generated. The same file serves two audiences from one copy - a person reads it to
 * pick a persona, and the tests read their expected values from it - so there is no second,
 * derived copy that could drift.
 *
 * <p>Binding follows the same idiom as {@code RiskScoreRules}: a distinctive root key, an
 * entry in {@code spring.config.import}, and Lombok-backed nested types. One YAML-config
 * idiom in this codebase, not two.
 *
 * <p>Dates are deliberately absent. Every temporal field is a relative offset resolved by
 * {@link PersonaDateAnchor}, because risk scoring and the chatbot both compute their windows
 * from the current moment on every call - fixed calendar dates would drift out of those
 * windows and silently reclassify a persona.
 */
@Configuration
@ConfigurationProperties(prefix = "persona-catalogue")
@Data
@NoArgsConstructor
public class PersonaCatalogue {

    /** Convention every reserved login must follow, so a seeded account is obvious on sight. */
    public static final String LOGIN_PREFIX = "seed.";
    public static final String LOGIN_DOMAIN = "@voltio.test";

    private List<Persona> personas = new ArrayList<>();

    @Data
    public static class Persona {
        /** Stable symbolic name. The unit of reset, and how tests name a persona. */
        private String key;
        /** Reserved login identity - the natural business key personas are resolved by. */
        private String login;
        private String displayName;
        private RoleName role;
        /** Intended target role once the role-definition story lands. Recorded, not enforced. */
        private String provisionalRole;
        /** Why this persona exists. Read by people; never asserted on. */
        private String purpose;
        private List<String> scenarios = new ArrayList<>();
        private List<SeedAccount> accounts = new ArrayList<>();
        private List<SeedGoal> goals = new ArrayList<>();
        private List<SeedTransaction> transactions = new ArrayList<>();
        private Expectations expectations;
    }

    @Data
    public static class SeedAccount {
        /** Local handle so goals and transactions can point here without a generated id. */
        private String ref;
        private AccountType type;
        private AccountStatus status;
        private BigDecimal balance;
        private BigDecimal dailyTransferLimit;
    }

    @Data
    public static class SeedGoal {
        private String accountRef;
        private String name;
        private BigDecimal targetAmount;
        /** Relative offset, resolved against the run anchor. */
        private int targetDaysAhead;
        private SavingsGoalStatus status;
    }

    @Data
    public static class SeedTransaction {
        private String accountRef;
        private BigDecimal amount;
        private TransactionDirection direction;
        private TransactionStatus status;
        /** Relative offset, resolved against the run anchor. */
        private int daysAgo;
        /** Expands one entry into a monthly series. Default 1 = no repetition. */
        private int repeatMonthly = 1;
        private String category;
        private String description;
    }

    /** What the four features must report for this persona. Asserted against by their tests. */
    @Data
    public static class Expectations {
        private RiskScoreStatus riskStatus;
        /** Null exactly when riskStatus is INSUFFICIENT_DATA. */
        private RiskScoreLevel riskLevel;
        private boolean chatbotSufficientData;
        private BigDecimal goalProgressPercent;
        private boolean canManageRestrictions;
    }

    public Persona byKey(String key) {
        return personas.stream()
                .filter(p -> p.getKey().equals(key))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No persona with key '" + key + "'. Known keys: " + keys()));
    }

    public List<String> keys() {
        return personas.stream().map(Persona::getKey).toList();
    }

    /**
     * Invariants C1-C10 from the catalogue contract, checked before any row is written so a
     * malformed catalogue fails startup rather than producing a half-built environment.
     *
     * <p>C6 and C7 are structural: they make deleting the sparse or operations persona a
     * startup failure rather than a silent loss of edge-case coverage.
     */
    @PostConstruct
    public void validate() {
        if (personas.isEmpty()) {
            throw new IllegalStateException("Persona catalogue is empty - expected at least one persona.");
        }

        Set<String> seenKeys = new HashSet<>();
        Set<String> seenLogins = new HashSet<>();

        for (Persona p : personas) {
            String id = "persona '" + p.getKey() + "'";

            // C1: unique key - reset targets a key, so ambiguity would reset the wrong data.
            if (p.getKey() == null || p.getKey().isBlank()) {
                throw new IllegalStateException("A persona has no key.");
            }
            if (!seenKeys.add(p.getKey())) {
                throw new IllegalStateException("C1 violated: duplicate persona key '" + p.getKey() + "'.");
            }

            // C2: unique login - it is the natural key, so duplicates break seeded-state detection.
            if (!seenLogins.add(p.getLogin())) {
                throw new IllegalStateException("C2 violated: duplicate login '" + p.getLogin() + "'.");
            }

            // C3: login follows the reserved convention, so seeded rows are recognizable on sight.
            if (p.getLogin() == null
                    || !p.getLogin().startsWith(LOGIN_PREFIX)
                    || !p.getLogin().endsWith(LOGIN_DOMAIN)) {
                throw new IllegalStateException("C3 violated: " + id + " login '" + p.getLogin()
                        + "' must start with '" + LOGIN_PREFIX + "' and end with '" + LOGIN_DOMAIN + "'.");
            }

            if (p.getAccounts().isEmpty()) {
                throw new IllegalStateException(id + " has no accounts - every persona needs at least one.");
            }

            Set<String> refs = new HashSet<>();
            for (SeedAccount a : p.getAccounts()) {
                if (!refs.add(a.getRef())) {
                    throw new IllegalStateException("C4 violated: " + id + " has duplicate account ref '"
                            + a.getRef() + "'.");
                }
                requireScale2(a.getBalance(), id + " account '" + a.getRef() + "' balance");
                requireScale2(a.getDailyTransferLimit(), id + " account '" + a.getRef() + "' dailyTransferLimit");
            }

            // C4: every reference resolves inside this persona. No cross-persona refs, because a
            // persona is the unit of reset and shared data would make one reset disturb another.
            Set<String> goalAccountRefs = new HashSet<>();
            for (SeedGoal g : p.getGoals()) {
                if (!refs.contains(g.getAccountRef())) {
                    throw new IllegalStateException("C4 violated: " + id + " goal '" + g.getName()
                            + "' references unknown account ref '" + g.getAccountRef() + "'.");
                }
                // C5: the uq_sg_customer_account constraint allows one goal per account.
                if (!goalAccountRefs.add(g.getAccountRef())) {
                    throw new IllegalStateException("C5 violated: " + id + " has more than one goal on account ref '"
                            + g.getAccountRef() + "'; the database allows one goal per account.");
                }
                requireScale2(g.getTargetAmount(), id + " goal '" + g.getName() + "' targetAmount");
            }

            for (SeedTransaction t : p.getTransactions()) {
                if (!refs.contains(t.getAccountRef())) {
                    throw new IllegalStateException("C4 violated: " + id + " transaction '" + t.getDescription()
                            + "' references unknown account ref '" + t.getAccountRef() + "'.");
                }
                requireScale2(t.getAmount(), id + " transaction '" + t.getDescription() + "' amount");
                if (t.getRepeatMonthly() < 1) {
                    throw new IllegalStateException(id + " transaction '" + t.getDescription()
                            + "' has repeatMonthly " + t.getRepeatMonthly() + "; must be at least 1.");
                }
            }

            Expectations e = p.getExpectations();
            if (e == null) {
                throw new IllegalStateException(id + " has no expectations block - nothing for tests to assert.");
            }

            // C8: riskLevel is null exactly when the status is INSUFFICIENT_DATA, matching
            // RiskScoreResponse, which omits level entirely when data is insufficient.
            boolean insufficient = e.getRiskStatus() == RiskScoreStatus.INSUFFICIENT_DATA;
            if (insufficient && e.getRiskLevel() != null) {
                throw new IllegalStateException("C8 violated: " + id
                        + " expects INSUFFICIENT_DATA but also names a riskLevel.");
            }
            if (!insufficient && e.getRiskLevel() == null) {
                throw new IllegalStateException("C8 violated: " + id
                        + " expects a calculated score but names no riskLevel.");
            }

            // C9: a goal expectation must have a goal behind it, or nothing produces the value.
            boolean hasGoals = !p.getGoals().isEmpty();
            if (hasGoals && e.getGoalProgressPercent() == null) {
                throw new IllegalStateException("C9 violated: " + id
                        + " has goals but no expected goalProgressPercent.");
            }
            if (!hasGoals && e.getGoalProgressPercent() != null) {
                throw new IllegalStateException("C9 violated: " + id
                        + " expects goal progress but has no goals.");
            }
        }

        // C6: at least one persona exercises both insufficient-data paths. Deleting the sparse
        // persona must fail the build, not quietly drop the coverage it exists to provide.
        boolean hasSparse = personas.stream().anyMatch(p ->
                p.getExpectations().getRiskStatus() == RiskScoreStatus.INSUFFICIENT_DATA
                        && !p.getExpectations().isChatbotSufficientData());
        if (!hasSparse) {
            throw new IllegalStateException("C6 violated: no persona expects both INSUFFICIENT_DATA risk status "
                    + "and chatbotSufficientData=false. At least one persona must exercise both fallback paths.");
        }

        // C7: the operations user must exist, or account-restriction scenarios have no actor.
        boolean hasOperations = personas.stream()
                .anyMatch(p -> p.getExpectations().isCanManageRestrictions());
        if (!hasOperations) {
            throw new IllegalStateException("C7 violated: no persona can manage account restrictions.");
        }
    }

    /** C10: columns are precision 19, scale 2 - more places would round silently on write. */
    private void requireScale2(BigDecimal value, String what) {
        if (value == null) {
            throw new IllegalStateException(what + " is missing.");
        }
        if (value.scale() > 2) {
            throw new IllegalStateException("C10 violated: " + what + " has " + value.scale()
                    + " decimal places; columns hold 2.");
        }
    }
}
