package com.group1.banking.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.group1.banking.entity.Account;
import com.group1.banking.entity.AccountStatus;
import com.group1.banking.entity.User;
import com.group1.banking.enums.RoleName;
import com.group1.banking.repository.AccountRepository;
import com.group1.banking.repository.SavingsGoalRepository;
import com.group1.banking.repository.TransactionRepository;
import com.group1.banking.repository.UserRepository;
import com.group1.banking.seed.PersonaCatalogue.Persona;

/**
 * User Story 1: the named cast exists and is usable, with no manual data entry.
 */
class PersonaSeedingTest extends SeedTestBase {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private SavingsGoalRepository savingsGoalRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("all four personas are present and resolvable by their reserved login")
    void allPersonasSeeded() {
        assertThat(catalogue.keys())
                .containsExactlyInAnyOrder(Personas.SALARIED, Personas.GOAL_SAVER,
                        Personas.OPERATIONS, Personas.SPARSE);

        for (Persona persona : catalogue.getPersonas()) {
            assertThat(userRepository.findByUsernameIgnoreCase(persona.getLogin()))
                    .as("persona '%s' should be findable by its login", persona.getKey())
                    .isPresent();
            assertThat(personas.customerIdOf(persona.getKey())).isNotNull();
        }
    }

    @Test
    @DisplayName("each persona has exactly the accounts, transactions and goals it declares")
    void seededShapeMatchesCatalogue() {
        for (Persona persona : catalogue.getPersonas()) {
            List<Account> accounts = accountsOf(persona);
            assertThat(accounts)
                    .as("persona '%s' account count", persona.getKey())
                    .hasSize(persona.getAccounts().size());

            long expectedTransactions = persona.getTransactions().stream()
                    .mapToLong(t -> Math.max(1, t.getRepeatMonthly()))
                    .sum();
            long actualTransactions = accounts.stream()
                    .mapToLong(a -> transactionRepository
                            .findAllByAccountAccountId(a.getAccountId()).size())
                    .sum();
            assertThat(actualTransactions)
                    .as("persona '%s' transaction count (repeatMonthly expanded)", persona.getKey())
                    .isEqualTo(expectedTransactions);

            long actualGoals = accounts.stream()
                    .mapToLong(a -> savingsGoalRepository
                            .findAllByAccountAccountId(a.getAccountId()).size())
                    .sum();
            assertThat(actualGoals)
                    .as("persona '%s' goal count", persona.getKey())
                    .isEqualTo(persona.getGoals().size());
        }
    }

    @Test
    @DisplayName("the operations persona holds ADMIN and owns an account to unfreeze")
    void operationsPersonaCanManageRestrictions() {
        Persona operations = catalogue.byKey(Personas.OPERATIONS);
        User user = userRepository.findByUsernameIgnoreCase(operations.getLogin()).orElseThrow();

        assertThat(user.getRoles()).contains(RoleName.ADMIN);
        assertThat(accountsOf(operations))
                .as("a frozen account of its own, so unfreezing does not disturb another persona")
                .anyMatch(a -> a.getStatus() == AccountStatus.FROZEN);
    }

    @Test
    @DisplayName("seeded personas can actually log in with the documented password")
    void seededCredentialsWork() {
        for (Persona persona : catalogue.getPersonas()) {
            User user = userRepository.findByUsernameIgnoreCase(persona.getLogin()).orElseThrow();

            assertThat(user.isActive()).isTrue();
            assertThat(passwordEncoder.matches(PersonaSeeder.SEED_PASSWORD, user.getPasswordHash()))
                    .as("persona '%s' must be usable by a QA tester, not just by code",
                            persona.getKey())
                    .isTrue();
            assertThat(user.getPasswordHash())
                    .as("the password must be hashed, never stored in the clear")
                    .isNotEqualTo(PersonaSeeder.SEED_PASSWORD);
        }
    }

    @Test
    @DisplayName("no persona shares data with another - a persona is the unit of reset")
    void personasDoNotShareData() {
        List<Long> allAccountIds = catalogue.getPersonas().stream()
                .flatMap(p -> accountsOf(p).stream())
                .map(Account::getAccountId)
                .toList();

        assertThat(allAccountIds)
                .as("an account belonging to two personas would make one reset disturb the other")
                .doesNotHaveDuplicates();
    }

    private List<Account> accountsOf(Persona persona) {
        return accountRepository.findAllByCustomerCustomerId(personas.customerIdOf(persona.getKey()));
    }
}
