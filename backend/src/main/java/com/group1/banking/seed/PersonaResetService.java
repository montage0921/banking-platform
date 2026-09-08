package com.group1.banking.seed;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.group1.banking.entity.Account;
import com.group1.banking.entity.Customer;
import com.group1.banking.entity.User;
import com.group1.banking.repository.AccountRepository;
import com.group1.banking.repository.CustomerRepository;
import com.group1.banking.repository.SavingsGoalRepository;
import com.group1.banking.repository.TransactionRepository;
import com.group1.banking.repository.UserRepository;
import com.group1.banking.seed.PersonaCatalogue.Persona;

/**
 * Restores personas to their documented starting state.
 *
 * <p><b>Scoped per persona.</b> QA and demo environments are shared, and a reset that wiped
 * everything would destroy a colleague's half-finished run - turning the baseline from a
 * trustworthy reference into a source of phantom failures. Resetting one persona leaves every
 * other persona, and any in-flight work against it, untouched.
 *
 * <p>Resetting the whole baseline is simply resetting every persona; there is no separate
 * whole-baseline code path with behaviour of its own.
 *
 * <p><b>Never automatic.</b> This runs only when explicitly invoked. Restoring on every startup
 * would silently discard whatever a developer was in the middle of.
 *
 * <p>Delete-then-recreate rather than diff-and-patch: dates are re-anchored to the moment of the
 * reset, which is the point. Patching would have to reconcile arbitrary mutations against a
 * moving anchor for no gain.
 */
@Service
public class PersonaResetService {

    private static final Logger log = LoggerFactory.getLogger(PersonaResetService.class);

    private final PersonaCatalogue catalogue;
    private final PersonaSeeder seeder;
    private final UserRepository userRepository;
    private final CustomerRepository customerRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final SavingsGoalRepository savingsGoalRepository;

    public PersonaResetService(PersonaCatalogue catalogue,
                               PersonaSeeder seeder,
                               UserRepository userRepository,
                               CustomerRepository customerRepository,
                               AccountRepository accountRepository,
                               TransactionRepository transactionRepository,
                               SavingsGoalRepository savingsGoalRepository) {
        this.catalogue = catalogue;
        this.seeder = seeder;
        this.userRepository = userRepository;
        this.customerRepository = customerRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.savingsGoalRepository = savingsGoalRepository;
    }

    /**
     * Restores the named personas and nothing else.
     *
     * @param personaKeys catalogue keys; unknown keys fail fast rather than silently doing nothing
     */
    public void reset(String... personaKeys) {
        PersonaDateAnchor anchor = PersonaDateAnchor.now();
        for (String key : personaKeys) {
            Persona persona = catalogue.byKey(key);
            removePersona(persona);
            seeder.seedOne(persona, anchor);
            log.info("Reset persona '{}' to its documented baseline.", key);
        }
    }

    /** Whole-baseline reset expressed as resetting every persona - no separate behaviour. */
    public void resetAll() {
        reset(catalogue.keys().toArray(String[]::new));
    }

    /**
     * Deletes one persona's data in dependency order.
     *
     * <p>Order matters and is not obvious from the mappings: {@code Customer.accounts} cascades
     * ALL and {@code riskScoreHistory} cascades with orphanRemoval, but transactions and savings
     * goals are owned by {@code Account} without a cascading collection, so they must be removed
     * explicitly before the account they point at.
     */
    @Transactional
    public void removePersona(Persona persona) {
        User user = userRepository.findByUsernameIgnoreCase(persona.getLogin()).orElse(null);
        if (user == null) {
            return;
        }

        Long customerId = user.getCustomerId();
        userRepository.delete(user);

        if (customerId == null) {
            return;
        }

        Customer customer = customerRepository.findById(customerId).orElse(null);
        if (customer == null) {
            return;
        }

        List<Account> accounts = accountRepository.findAllByCustomerCustomerId(customerId);
        for (Account account : accounts) {
            savingsGoalRepository.deleteAll(
                    savingsGoalRepository.findAllByAccountAccountId(account.getAccountId()));
            transactionRepository.deleteAll(
                    transactionRepository.findAllByAccountAccountId(account.getAccountId()));
        }
        accountRepository.deleteAll(accounts);

        // Cascades to riskScoreHistory via orphanRemoval.
        customerRepository.delete(customer);
    }
}
