package com.group1.banking.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import com.group1.banking.config.RiskScoreRules;
import com.group1.banking.dto.chat.SpendCategorySummary;
import com.group1.banking.entity.Account;
import com.group1.banking.entity.SavingsGoal;
import com.group1.banking.entity.Transaction;
import com.group1.banking.entity.User;
import com.group1.banking.enums.RiskScoreStatus;
import com.group1.banking.enums.RoleName;
import com.group1.banking.repository.AccountRepository;
import com.group1.banking.repository.SavingsGoalRepository;
import com.group1.banking.repository.TransactionRepository;
import com.group1.banking.repository.UserRepository;
import com.group1.banking.security.CustomUserPrincipal;
import com.group1.banking.seed.PersonaCatalogue.Persona;
import com.group1.banking.seed.PersonaCatalogue.SeedGoal;
import com.group1.banking.service.impl.SavingsChatContextService;

/**
 * The catalogue is the single source of truth, and this is what makes that true rather than
 * aspirational: seeded data that no longer matches its catalogue entry fails here.
 *
 * <p>This runs in the default {@code mvn test} invocation, which is the achievable form of
 * "validation runs wherever the four features' tests run". The four features use
 * {@code @WebMvcTest} and {@code @DataJpaTest} slices that never load the seeder, so validation
 * cannot literally be embedded in them. Running in the same invocation gives the property that
 * actually matters: a persona change that invalidates any feature's expectation fails the build
 * at the moment it is made, rather than being announced by a version number nobody reads.
 */
class PersonaCatalogueValidationTest extends SeedTestBase {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private SavingsGoalRepository savingsGoalRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SavingsChatContextService chatContextService;

    @Autowired
    private RiskScoreRules riskScoreRules;

    @Value("${app.chatbot.transactions.lookback-days:30}")
    private int lookbackDays;

    /**
     * The authority the application actually checks before allowing an account restriction -
     * see {@code OwnershipService} and {@code AccountService}. Named here once so the coupling
     * to authorization is explicit rather than scattered through assertions.
     */
    private static final String RESTRICTION_AUTHORITY = "ROLE_" + RoleName.BANK_ADMINISTRATOR.name();

