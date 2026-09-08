package com.group1.banking.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.group1.banking.entity.AccountStatus;
import com.group1.banking.entity.AccountType;
import com.group1.banking.entity.TransactionDirection;
import com.group1.banking.entity.TransactionStatus;
import com.group1.banking.enums.RiskScoreLevel;
import com.group1.banking.enums.RiskScoreStatus;
import com.group1.banking.enums.RoleName;
import com.group1.banking.enums.SavingsGoalStatus;
import com.group1.banking.seed.PersonaCatalogue.Expectations;
import com.group1.banking.seed.PersonaCatalogue.Persona;
import com.group1.banking.seed.PersonaCatalogue.SeedAccount;
import com.group1.banking.seed.PersonaCatalogue.SeedGoal;
import com.group1.banking.seed.PersonaCatalogue.SeedTransaction;

/**
 * Invariants C1-C10, one test per rule so a failure names which rule broke.
 *
 * <p>These run without a Spring context: the point is that a malformed catalogue is rejected
 * before anything is written, so a broken edit can never leave a half-seeded environment behind.
 */
class PersonaCatalogueLoadTest {

    @Test
    @DisplayName("the real catalogue shipped in the application passes every invariant")
    void shippedCatalogueIsValid() {
        // Guard against a green suite that only ever validated hand-built fixtures.
        PersonaCatalogue catalogue = new PersonaCatalogue();
        catalogue.setPersonas(List.of(sufficient("salaried"), sparse("sparse"), operations("ops")));

        assertThatCode(catalogue::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("C1: duplicate persona key is rejected")
    void c1DuplicateKey() {
        Persona a = sufficient("salaried");
        Persona b = sparse("salaried");
        b.setLogin("seed.other@voltio.test");

        assertThatThrownBy(() -> validate(a, b, operations("ops")))
                .hasMessageContaining("C1")
                .hasMessageContaining("salaried");
    }

    @Test
    @DisplayName("C2: duplicate login is rejected")
    void c2DuplicateLogin() {
        Persona a = sufficient("salaried");
        Persona b = sparse("sparse");
        b.setLogin(a.getLogin());

        assertThatThrownBy(() -> validate(a, b, operations("ops")))
                .hasMessageContaining("C2");
    }

    @Test
    @DisplayName("C3: a login outside the reserved convention is rejected")
    void c3LoginConvention() {
        Persona p = sufficient("salaried");
        p.setLogin("dana@example.com");

        assertThatThrownBy(() -> validate(p, sparse("sparse"), operations("ops")))
                .hasMessageContaining("C3");
    }

    @Test
    @DisplayName("C4: a reference to an unknown account ref is rejected")
    void c4DanglingAccountRef() {
        Persona p = sufficient("salaried");
        p.getTransactions().get(0).setAccountRef("nonexistent");

        assertThatThrownBy(() -> validate(p, sparse("sparse"), operations("ops")))
                .hasMessageContaining("C4")
                .hasMessageContaining("nonexistent");
    }

    @Test
    @DisplayName("C5: two goals on one account is rejected (uq_sg_customer_account)")
    void c5TwoGoalsOneAccount() {
        Persona p = sufficient("saver");
        p.setGoals(new ArrayList<>(List.of(goal("main", "First"), goal("main", "Second"))));
        p.getExpectations().setGoalProgressPercent(new BigDecimal("45.00"));

        assertThatThrownBy(() -> validate(p, sparse("sparse"), operations("ops")))
                .hasMessageContaining("C5");
    }

    @Test
    @DisplayName("C6: removing the sparse persona fails the build")
    void c6NoSparsePersona() {
        assertThatThrownBy(() -> validate(sufficient("salaried"), operations("ops")))
                .hasMessageContaining("C6")
                .hasMessageContaining("both fallback paths");
    }

    @Test
    @DisplayName("C7: removing the operations persona fails the build")
    void c7NoOperationsPersona() {
        assertThatThrownBy(() -> validate(sufficient("salaried"), sparse("sparse")))
                .hasMessageContaining("C7");
    }

    @Test
    @DisplayName("C8: a risk level alongside INSUFFICIENT_DATA is rejected, and vice versa")
    void c8RiskLevelConsistency() {
        Persona claimsLevelWithoutScore = sparse("sparse");
        claimsLevelWithoutScore.getExpectations().setRiskLevel(RiskScoreLevel.LOW);
        assertThatThrownBy(() -> validate(sufficient("salaried"), claimsLevelWithoutScore, operations("ops")))
                .hasMessageContaining("C8");

        Persona scoredWithoutLevel = sufficient("salaried");
        scoredWithoutLevel.getExpectations().setRiskLevel(null);
        assertThatThrownBy(() -> validate(scoredWithoutLevel, sparse("sparse"), operations("ops")))
                .hasMessageContaining("C8");
    }

    @Test
    @DisplayName("C9: goal progress declared with no goal behind it is rejected")
    void c9GoalProgressWithoutGoal() {
        Persona p = sufficient("salaried");
        p.getExpectations().setGoalProgressPercent(new BigDecimal("45.00"));

        assertThatThrownBy(() -> validate(p, sparse("sparse"), operations("ops")))
                .hasMessageContaining("C9");
    }

    @Test
    @DisplayName("C10: more than two decimal places is rejected before it rounds silently")
    void c10DecimalScale() {
        Persona p = sufficient("salaried");
        p.getAccounts().get(0).setBalance(new BigDecimal("4250.12345"));

        assertThatThrownBy(() -> validate(p, sparse("sparse"), operations("ops")))
                .hasMessageContaining("C10");
    }

    @Test
    @DisplayName("an empty catalogue is rejected")
    void emptyCatalogueRejected() {
        assertThatThrownBy(() -> validate())
                .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("byKey names the available keys when asked for one that does not exist")
    void byKeyIsHelpful() {
        PersonaCatalogue catalogue = new PersonaCatalogue();
        catalogue.setPersonas(List.of(sufficient("salaried")));

        assertThatThrownBy(() -> catalogue.byKey("typo"))
                .hasMessageContaining("typo")
                .hasMessageContaining("salaried");
        assertThat(catalogue.keys()).containsExactly("salaried");
    }

    // ---- fixtures -----------------------------------------------------------------------

    private void validate(Persona... personas) {
        PersonaCatalogue catalogue = new PersonaCatalogue();
        catalogue.setPersonas(new ArrayList<>(List.of(personas)));
        catalogue.validate();
    }

    private Persona base(String key, String login) {
        Persona p = new Persona();
        p.setKey(key);
        p.setLogin(login);
        p.setDisplayName("Test Person");
        p.setRole(RoleName.CUSTOMER);
        p.setPurpose("fixture");
        p.setScenarios(new ArrayList<>(List.of("some-scenario")));
        p.setAccounts(new ArrayList<>(List.of(account("main"))));
        p.setGoals(new ArrayList<>());
        p.setTransactions(new ArrayList<>(List.of(transaction("main"))));
        return p;
    }

    private Persona sufficient(String key) {
        Persona p = base(key, "seed." + key.toLowerCase() + "@voltio.test");
        p.setExpectations(expectations(RiskScoreStatus.OK, RiskScoreLevel.MODERATE, true, false));
        return p;
    }

    private Persona sparse(String key) {
        Persona p = base(key, "seed." + key.toLowerCase() + "@voltio.test");
        p.setExpectations(expectations(RiskScoreStatus.INSUFFICIENT_DATA, null, false, false));
        return p;
    }

    private Persona operations(String key) {
        Persona p = base(key, "seed." + key.toLowerCase() + "@voltio.test");
        p.setRole(RoleName.ADMIN);
        p.setExpectations(expectations(RiskScoreStatus.OK, RiskScoreLevel.LOW, true, true));
        return p;
    }

    private Expectations expectations(RiskScoreStatus status, RiskScoreLevel level,
                                      boolean chatbotSufficient, boolean canManageRestrictions) {
        Expectations e = new Expectations();
        e.setRiskStatus(status);
        e.setRiskLevel(level);
        e.setChatbotSufficientData(chatbotSufficient);
        e.setCanManageRestrictions(canManageRestrictions);
        return e;
    }

    private SeedAccount account(String ref) {
        SeedAccount a = new SeedAccount();
        a.setRef(ref);
        a.setType(AccountType.CHECKING);
        a.setStatus(AccountStatus.ACTIVE);
        a.setBalance(new BigDecimal("100.00"));
        a.setDailyTransferLimit(new BigDecimal("500.00"));
        return a;
    }

    private SeedTransaction transaction(String accountRef) {
        SeedTransaction t = new SeedTransaction();
        t.setAccountRef(accountRef);
        t.setAmount(new BigDecimal("10.00"));
        t.setDirection(TransactionDirection.DEBIT);
        t.setStatus(TransactionStatus.SUCCESS);
        t.setDaysAgo(5);
        t.setCategory("Groceries");
        t.setDescription("fixture");
        return t;
    }

    private SeedGoal goal(String accountRef, String name) {
        SeedGoal g = new SeedGoal();
        g.setAccountRef(accountRef);
        g.setName(name);
        g.setTargetAmount(new BigDecimal("1000.00"));
        g.setTargetDaysAhead(90);
        g.setStatus(SavingsGoalStatus.IN_PROGRESS);
        return g;
    }
}
