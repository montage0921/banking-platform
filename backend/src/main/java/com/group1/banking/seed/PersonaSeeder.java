package com.group1.banking.seed;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.group1.banking.entity.Account;
import com.group1.banking.entity.Customer;
import com.group1.banking.entity.SavingsGoal;
import com.group1.banking.entity.Transaction;
import com.group1.banking.entity.User;
import com.group1.banking.enums.CustomerType;
import com.group1.banking.repository.AccountRepository;
import com.group1.banking.repository.CustomerRepository;
import com.group1.banking.repository.SavingsGoalRepository;
import com.group1.banking.repository.TransactionRepository;
import com.group1.banking.repository.UserRepository;
import com.group1.banking.seed.PersonaCatalogue.Persona;
import com.group1.banking.seed.PersonaCatalogue.SeedAccount;
import com.group1.banking.seed.PersonaCatalogue.SeedGoal;
import com.group1.banking.seed.PersonaCatalogue.SeedTransaction;
import com.group1.banking.util.AccountIdentifiers;

/**
 * Applies the persona catalogue to the current environment.
 *
 * <p>Fill-in-missing, never overwrite: a persona already present is skipped whole. Restoring a
 * mutated persona is {@link PersonaResetService}'s job, and is always explicit - a test run
 * that moved money is not undone by restarting the application.
 *
 * <p>Each persona is created in its own transaction, so a failure part-way leaves that persona
 * absent rather than half-built. A half-built customer would read as a legitimate state and
 * quietly produce wrong test results.
 *
 * <p>Writes go through repositories rather than the service layer. Service methods such as
 * {@code AccountService.createAccount} resolve the acting user from {@code SecurityContextHolder}
 * and enforce ownership rules that assume a live authenticated request; driving them from an
 * {@code ApplicationRunner} would mean fabricating a security context purely to create test
 * data. The invariants those services enforce are not abandoned - PersonaCatalogueValidationTest
 * asserts the seeded rows satisfy the same conditions, so a divergence fails a test.
 */
@Service
public class PersonaSeeder {

    private static final Logger log = LoggerFactory.getLogger(PersonaSeeder.class);

    /**
     * Password shared by every seeded persona. Non-production only - seeding refuses to run in
     * a production-shaped environment, so this value can never reach one. Documented rather
     * than hidden, because a QA tester needs to be able to log in as a persona.
     */
    /**
     * Start of the account/customer id band owned by {@code backend/scripts/generate_seed.py},
     * which allocates bulk demo rows from 900001 upward using fixed ids. Personas must stay
     * below it so the two seeders never contend for an id, in either order.
     */
    static final long BULK_DEMO_ID_BASE = 900_000L;

    public static final String SEED_PASSWORD = "SeedPersona!2026";

    private final PersonaCatalogue catalogue;
    private final UserRepository userRepository;
    private final CustomerRepository customerRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final SavingsGoalRepository savingsGoalRepository;
    private final PasswordEncoder passwordEncoder;