    @Test
    @DisplayName("every persona in the catalogue is actually seeded")
    void everyPersonaIsSeeded() {
        for (Persona persona : catalogue.getPersonas()) {
            assertThat(personas.isSeeded(persona.getKey()))
                    .as("persona '%s' (%s) should be present", persona.getKey(), persona.getLogin())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("chatbot sufficiency matches every persona's declared expectation")
    void chatbotSufficiencyMatchesCatalogue() {
        for (Persona persona : catalogue.getPersonas()) {
            SpendCategorySummary summary = chatContextService
                    .getSpendByCategory(personas.customerIdOf(persona.getKey()), lookbackDays);

            assertThat(summary.sufficientData())
                    .as("persona '%s' declares chatbotSufficientData=%s",
                            persona.getKey(), persona.getExpectations().isChatbotSufficientData())
                    .isEqualTo(persona.getExpectations().isChatbotSufficientData());
        }
    }

    @Test
    @DisplayName("history length matches each persona's declared risk status")
    void riskDataSufficiencyMatchesCatalogue() {
        long cutoffDays = riskScoreRules.getInsufficientConditions().getMinMonths() * 30L;

        for (Persona persona : catalogue.getPersonas()) {
            Instant earliest = earliestTransaction(persona);
            boolean expectedInsufficient =
                    persona.getExpectations().getRiskStatus() == RiskScoreStatus.INSUFFICIENT_DATA;

            if (earliest == null) {
                assertThat(expectedInsufficient)
                        .as("persona '%s' has no transactions, so it cannot expect a score",
                                persona.getKey())
                        .isTrue();
                continue;
            }

            long historyDays = ChronoUnit.DAYS.between(earliest, Instant.now());
            if (expectedInsufficient) {
                assertThat(historyDays)
                        .as("persona '%s' expects INSUFFICIENT_DATA, so its history (%d days) "
                                + "must fall short of the cutoff (%d days)",
                                persona.getKey(), historyDays, cutoffDays)
                        .isLessThan(cutoffDays);
            } else {
                assertThat(historyDays)
                        .as("persona '%s' expects a score, so its history (%d days) must exceed "
                                + "the cutoff (%d days)", persona.getKey(), historyDays, cutoffDays)
                        .isGreaterThan(cutoffDays);
            }
        }
    }

    @Test
    @DisplayName("goal progress recomputes to the declared percentage")
    void goalProgressMatchesCatalogue() {
        for (Persona persona : catalogue.getPersonas()) {
            List<SavingsGoal> seeded = goalsOf(persona);

            assertThat(seeded)
                    .as("persona '%s' should have one seeded goal per catalogue goal", persona.getKey())
                    .hasSize(persona.getGoals().size());

            // Matched by name, not by list position - a persona may hold several goals and
            // database ordering is not a stable key.
            for (SeedGoal declared : persona.getGoals()) {
                SavingsGoal goal = seeded.stream()
                        .filter(g -> g.getGoalName().equals(declared.getName()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("persona '" + persona.getKey()
                                + "' has no seeded goal named '" + declared.getName() + "'"));

                BigDecimal balance = accountRepository.findById(goal.getAccount().getAccountId())
                        .orElseThrow().getBalance();
                BigDecimal actual = PersonaSeeder.goalProgressPercent(balance, goal.getTargetAmount());

                assertThat(actual)
                        .as("persona '%s' goal '%s' declares %s%% progress; balance %s of target "
                                + "%s gives %s%%", persona.getKey(), declared.getName(),
                                declared.getExpectedProgressPercent(), balance,
                                goal.getTargetAmount(), actual)
                        .isEqualByComparingTo(declared.getExpectedProgressPercent());
            }
        }
    }

    @Test
    @DisplayName("a goal with a past target date is seeded as genuinely overdue")
    void overdueGoalIsActuallyOverdue() {
        // The overdue path needs a goal that is already late on every run, not one that
        // becomes late if a test happens to run slowly.
        for (Persona persona : catalogue.getPersonas()) {
            for (SeedGoal declared : persona.getGoals()) {
                if (declared.getTargetDaysAhead() >= 0) {
                    continue;
                }
                SavingsGoal goal = goalsOf(persona).stream()
                        .filter(g -> g.getGoalName().equals(declared.getName()))
                        .findFirst().orElseThrow();

                assertThat(goal.getTargetDate())
                        .as("persona '%s' goal '%s' declares targetDaysAhead=%d, so its seeded "
                                + "target date must already have passed", persona.getKey(),
                                declared.getName(), declared.getTargetDaysAhead())
                        .isBefore(java.time.LocalDate.now(java.time.ZoneOffset.UTC));
            }
        }
    }

    @Test
    @DisplayName("goal progress sits clear of the GOAL_PROGRESS band boundaries")
    void goalProgressAvoidsBandBoundaries() {
        // Bands from risk-score-rules.yaml. A persona sitting on one of these makes its expected
        // outcome ambiguous and turns a rules tweak into a mystery failure.
        List<BigDecimal> boundaries = List.of(
                new BigDecimal("10"), new BigDecimal("30"), new BigDecimal("60"),
                new BigDecimal("90"), new BigDecimal("99.9"), new BigDecimal("100"));
        BigDecimal minimumMargin = new BigDecimal("5");

        for (Persona persona : catalogue.getPersonas()) {
            for (SeedGoal declared : persona.getGoals()) {
                BigDecimal progress = declared.getExpectedProgressPercent();
                for (BigDecimal boundary : boundaries) {
                    BigDecimal distance = progress.subtract(boundary).abs();
                    assertThat(distance)
                            .as("persona '%s' goal '%s' progress %s%% is only %s from the %s%% "
                                    + "band boundary; move it toward the middle of its band",
                                    persona.getKey(), declared.getName(), progress, distance, boundary)
                            .isGreaterThanOrEqualTo(minimumMargin);
                }
            }
        }
    }

    @Test
    @DisplayName("restriction-management capability matches what authorization actually grants")
    void restrictionCapabilityMatchesCatalogue() {
        // Derived, not hardcoded. Building a real CustomUserPrincipal routes through the same
        // role-to-authority mapping the application uses, so if restriction powers are granted
        // to another role or taken away from Bank Administrator, this fails and names the
        // persona - instead of silently passing because a role constant still matches.
        for (Persona persona : catalogue.getPersonas()) {
            User user = userRepository.findByUsernameIgnoreCase(persona.getLogin()).orElseThrow();
            boolean granted = new CustomUserPrincipal(user).getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals(RESTRICTION_AUTHORITY));

            assertThat(granted)
                    .as("persona '%s' declares canManageRestrictions=%s; authorization grants "
                            + "restriction management via the '%s' authority",
                            persona.getKey(), persona.getExpectations().isCanManageRestrictions(),
                            RESTRICTION_AUTHORITY)
                    .isEqualTo(persona.getExpectations().isCanManageRestrictions());
        }
    }

    @Test
    @DisplayName("at least one persona can manage restrictions, and not every persona can")
    void restrictionCapabilityIsDiscriminating() {
        long canManage = catalogue.getPersonas().stream()
                .filter(p -> p.getExpectations().isCanManageRestrictions())
                .count();

        assertThat(canManage)
                .as("restriction management needs an actor (FR-006)")
                .isGreaterThanOrEqualTo(1);
        assertThat(canManage)
                .as("if every persona could manage restrictions the check would prove nothing - "
                        + "the non-administrator staff roles exist partly to hold this line")
                .isLessThan(catalogue.getPersonas().size());
    }

    @Test
    @DisplayName("seeded rows satisfy the invariants the service layer would have enforced")
    void seededRowsSatisfyServiceInvariants() {
        // The seeder writes through repositories, bypassing AccountService. These assertions are
        // what stop that shortcut from producing rows the services would never have created.
        for (Account account : accountRepository.findAll()) {
            assertThat(account.getAccountNumber())
                    .as("account %d should carry a service-shaped account number", account.getAccountId())
                    .isEqualTo(String.format("ACC%010d", account.getAccountId()));
            assertThat(account.getBalance())
                    .as("account %d balance should not be negative", account.getAccountId())
                    .isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(account.getBalance().scale()).isLessThanOrEqualTo(2);
            assertThat(account.getDailyTransferLimit()).isNotNull();
        }
    }

    @Test
    @DisplayName("no duplicate account identifiers exist after seeding")
    void noDuplicateAccountIdentifiers() {
        List<Long> ids = accountRepository.findAll().stream().map(Account::getAccountId).toList();
        List<String> numbers = accountRepository.findAll().stream()
                .map(Account::getAccountNumber).toList();

        assertThat(ids).doesNotHaveDuplicates();
        assertThat(numbers).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("no seeded transaction fell back to the @PrePersist 'now' default")
    void transactionTimestampsHonourTheAnchor() {
        // If setTimestamp were ever dropped, @PrePersist would stamp every row with the seeding
        // moment - flattening all history to today and silently breaking every persona at once.
        Instant oneHourAgo = Instant.now().minus(1, ChronoUnit.HOURS);

        for (Persona persona : catalogue.getPersonas()) {
            List<Instant> timestamps = transactionsOf(persona).stream()
                    .map(Transaction::getTimestamp).toList();
            if (timestamps.isEmpty()) {
                continue;
            }
            assertThat(timestamps)
                    .as("persona '%s' should have historical transactions, not rows stamped 'now'",
                            persona.getKey())
                    .anyMatch(t -> t.isBefore(oneHourAgo));
        }
    }

    private Instant earliestTransaction(Persona persona) {
        return transactionsOf(persona).stream()
                .map(Transaction::getTimestamp)
                .min(Instant::compareTo)
                .orElse(null);
    }

    private List<Transaction> transactionsOf(Persona persona) {
        return accountsOf(persona).stream()
                .flatMap(a -> transactionRepository.findAllByAccountAccountId(a.getAccountId()).stream())
                .toList();
    }

    private List<SavingsGoal> goalsOf(Persona persona) {
        return accountsOf(persona).stream()
                .flatMap(a -> savingsGoalRepository.findAllByAccountAccountId(a.getAccountId()).stream())
                .toList();
    }

    private List<Account> accountsOf(Persona persona) {
        return accountRepository.findAllByCustomerCustomerId(personas.customerIdOf(persona.getKey()));
    }
}
