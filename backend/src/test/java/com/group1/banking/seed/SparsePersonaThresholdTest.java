package com.group1.banking.seed;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.group1.banking.entity.Transaction;
import com.group1.banking.enums.RiskScoreStatus;
import com.group1.banking.repository.AccountRepository;
import com.group1.banking.repository.TransactionRepository;
import com.group1.banking.service.impl.SavingsChatContextService;

/**
 * The sparse persona must fail two thresholds that are configured independently and measured
 * differently, in the same direction, with margin:
 *
 * <ul>
 *   <li><b>Risk Scoring</b> - {@code minMonths} measures the <em>age of the oldest</em>
 *       transaction.</li>
 *   <li><b>Chatbot</b> - {@code min-for-personalization} measures the <em>count within</em> a
 *       30-day lookback.</li>
 * </ul>
 *
 * <p>Because they are unrelated, a persona can drift into the gap between them, satisfying one
 * and not the other. The margin assertions exist to catch that before it becomes a confusing
 * failure in someone else's feature.
 */
class SparsePersonaThresholdTest extends SeedTestBase {

    @Autowired
    private SavingsChatContextService chatContextService;

    @Autowired
    private RiskScoreRules riskScoreRules;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Value("${app.chatbot.transactions.min-for-personalization:3}")
    private int minTransactionsForPersonalization;

    @Value("${app.chatbot.transactions.lookback-days:30}")
    private int lookbackDays;

    @Test
    @DisplayName("chatbot reports insufficient data, so it falls back instead of fabricating")
    void chatbotFallsBack() {
        Long customerId = personas.customerIdOf(Personas.SPARSE);

        SpendCategorySummary summary = chatContextService.getSpendByCategory(customerId, lookbackDays);

        assertThat(summary.sufficientData())
                .as("sparse persona must not reach the chatbot's personalisation threshold")
                .isFalse();
        // Read the expectation from the catalogue rather than restating it here.
        assertThat(summary.sufficientData())
                .isEqualTo(personas.byKey(Personas.SPARSE).getExpectations().isChatbotSufficientData());
    }

    @Test
    @DisplayName("risk scoring reports INSUFFICIENT_DATA with no score to report")
    void riskScoringReportsInsufficientData() {
        var expectations = personas.byKey(Personas.SPARSE).getExpectations();

        assertThat(expectations.getRiskStatus()).isEqualTo(RiskScoreStatus.INSUFFICIENT_DATA);
        // C8 holds: no level is claimed when there is no score.
        assertThat(expectations.getRiskLevel()).isNull();

        // The seeded data must actually put the persona below the window, not merely say so.
        Instant earliest = earliestTransactionOf(Personas.SPARSE);
        Instant riskCutoff = Instant.now().minus(
                riskScoreRules.getInsufficientConditions().getMinMonths() * 30L, ChronoUnit.DAYS);

        assertThat(earliest)
                .as("sparse persona's history must start AFTER the risk cutoff, leaving it unscorable")
                .isAfter(riskCutoff);
    }

    @Test
    @DisplayName("both thresholds are cleared with margin, not sat upon")
    void marginsAreComfortable() {
        // --- Chatbot margin: strictly below the minimum, and deliberately not zero. ---
        Long customerId = personas.customerIdOf(Personas.SPARSE);
        SpendCategorySummary summary = chatContextService.getSpendByCategory(customerId, lookbackDays);

        assertThat(summary.transactionCount())
                .as("must be strictly below min-for-personalization (%d), not equal to it",
                        minTransactionsForPersonalization)
                .isLessThan(minTransactionsForPersonalization);
        assertThat(summary.transactionCount())
                .as("must not be zero: zero passes both checks vacuously and would not catch a "
                        + "regression flipping the chatbot's >= comparison to >")
                .isGreaterThan(0);

        // --- Risk margin: nowhere near the cutoff, not just barely inside it. ---
        long historyDays = ChronoUnit.DAYS.between(earliestTransactionOf(Personas.SPARSE), Instant.now());
        long cutoffDays = riskScoreRules.getInsufficientConditions().getMinMonths() * 30L;

        assertThat(historyDays)
                .as("sparse history (%d days) should sit well inside the risk cutoff (%d days), "
                        + "not hover on the boundary", historyDays, cutoffDays)
                .isLessThan(cutoffDays / 2);
    }

    private Instant earliestTransactionOf(String personaKey) {
        Long customerId = personas.customerIdOf(personaKey);
        List<Account> accounts = accountRepository.findAllByCustomerCustomerId(customerId);
        return accounts.stream()
                .flatMap(a -> transactionRepository
                        .findAllByAccountAccountId(a.getAccountId()).stream())
                .map(Transaction::getTimestamp)
                .min(Instant::compareTo)
                .orElseThrow(() -> new AssertionError(
                        "Persona '" + personaKey + "' has no transactions at all. Zero is not the "
                                + "same as sparse - see the persona's purpose in the catalogue."));
    }
}
