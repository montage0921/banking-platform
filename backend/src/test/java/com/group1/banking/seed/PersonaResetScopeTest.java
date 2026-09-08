package com.group1.banking.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.group1.banking.entity.Account;
import com.group1.banking.repository.AccountRepository;
import com.group1.banking.repository.UserRepository;

/**
 * Three behaviours that are easy to conflate, and one that matters most.
 *
 * <p>The middle test - reset one persona, leave the other mutated - is what makes a shared QA
 * environment usable by two people at once. Without it, one person's cleanup silently destroys
 * the other's half-finished run and the baseline stops being trustworthy.
 */
class PersonaResetScopeTest extends SeedTestBase {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("re-applying the baseline does NOT undo a mutation - only reset does")
    void reseedDoesNotOverwrite() {
        BigDecimal mutated = new BigDecimal("1.00");
        mutateBalance(Personas.SALARIED, mutated);

        seeder.seedAll();

        assertThat(firstBalanceOf(Personas.SALARIED))
                .as("fill-in-missing must leave an existing persona untouched, however odd its state")
                .isEqualByComparingTo(mutated);
    }

    @Test
    @DisplayName("resetting one persona leaves another person's in-flight work alone")
    void resetIsScopedToNamedPersonas() {
        BigDecimal mutatedA = new BigDecimal("11.00");
        BigDecimal mutatedB = new BigDecimal("22.00");
        mutateBalance(Personas.SALARIED, mutatedA);
        mutateBalance(Personas.GOAL_SAVER, mutatedB);

        resetService.reset(Personas.SALARIED);

        assertThat(firstBalanceOf(Personas.SALARIED))
                .as("the named persona returns to its documented baseline")
                .isNotEqualByComparingTo(mutatedA);
        assertThat(firstBalanceOf(Personas.GOAL_SAVER))
                .as("a persona nobody named must keep its mutation - this is what lets two "
                        + "people share a QA environment without destroying each other's runs")
                .isEqualByComparingTo(mutatedB);
    }

    @Test
    @DisplayName("applying the baseline repeatedly never duplicates anything")
    void reseedingIsIdempotent() {
        long usersBefore = countSeededUsers();
        long accountsBefore = accountRepository.count();

        seeder.seedAll();
        seeder.seedAll();

        assertThat(countSeededUsers()).isEqualTo(usersBefore);
        assertThat(accountRepository.count()).isEqualTo(accountsBefore);
    }

    @Test
    @DisplayName("resetAll is just resetting every persona - no behaviour of its own")
    void resetAllRestoresEveryPersona() {
        for (String key : catalogue.keys()) {
            mutateBalance(key, new BigDecimal("3.00"));
        }

        resetService.resetAll();

        for (String key : catalogue.keys()) {
            assertThat(firstBalanceOf(key))
                    .as("persona '%s' should be back at its baseline", key)
                    .isNotEqualByComparingTo(new BigDecimal("3.00"));
        }
        assertThat(countSeededUsers()).isEqualTo(catalogue.getPersonas().size());
    }

    @Test
    @DisplayName("no account id is issued twice, even after repeated resets")
    void resetsDoNotProduceDuplicateAccountIds() {
        resetService.resetAll();
        resetService.reset(Personas.SPARSE);
        resetService.resetAll();

        List<Long> ids = accountRepository.findAll().stream().map(Account::getAccountId).toList();

        assertThat(ids).doesNotHaveDuplicates();
    }

    private void mutateBalance(String personaKey, BigDecimal balance) {
        Account account = accountsOf(personaKey).get(0);
        account.setBalance(balance);
        accountRepository.saveAndFlush(account);
    }

    private BigDecimal firstBalanceOf(String personaKey) {
        return accountsOf(personaKey).get(0).getBalance();
    }

    private List<Account> accountsOf(String personaKey) {
        return accountRepository.findAllByCustomerCustomerId(personas.customerIdOf(personaKey))
                .stream()
                .sorted((a, b) -> Long.compare(a.getAccountId(), b.getAccountId()))
                .toList();
    }

    private long countSeededUsers() {
        return catalogue.getPersonas().stream()
                .filter(p -> userRepository.existsByUsernameIgnoreCase(p.getLogin()))
                .count();
    }
}
