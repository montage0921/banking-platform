package com.group1.banking.service;

import com.group1.banking.dto.response.RecategoriseResponse;
import com.group1.banking.dto.response.SpendingInsightResponse;
import com.group1.banking.entity.*;
import com.group1.banking.enums.RoleName;
import com.group1.banking.exception.*;
import com.group1.banking.repository.AccountRepository;
import com.group1.banking.repository.TransactionQueryRepository;
import com.group1.banking.security.CustomUserPrincipal;
import com.group1.banking.security.OwnershipValidator;
import com.group1.banking.util.CategoryResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SpendingInsightService.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SpendingInsightServiceTest {

    @Mock
    private TransactionQueryRepository transactionQueryRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private OwnershipValidator ownershipValidator;

    @Mock
    private CategoryResolver categoryResolver;

    @Mock
    private AuditService auditService;

    @InjectMocks
    private SpendingInsightService spendingInsightService;

    private CustomUserPrincipal customerPrincipal;
    private Account account;
    private User customerUser;

    @BeforeEach
    void setUp() {
        customerUser = new User();
        customerUser.setUserId(UUID.randomUUID());
        customerUser.setUsername("customer@test.com");
        customerUser.setCustomerId(42L);
        customerUser.setRoles(List.of(RoleName.RETAIL_CUSTOMER));
        customerUser.setActive(true);

        customerPrincipal = new CustomUserPrincipal(customerUser);

        account = new Account();
        account.setAccountId(1001L);
        account.setStatus(AccountStatus.ACTIVE);
        account.setBalance(new BigDecimal("1000.00"));
        account.setCreatedAt(Instant.now().minusSeconds(365L * 24 * 60 * 60)); // 1 year ago

        Customer customer = new Customer();
        customer.setCustomerId(42L);
        account.setCustomer(customer);

        doNothing().when(ownershipValidator).assertOwnership(anyLong(), any());
        when(accountRepository.findById(1001L)).thenReturn(Optional.of(account));
        when(transactionQueryRepository.findEligibleForInsights(anyLong(), any(), any(), any(), any()))
                .thenReturn(List.of());
        doNothing().when(auditService).log(any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ===== getInsights() TESTS =====

    @Test
    void getInsights_shouldReturnEmptyInsights_whenNoTransactions() {
        YearMonth lastMonth = YearMonth.now().minusMonths(1);

        SpendingInsightResponse result = spendingInsightService.getInsights(
                1001L, lastMonth.getYear(), lastMonth.getMonthValue(), customerPrincipal);

        assertThat(result).isNotNull();
        assertThat(result.getAccountId()).isEqualTo(1001L);
        assertThat(result.getTotalDebitSpend()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getTransactionCount()).isEqualTo(0);
    }

    @Test
    void getInsights_shouldThrow_whenNoPermission() {
        User noPermUser = new User();
        noPermUser.setUserId(UUID.randomUUID());
        noPermUser.setRoles(List.of()); // no roles
        noPermUser.setActive(true);
        CustomUserPrincipal noPerm = new CustomUserPrincipal(noPermUser);

        assertThatThrownBy(() -> spendingInsightService.getInsights(1001L, 2024, 1, noPerm))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void getInsights_shouldThrow_whenInvalidMonth_zero() {
        assertThatThrownBy(() -> spendingInsightService.getInsights(1001L, 2024, 0, customerPrincipal))
                .isInstanceOf(BusinessStateException.class);
    }

    @Test
    void getInsights_shouldThrow_whenInvalidMonth_thirteen() {
        assertThatThrownBy(() -> spendingInsightService.getInsights(1001L, 2024, 13, customerPrincipal))
                .isInstanceOf(BusinessStateException.class);
    }

    @Test
    void getInsights_shouldThrow_whenFutureMonth() {
        YearMonth futureMonth = YearMonth.now().plusMonths(1);

        assertThatThrownBy(() -> spendingInsightService.getInsights(
                1001L, futureMonth.getYear(), futureMonth.getMonthValue(), customerPrincipal))
                .isInstanceOf(BusinessStateException.class);
    }

    @Test
    void getInsights_shouldThrow_whenAccountNotFound() {
        when(accountRepository.findById(999L)).thenReturn(Optional.empty());

        YearMonth lastMonth = YearMonth.now().minusMonths(1);
        assertThatThrownBy(() -> spendingInsightService.getInsights(
                999L, lastMonth.getYear(), lastMonth.getMonthValue(), customerPrincipal))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getInsights_shouldReturnCategoryBreakdown_withTransactions() {
        Transaction tx1 = new Transaction();
        tx1.setTransactionId("tx-001");
        tx1.setAmount(new BigDecimal("50.00"));
        tx1.setDirection(TransactionDirection.DEBIT);
        tx1.setStatus(TransactionStatus.SUCCESS);
        tx1.setTimestamp(Instant.now().minusSeconds(5000));
        tx1.setCategory("Food & Drink");

        when(transactionQueryRepository.findEligibleForInsights(anyLong(), any(), any(), any(), any()))
                .thenReturn(List.of(tx1));

        YearMonth lastMonth = YearMonth.now().minusMonths(1);
        SpendingInsightResponse result = spendingInsightService.getInsights(
                1001L, lastMonth.getYear(), lastMonth.getMonthValue(), customerPrincipal);

        assertThat(result.getTransactionCount()).isEqualTo(1);
        assertThat(result.getTotalDebitSpend()).isEqualByComparingTo(new BigDecimal("50.00"));
    }

    @Test
    void getInsights_shouldAutoCategorise_whenCategoryIsNull() {
        Transaction tx1 = new Transaction();
        tx1.setTransactionId("tx-002");
        tx1.setAmount(new BigDecimal("30.00"));
        tx1.setDirection(TransactionDirection.DEBIT);
        tx1.setStatus(TransactionStatus.SUCCESS);
        tx1.setTimestamp(Instant.now().minusSeconds(5000));
        tx1.setCategory(null); // null — should trigger auto-categorisation
        tx1.setDescription("Grocery store");

        when(transactionQueryRepository.findEligibleForInsights(anyLong(), any(), any(), any(), any()))
                .thenReturn(List.of(tx1));
        when(categoryResolver.resolve("Grocery store")).thenReturn("Food & Drink");

        YearMonth lastMonth = YearMonth.now().minusMonths(1);
        SpendingInsightResponse result = spendingInsightService.getInsights(
                1001L, lastMonth.getYear(), lastMonth.getMonthValue(), customerPrincipal);

        assertThat(result).isNotNull();
        verify(transactionQueryRepository).save(tx1); // auto-categorise saved
    }

    @Test
    void getInsights_shouldMarkUncategorised_whenCategoryResolverReturnsNull() {
        Transaction tx1 = new Transaction();
        tx1.setTransactionId("tx-003");
        tx1.setAmount(new BigDecimal("15.00"));
        tx1.setDirection(TransactionDirection.DEBIT);
        tx1.setStatus(TransactionStatus.SUCCESS);
        tx1.setTimestamp(Instant.now().minusSeconds(5000));
        tx1.setCategory(null);
        tx1.setDescription("Unknown vendor");

        when(transactionQueryRepository.findEligibleForInsights(anyLong(), any(), any(), any(), any()))
                .thenReturn(List.of(tx1));
        when(categoryResolver.resolve("Unknown vendor")).thenReturn(null);

        YearMonth lastMonth = YearMonth.now().minusMonths(1);
        SpendingInsightResponse result = spendingInsightService.getInsights(
                1001L, lastMonth.getYear(), lastMonth.getMonthValue(), customerPrincipal);

        assertThat(result.isHasUncategorised()).isTrue();
    }

    @Test
    void getInsights_shouldBuildSixMonthTrend() {
        YearMonth lastMonth = YearMonth.now().minusMonths(1);

        SpendingInsightResponse result = spendingInsightService.getInsights(
                1001L, lastMonth.getYear(), lastMonth.getMonthValue(), customerPrincipal);

        assertThat(result.getSixMonthTrend()).isNotNull();
        assertThat(result.getSixMonthTrend()).hasSize(6);
    }

    @Test
    void getInsights_shouldThrow_whenOwnershipFails() {
        doThrow(new OwnershipException("Not owner"))
                .when(ownershipValidator).assertOwnership(anyLong(), any());

        YearMonth lastMonth = YearMonth.now().minusMonths(1);
        assertThatThrownBy(() -> spendingInsightService.getInsights(
                1001L, lastMonth.getYear(), lastMonth.getMonthValue(), customerPrincipal))
                .isInstanceOf(OwnershipException.class);
    }

    @Test
    void getInsights_shouldWork_whenAdminRole() {
        User adminUser = new User();
        adminUser.setUserId(UUID.randomUUID());
        adminUser.setRoles(List.of(RoleName.BANK_ADMINISTRATOR));
        adminUser.setActive(true);
        CustomUserPrincipal adminPrincipal = new CustomUserPrincipal(adminUser);

        YearMonth lastMonth = YearMonth.now().minusMonths(1);
        SpendingInsightResponse result = spendingInsightService.getInsights(
                1001L, lastMonth.getYear(), lastMonth.getMonthValue(), adminPrincipal);

        assertThat(result).isNotNull();
    }

    // ===== recategorise() TESTS =====

    @Test
    void recategorise_shouldReturnUpdatedCategory_whenValid() {
        Transaction tx = new Transaction();
        tx.setTransactionId("tx-100");
        tx.setAmount(new BigDecimal("75.00"));
        tx.setDirection(TransactionDirection.DEBIT);
        tx.setStatus(TransactionStatus.SUCCESS);
        tx.setTimestamp(Instant.now().minusSeconds(60000));
        tx.setCategory("Shopping");

        when(transactionQueryRepository.findById("tx-100")).thenReturn(Optional.of(tx));
        when(transactionQueryRepository.save(any(Transaction.class))).thenReturn(tx);
        when(transactionQueryRepository.findEligibleForInsights(anyLong(), any(), any(), any(), any()))
                .thenReturn(List.of(tx));

        RecategoriseResponse result = spendingInsightService.recategorise(
                1001L, "tx-100", "Food & Drink", customerPrincipal);

        assertThat(result.getTransactionId()).isEqualTo("tx-100");
        assertThat(result.getPreviousCategory()).isEqualTo("Shopping");
        assertThat(result.getUpdatedCategory()).isEqualTo("Food & Drink");
    }

    @Test
    void recategorise_shouldThrow_whenNoPermission() {
        User noPermUser = new User();
        noPermUser.setUserId(UUID.randomUUID());
        noPermUser.setRoles(List.of());
        noPermUser.setActive(true);
        CustomUserPrincipal noPerm = new CustomUserPrincipal(noPermUser);

        assertThatThrownBy(() -> spendingInsightService.recategorise(
                1001L, "tx-100", "Food & Drink", noPerm))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void recategorise_shouldThrow_whenInvalidCategory() {
        assertThatThrownBy(() -> spendingInsightService.recategorise(
                1001L, "tx-100", "InvalidCat", customerPrincipal))
                .isInstanceOf(SemanticValidationException.class);
    }

    @Test
    void recategorise_shouldThrow_whenTransactionNotFound() {
        when(transactionQueryRepository.findById("missing-tx")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> spendingInsightService.recategorise(
                1001L, "missing-tx", "Housing", customerPrincipal))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void recategorise_shouldThrow_whenOwnershipFails() {
        doThrow(new OwnershipException("Not owner"))
                .when(ownershipValidator).assertOwnership(anyLong(), any());

        assertThatThrownBy(() -> spendingInsightService.recategorise(
                1001L, "tx-100", "Housing", customerPrincipal))
                .isInstanceOf(OwnershipException.class);
    }
}
