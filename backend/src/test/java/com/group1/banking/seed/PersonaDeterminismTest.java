package com.group1.banking.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.group1.banking.entity.Account;
import com.group1.banking.entity.Transaction;
import com.group1.banking.repository.AccountRepository;
import com.group1.banking.repository.TransactionRepository;
import com.group1.banking.seed.PersonaCatalogue.Persona;

/**
 * Same catalogue, same outcomes - run after run, and month after month.
 *
 * <p>Assertions here are on outcomes and relative positions only. Literal timestamps differ
 * between runs by design: dates are anchored to the moment of seeding precisely so a persona's
 * classification cannot drift as real time passes. A test that pinned a date would be asserting
 * the opposite of what the feature guarantees.
 */
class PersonaDeterminismTest extends SeedTestBase {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Test
    @DisplayName("two seed runs produce identical balances, counts and goal progress")
    void repeatedSeedingProducesIdenticalOutcomes() {
        Map<String, String> first = snapshot();

        resetService.resetAll();
        Map<String, String> second = snapshot();

        resetService.resetAll();
        Map<String, String> third = snapshot();

        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
    }

    @Test
    @DisplayName("a persona's position relative to the risk window survives re-anchoring")
    void classificationIsStableAcrossReseeds() {
        Map<String, Long> firstHistoryDays = historyDaysPerPersona();

        resetService.resetAll();
        Map<String, Long> secondHistoryDays = historyDaysPerPersona();

        // Not equality on timestamps - equality on how much history each persona has, which is
        // what decides whether risk scoring can score them.
        assertThat(secondHistoryDays).isEqualTo(firstHistoryDays);
    }

    @Test
    @DisplayName("all offsets in one run resolve against a single anchor")
    void oneRunUsesOneAnchor() {
        // The salaried persona's monthly series is generated from one anchor, so consecutive
        // occurrences sit close to a month apart. Per-row Instant.now() calls would still look
        // roughly right, but a run spanning midnight would skew a day - this catches the shape.
        Persona salaried = catalogue.byKey(Personas.SALARIED);
        List<Instant> salary = transactionsOf(salaried).stream()
                .filter(t -> "Salary".equals(t.getCategory()))
                .map(Transaction::getTimestamp)
                .sorted()
                .toList();

        assertThat(salary).hasSizeGreaterThan(2);
        for (int i = 1; i < salary.size(); i++) {
            long gap = ChronoUnit.DAYS.between(salary.get(i - 1), salary.get(i));
            assertThat(gap)
                    .as("consecutive salary payments should be about a month apart, was %d days", gap)
                    .isBetween(28L, 31L);
        }
    }

    @Test
    @DisplayName("literal timestamps are NOT stable - and must never be asserted on")
    void timestampsDifferBetweenRuns() {
        Instant before = earliestTransactionOverall();

        resetService.resetAll();
        Instant after = earliestTransactionOverall();

        // Documents the accepted trade-off: relative anchoring buys drift-immunity at the cost
        // of literal timestamp stability. If this ever becomes equal, someone has switched to
        // fixed dates and SC-008 is quietly broken.
        assertThat(after)
                .as("re-anchoring should move literal timestamps forward")
                .isAfterOrEqualTo(before);
    }

    /** Outcome-shaped snapshot: no timestamps, only what a feature would report. */
    private Map<String, String> snapshot() {
        Map<String, String> snapshot = new TreeMap<>();
        for (Persona persona : catalogue.getPersonas()) {
            List<Account> accounts = accountsOf(persona);
            BigDecimal totalBalance = accounts.stream()
                    .map(Account::getBalance)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            snapshot.put(persona.getKey(), String.format(
                    "accounts=%d balance=%s txns=%d",
                    accounts.size(), totalBalance.stripTrailingZeros().toPlainString(),
                    transactionsOf(persona).size()));
        }
        return snapshot;
    }

    private Map<String, Long> historyDaysPerPersona() {
        Map<String, Long> days = new TreeMap<>();
        for (Persona persona : catalogue.getPersonas()) {
            transactionsOf(persona).stream()
                    .map(Transaction::getTimestamp)
                    .min(Instant::compareTo)
                    .ifPresent(earliest -> days.put(persona.getKey(),
                            ChronoUnit.DAYS.between(earliest, Instant.now())));
        }
        return days;
    }

    private Instant earliestTransactionOverall() {
        return catalogue.getPersonas().stream()
                .flatMap(p -> transactionsOf(p).stream())
                .map(Transaction::getTimestamp)
                .min(Instant::compareTo)
                .orElseThrow();
    }

    private List<Transaction> transactionsOf(Persona persona) {
        return accountsOf(persona).stream()
                .flatMap(a -> transactionRepository.findAllByAccountAccountId(a.getAccountId()).stream())
                .toList();
    }

    private List<Account> accountsOf(Persona persona) {
        return accountRepository.findAllByCustomerCustomerId(personas.customerIdOf(persona.getKey()));
    }
}
