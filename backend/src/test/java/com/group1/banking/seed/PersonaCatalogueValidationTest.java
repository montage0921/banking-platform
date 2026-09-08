package com.group1.banking.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import com.group1.banking.seed.PersonaCatalogue.Persona;
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
            BigDecimal declared = persona.getExpectations().getGoalProgressPercent();
            List<SavingsGoal> goals = goalsOf(persona);

            if (declared == null) {
                assertThat(goals)
                        .as("persona '%s' declares no goal progress, so it should have no goals",
                                persona.getKey())
                        .isEmpty();
                continue;
            }

            SavingsGoal goal = goals.get(0);
            BigDecimal balance = accountRepository.findById(goal.getAccount().getAccountId())
                    .orElseThrow().getBalance();
            BigDecimal actual = PersonaSeeder.goalProgressPercent(balance, goal.getTargetAmount());

            assertThat(actual)
                    .as("persona '%s' declares %s%% progress; balance %s of target %s gives %s%%",
                            persona.getKey(), declared, balance, goal.getTargetAmount(), actual)
                    .isEqualByComparingTo(declared);
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
            BigDecimal progress = persona.getExpectations().getGoalProgressPercent();
            if (progress == null) {
                continue;
            }
            for (BigDecimal boundary : boundaries) {
                BigDecimal distance = progress.subtract(boundary).abs();
                assertThat(distance)
                        .as("persona '%s' progress %s%% is only %s from the %s%% band boundary; "
                                + "move it toward the middle of its band",
                                persona.getKey(), progress, distance, boundary)
                        .isGreaterThanOrEqualTo(minimumMargin);
            }
        }
    }

    @Test
    @DisplayName("restriction-management capability matches the seeded role")
    void restrictionCapabilityMatchesCatalogue() {
        for (Persona persona : catalogue.getPersonas()) {
            User user = userRepository.findByUsernameIgnoreCase(persona.getLogin()).orElseThrow();
            boolean isAdmin = user.getRoles().contains(RoleName.ADMIN);

            assertThat(isAdmin)
                    .as("persona '%s' declares canManageRestrictions=%s, so its seeded role must agree",
                            persona.getKey(), persona.getExpectations().isCanManageRestrictions())
                    .isEqualTo(persona.getExpectations().isCanManageRestrictions());
        }
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

    /** Kept for readability of the progress assertions above. */
    @SuppressWarnings("unused")
    private BigDecimal percent(BigDecimal part, BigDecimal whole) {
        return part.multiply(BigDecimal.valueOf(100)).divide(whole, 2, RoundingMode.HALF_UP);
    }
}
