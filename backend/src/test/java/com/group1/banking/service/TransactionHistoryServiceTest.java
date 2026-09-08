package com.group1.banking.service;

import com.group1.banking.dto.response.TransactionHistoryResponse;
import com.group1.banking.entity.*;
import com.group1.banking.exception.*;
import com.group1.banking.repository.AccountRepository;
import com.group1.banking.repository.ExportCacheRepository;
import com.group1.banking.repository.TransactionQueryRepository;
import com.group1.banking.security.OwnershipValidator;
import com.group1.banking.security.UserPrincipal;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TransactionHistoryService.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransactionHistoryServiceTest {

    @Mock
    private TransactionQueryRepository transactionQueryRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private OwnershipValidator ownershipValidator;

    @Mock
    private PdfStatementService pdfStatementService;

    @Mock
    private ExportCacheRepository exportCacheRepository;

    @Mock
    private AuditService auditService;

    @InjectMocks
    private TransactionHistoryService transactionHistoryService;

    private UserPrincipal customerPrincipal;
    private Account account;
    private Customer customer;

    @BeforeEach
    void setUp() {
        customerPrincipal = new UserPrincipal(
                UUID.randomUUID().toString(),
                "customer@test.com",
                List.of("RETAIL_CUSTOMER"),
                List.of("CUSTOMER_READ", "CUSTOMER_CREATE", "CUSTOMER_UPDATE"),
                42L);

        customer = new Customer();
        customer.setCustomerId(42L);

        account = new Account();
        account.setAccountId(1001L);
        account.setCustomer(customer);
        account.setStatus(AccountStatus.ACTIVE);
        account.setBalance(new BigDecimal("500.00"));

        doNothing().when(ownershipValidator).assertOwnership(anyLong(), any());
        when(accountRepository.findById(1001L)).thenReturn(Optional.of(account));
        when(transactionQueryRepository
                .findByAccount_AccountIdAndTimestampBetweenOrderByTimestampAsc(anyLong(), any(), any()))
                .thenReturn(List.of());
        doNothing().when(auditService).log(any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ===== getHistory() TESTS =====

    @Test
    void getHistory_shouldReturnEmptyList_whenNoTransactions() {
        TransactionHistoryResponse result = transactionHistoryService.getHistory(
                1001L, null, null, customerPrincipal);

        assertThat(result).isNotNull();
        assertThat(result.getTransactionCount()).isEqualTo(0);
        assertThat(result.getAccountId()).isEqualTo(1001L);
    }

    @Test
    void getHistory_shouldReturnTransactions_whenFound() {
        Transaction tx = new Transaction();
        tx.setTransactionId("tx-001");
        tx.setAmount(new BigDecimal("100.00"));
        tx.setDirection(TransactionDirection.CREDIT);
        tx.setStatus(TransactionStatus.SUCCESS);
        tx.setTimestamp(Instant.now());
        tx.setDescription("Test");

        when(transactionQueryRepository
                .findByAccount_AccountIdAndTimestampBetweenOrderByTimestampAsc(anyLong(), any(), any()))
                .thenReturn(List.of(tx));

        TransactionHistoryResponse result = transactionHistoryService.getHistory(
                1001L, null, null, customerPrincipal);

        assertThat(result.getTransactionCount()).isEqualTo(1);
        assertThat(result.getTransactions().get(0).getTransactionId()).isEqualTo("tx-001");
    }

    @Test
    void getHistory_shouldThrow_whenNoPermission() {
        UserPrincipal noPermPrincipal = new UserPrincipal(
                UUID.randomUUID().toString(), "user@test.com",
                List.of("RETAIL_CUSTOMER"), List.of(), 42L); // no CUSTOMER_READ permission

        assertThatThrownBy(() -> transactionHistoryService.getHistory(1001L, null, null, noPermPrincipal))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void getHistory_shouldThrow_whenFutureStartDate() {
        LocalDate futureDate = LocalDate.now().plusDays(5);

        assertThatThrownBy(() -> transactionHistoryService.getHistory(1001L, futureDate, null, customerPrincipal))
                .isInstanceOf(BusinessStateException.class);
    }

    @Test
    void getHistory_shouldThrow_whenEndDateBeforeStartDate() {
        LocalDate startDate = LocalDate.now().minusDays(10);
        LocalDate endDate = LocalDate.now().minusDays(20);

        assertThatThrownBy(() -> transactionHistoryService.getHistory(1001L, startDate, endDate, customerPrincipal))
                .isInstanceOf(BusinessStateException.class);
    }

    @Test
    void getHistory_shouldThrow_whenRangeExceeds366Days() {
        LocalDate startDate = LocalDate.now().minusDays(400);
        LocalDate endDate = LocalDate.now();

        assertThatThrownBy(() -> transactionHistoryService.getHistory(1001L, startDate, endDate, customerPrincipal))
                .isInstanceOf(BusinessStateException.class);
    }

    @Test
    void getHistory_shouldThrow_whenAccountNotFound() {
        when(accountRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionHistoryService.getHistory(999L, null, null, customerPrincipal))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getHistory_shouldThrow_whenClosedAndRetentionExpired() {
        account.setStatus(AccountStatus.CLOSED);
        account.setClosedAt(Instant.now().minusSeconds(91L * 24 * 60 * 60)); // 91 days ago

        assertThatThrownBy(() -> transactionHistoryService.getHistory(1001L, null, null, customerPrincipal))
                .isInstanceOf(RetentionWindowException.class);
    }

    @Test
    void getHistory_shouldSucceed_whenClosedWithin90DayWindow() {
        account.setStatus(AccountStatus.CLOSED);
        account.setClosedAt(Instant.now().minusSeconds(30L * 24 * 60 * 60)); // 30 days ago

        TransactionHistoryResponse result = transactionHistoryService.getHistory(
                1001L, null, null, customerPrincipal);
        assertThat(result).isNotNull();
    }

    @Test
    void getHistory_shouldAcceptExplicitDateRange() {
        LocalDate startDate = LocalDate.now().minusDays(30);
        LocalDate endDate = LocalDate.now();

        TransactionHistoryResponse result = transactionHistoryService.getHistory(
                1001L, startDate, endDate, customerPrincipal);
        assertThat(result).isNotNull();
    }

    @Test
    void getHistory_shouldCapFutureEndDate() {
        LocalDate startDate = LocalDate.now().minusDays(5);
        LocalDate futureEndDate = LocalDate.now().plusDays(10); // future — should be capped

        TransactionHistoryResponse result = transactionHistoryService.getHistory(
                1001L, startDate, futureEndDate, customerPrincipal);
        assertThat(result).isNotNull();
    }

    @Test
    void getHistory_shouldThrow_whenOwnershipFails() {
        doThrow(new OwnershipException("Not owner"))
                .when(ownershipValidator).assertOwnership(anyLong(), any());

        assertThatThrownBy(() -> transactionHistoryService.getHistory(1001L, null, null, customerPrincipal))
                .isInstanceOf(OwnershipException.class);
    }

    // ===== exportPdf() TESTS =====

    @Test
    void exportPdf_shouldReturnCachedBytes_whenCacheHit() {
        byte[] cachedPdf = "PDF-CONTENT".getBytes();
        ExportCacheEntity cached = new ExportCacheEntity();
        cached.setPdfData(cachedPdf);
        when(exportCacheRepository.findByAccountIdAndParamHash(anyLong(), anyString()))
                .thenReturn(Optional.of(cached));

        byte[] result = transactionHistoryService.exportPdf(1001L, null, null, customerPrincipal);
        assertThat(result).isEqualTo(cachedPdf);
    }

    @Test
    void exportPdf_shouldGenerateAndCachePdf_whenCacheMiss() {
        when(exportCacheRepository.findByAccountIdAndParamHash(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(transactionQueryRepository
                .findByAccount_AccountIdAndTimestampBetweenOrderByTimestampAsc(anyLong(), any(), any()))
                .thenReturn(List.of());
        byte[] generatedPdf = "NEW-PDF".getBytes();
        when(pdfStatementService.buildPdf(anyLong(), any(), any(), any())).thenReturn(generatedPdf);
        when(exportCacheRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        byte[] result = transactionHistoryService.exportPdf(1001L, null, null, customerPrincipal);
        assertThat(result).isEqualTo(generatedPdf);
        verify(exportCacheRepository).save(any(ExportCacheEntity.class));
    }

    @Test
    void exportPdf_shouldThrow_whenNoPermission() {
        UserPrincipal noPermPrincipal = new UserPrincipal(
                UUID.randomUUID().toString(), "user@test.com",
                List.of("RETAIL_CUSTOMER"), List.of(), 42L);

        assertThatThrownBy(() -> transactionHistoryService.exportPdf(1001L, null, null, noPermPrincipal))
                .isInstanceOf(PermissionDeniedException.class);
    }
}