    public PersonaSeeder(PersonaCatalogue catalogue,
                         UserRepository userRepository,
                         CustomerRepository customerRepository,
                         AccountRepository accountRepository,
                         TransactionRepository transactionRepository,
                         SavingsGoalRepository savingsGoalRepository,
                         PasswordEncoder passwordEncoder) {
        this.catalogue = catalogue;
        this.userRepository = userRepository;
        this.customerRepository = customerRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.savingsGoalRepository = savingsGoalRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Applies every persona that is not already present.
     *
     * @return the keys of the personas actually created
     */
    public List<String> seedAll() {
        PersonaDateAnchor anchor = PersonaDateAnchor.now();
        List<String> created = new ArrayList<>();
        for (Persona persona : catalogue.getPersonas()) {
            if (seedOne(persona, anchor)) {
                created.add(persona.getKey());
            }
        }
        log.info("Persona seeding complete: {} created, {} already present.",
                created.size(), catalogue.getPersonas().size() - created.size());
        return created;
    }

    /**
     * Creates one persona if its reserved login is not already taken.
     *
     * <p>REQUIRES_NEW so each persona commits or rolls back on its own - one malformed persona
     * cannot leave a previously-created one half-written.
     *
     * @return true if the persona was created, false if it was already present
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean seedOne(Persona persona, PersonaDateAnchor anchor) {
        if (userRepository.existsByUsernameIgnoreCase(persona.getLogin())) {
            log.debug("Persona '{}' already present ({}), leaving untouched.",
                    persona.getKey(), persona.getLogin());
            return false;
        }

        Customer customer = createCustomer(persona);
        Map<String, Account> accountsByRef = createAccounts(persona, customer);
        createTransactions(persona, accountsByRef, anchor);
        createGoals(persona, customer, accountsByRef, anchor);
        createUser(persona, customer);

        log.info("Seeded persona '{}' as {} ({} account(s), {} goal(s)).",
                persona.getKey(), persona.getLogin(),
                accountsByRef.size(), persona.getGoals().size());
        return true;
    }

    private Customer createCustomer(Persona persona) {
        Customer customer = new Customer();
        customer.setName(persona.getDisplayName());
        customer.setAddress("1 Test Street, Sample City");
        customer.setType(CustomerType.PERSON);
        // Fixed date of birth: an adult of stable age, and not derived from "now", so it does
        // not interact with any age-based rule differently between seeding runs.
        customer.setDateOfBirth(LocalDate.of(1990, 4, 17));
        customer.setKycVerified(true);
        return customerRepository.save(customer);
    }

    private Map<String, Account> createAccounts(Persona persona, Customer customer) {
        Map<String, Account> byRef = new HashMap<>();
        // Allocate the whole run of ids up front and step it locally.
        //
        // ponytail: AccountService derives ids from accountRepository.count(), which is only
        // correct when rows are inserted one at a time with a flush between. Seeding inserts
        // several accounts inside one transaction, where the count lags behind the pending
        // inserts and hands out the same id twice. Taking max(accountId)+1 as the floor keeps
        // the seeder collision-free without changing the scheme AccountService uses - once
        // seeded rows are committed, its count()+1000 still lands clear of them. The real fix
        // is a proper identity strategy on the account table; that is out of scope here.
        // Ignore anything at or above BULK_DEMO_ID_BASE when finding the high-water mark.
        // Without that bound, running backend/scripts/generate_seed.py first would push
        // persona accounts to 900301+, inside the band that script allocates from - and the
        // next regeneration with a larger --customers would then collide with them.
        long nextAccountId = Math.max(
                AccountIdentifiers.nextAccountId(accountRepository.count()),
                accountRepository.findMaxAccountIdBelow(BULK_DEMO_ID_BASE) + 1);

        for (SeedAccount seed : persona.getAccounts()) {
            Account account = new Account();
            long accountId = nextAccountId++;
            account.setAccountId(accountId);
            account.setAccountNumber(AccountIdentifiers.accountNumber(accountId));
            account.setCustomer(customer);
            account.setAccountType(seed.getType());
            account.setStatus(seed.getStatus());
            account.setBalance(seed.getBalance());
            account.setDailyTransferLimit(seed.getDailyTransferLimit());
            byRef.put(seed.getRef(), accountRepository.saveAndFlush(account));
        }
        return byRef;
    }

    private void createTransactions(Persona persona, Map<String, Account> accountsByRef,
                                    PersonaDateAnchor anchor) {
        for (SeedTransaction seed : persona.getTransactions()) {
            Account account = accountsByRef.get(seed.getAccountRef());
            for (Instant timestamp : anchor.expand(seed)) {
                Transaction transaction = new Transaction();
                transaction.setTransactionId(UUID.randomUUID().toString());
                transaction.setAccount(account);
                transaction.setAmount(seed.getAmount());
                transaction.setDirection(seed.getDirection());
                transaction.setStatus(seed.getStatus());
                // Set explicitly. Transaction's @PrePersist defaults a null timestamp to
                // Instant.now(), which would discard the anchor and flatten every persona's
                // history to today - defeating the whole point of relative anchoring.
                transaction.setTimestamp(timestamp);
                transaction.setCategory(seed.getCategory());
                transaction.setDescription(seed.getDescription());
                transactionRepository.save(transaction);
            }
        }
    }

    private void createGoals(Persona persona, Customer customer,
                             Map<String, Account> accountsByRef, PersonaDateAnchor anchor) {
        for (SeedGoal seed : persona.getGoals()) {
            SavingsGoal goal = new SavingsGoal();
            goal.setCustomerId(customer.getCustomerId());
            goal.setAccount(accountsByRef.get(seed.getAccountRef()));
            goal.setGoalName(seed.getName());
            goal.setTargetAmount(seed.getTargetAmount());
            goal.setTargetDate(anchor.daysAhead(seed.getTargetDaysAhead()));
            goal.setStatus(seed.getStatus());
            savingsGoalRepository.save(goal);
        }
    }

    private void createUser(Persona persona, Customer customer) {
        User user = new User();
        user.setUsername(persona.getLogin());
        user.setPasswordHash(passwordEncoder.encode(SEED_PASSWORD));
        user.setCustomerId(customer.getCustomerId());
        user.setRoles(new ArrayList<>(List.of(persona.getRole())));
        user.setActive(true);
        userRepository.save(user);
    }

    /** Progress a goal will show, derived the same way the Goal Tracker derives it. */
    public static BigDecimal goalProgressPercent(BigDecimal balance, BigDecimal target) {
        return balance.multiply(BigDecimal.valueOf(100))
                .divide(target, 2, java.math.RoundingMode.HALF_UP);
    }
}
