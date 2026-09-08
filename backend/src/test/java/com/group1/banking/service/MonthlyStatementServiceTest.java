package com.group1.banking.service;

import com.group1.banking.entity.Account;
import com.group1.banking.entity.AccountStatus;
import com.group1.banking.entity.AccountType;
import com.group1.banking.entity.Customer;
import com.group1.banking.entity.ExportCacheEntity;
import com.group1.banking.entity.User;
import com.group1.banking.enums.CustomerType;
import com.group1.banking.enums.RoleName;
import com.group1.banking.exception.BusinessStateException;
import com.group1.banking.exception.PermissionDeniedException;
import com.group1.banking.exception.ResourceNotFoundException;
import com.group1.banking.exception.SemanticValidationException;
import com.group1.banking.repository.AccountRepository;
import com.group1.banking.repository.ExportCacheRepository;
import com.group1.banking.repository.TransactionQueryRepository;
import com.group1.banking.security.CustomUserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MonthlyStatementServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionQueryRepository transactionQueryRepository;

    @Mock
    private ExportCacheRepository exportCacheRepository;

    @Mock
    private PdfStatementService pdfStatementService;

    @Mock
    private AuditService auditService;

    @InjectMocks
    private MonthlyStatementService monthlyStatementService;

    private CustomUserPrincipal customerCaller;
    private CustomUserPrincipal adminCaller;
    private Account account;

    @BeforeEach
    void setUp() {
        User customerUser = new User();
        customerUser.setUserId(UUID.randomUUID());
        customerUser.setUsername("alice@example.com");
        customerUser.setPasswordHash("hash");
        customerUser.setRoles(new ArrayList<>(List.of(RoleName.RETAIL_CUSTOMER)));
        customerUser.setActive(true);
        customerUser.setCustomerId(42L);
        customerCaller = new CustomUserPrincipal(customerUser);

        User adminUser = new User();
        adminUser.setUserId(UUID.randomUUID());
        adminUser.setUsername("admin@example.com");
        adminUser.setPasswordHash("hash");
        adminUser.setRoles(new ArrayList<>(List.of(RoleName.BANK_ADMINISTRATOR)));
        adminUser.setActive(true);
        adminUser.setCustomerId(1L);
        adminCaller = new CustomUserPrincipal(adminUser);

        Customer customer = new Customer();
        customer.setCustomerId(42L);
        customer.setName("Alice Smith");
        customer.setType(CustomerType.PERSON);
        customer.setAddress("123 Main St");

        account = new Account();
        account.setAccountId(1001L);
        account.setCustomer(customer);
        account.setAccountNumber("CA12345678901234567890");
        account.setAccountType(AccountType.CHECKING);
        account.setStatus(AccountStatus.ACTIVE);
        account.setBalance(new BigDecimal("500.00"));
    }

    @Test
    void generateStatement_shouldThrow_whenCallerLacksCustomerReadPermission() {
        User noPermUser = new User();
        noPermUser.setUserId(UUID.randomUUID());
        noPermUser.setUsername("noperm@example.com");
        noPermUser.setPasswordHash("hash");
        noPermUser.setRoles(new ArrayList<>());
        noPermUser.setActive(true);
        noPermUser.setCustomerId(42L);
        CustomUserPrincipal noPermCaller = new CustomUserPrincipal(noPermUser);

        assertThatThrownBy(() -> monthlyStatementService.generateStatement(1001L, "2024-01", noPermCaller))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void generateStatement_shouldThrow_whenPeriodFormatIsInvalid() {
        assertThatThrownBy(() -> monthlyStatementService.generateStatement(1001L, "invalid-period", customerCaller))
                .isInstanceOf(SemanticValidationException.class);
    }

    @Test
    void generateStatement_shouldThrow_whenPeriodHasWrongFormat() {
        assertThatThrownBy(() -> monthlyStatementService.generateStatement(1001L, "01-2025", customerCaller))
                .isInstanceOf(SemanticValidationException.class);
    }

    @Test
    void generateStatement_shouldThrow_whenFutureMonthRequested() {
        String futurePeriod = YearMonth.now().plusMonths(2).toString();

        assertThatThrownBy(() -> monthlyStatementService.generateStatement(1001L, futurePeriod, customerCaller))
                .isInstanceOf(BusinessStateException.class);
    }

    @Test
    void generateStatement_shouldThrow_whenAccountNotFound() {
        when(accountRepository.findById(1001L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> monthlyStatementService.generateStatement(1001L, "2024-01", customerCaller))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void generateStatement_shouldThrow_whenCustomerDoesNotOwnAccount() {
        User otherUser = new User();
        otherUser.setUserId(UUID.randomUUID());
        otherUser.setUsername("other@example.com");
        otherUser.setPasswordHash("hash");
        otherUser.setRoles(new ArrayList<>(List.of(RoleName.RETAIL_CUSTOMER)));
        otherUser.setActive(true);
        otherUser.setCustomerId(99L);
        CustomUserPrincipal otherCaller = new CustomUserPrincipal(otherUser);

        when(accountRepository.findById(1001L)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> monthlyStatementService.generateStatement(1001L, "2024-01", otherCaller))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void generateStatement_shouldReturnCachedPdf_whenCacheHit() {
        ExportCacheEntity cachedEntry = new ExportCacheEntity();
        cachedEntry.setPdfData(new byte[]{10, 20, 30});

        when(accountRepository.findById(1001L)).thenReturn(Optional.of(account));
        when(exportCacheRepository.findByAccountIdAndParamHash(anyLong(), anyString()))
                .thenReturn(Optional.of(cachedEntry));

        byte[] result = monthlyStatementService.generateStatement(1001L, "2024-01", customerCaller);

        assertThat(result).isEqualTo(new byte[]{10, 20, 30});
        verifyNoInteractions(pdfStatementService);
    }

    @Test
    void generateStatement_shouldBuildAndCachePdf_whenCacheMiss() {
        byte[] fakePdf = new byte[]{1, 2, 3};

        when(accountRepository.findById(1001L)).thenReturn(Optional.of(account));
        when(exportCacheRepository.findByAccountIdAndParamHash(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(transactionQueryRepository.findByAccount_AccountIdAndTimestampBetweenOrderByTimestampAsc(
                anyLong(), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());
        when(pdfStatementService.buildStatementPdf(anyLong(), anyString(), anyString(), anyString(),
                any(YearMonth.class), anyBoolean(), any(BigDecimal.class), any(BigDecimal.class),
                any(BigDecimal.class), any(BigDecimal.class), anyList()))
                .thenReturn(fakePdf);
        when(exportCacheRepository.save(any(ExportCacheEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        byte[] result = monthlyStatementService.generateStatement(1001L, "2024-01", customerCaller);

        assertThat(result).isEqualTo(fakePdf);
        verify(exportCacheRepository).save(any(ExportCacheEntity.class));
        verify(auditService).log(eq(com.group1.banking.entity.AuditEventType.STATEMENT_DOWNLOADED), anyString(), eq(RoleName.RETAIL_CUSTOMER), anyString(),
                eq("STATEMENT"), anyString(), eq(com.group1.banking.entity.AuditOutcome.SUCCESS), any());
    }

    @Test
    void generateStatement_shouldAllowAdmin_toAccessAnyAccount() {
        byte[] fakePdf = new byte[]{5, 6, 7};

        when(accountRepository.findById(1001L)).thenReturn(Optional.of(account));
        when(exportCacheRepository.findByAccountIdAndParamHash(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(transactionQueryRepository.findByAccount_AccountIdAndTimestampBetweenOrderByTimestampAsc(
                anyLong(), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());
        when(pdfStatementService.buildStatementPdf(anyLong(), anyString(), anyString(), anyString(),
                any(YearMonth.class), anyBoolean(), any(BigDecimal.class), any(BigDecimal.class),
                any(BigDecimal.class), any(BigDecimal.class), anyList()))
                .thenReturn(fakePdf);
        when(exportCacheRepository.save(any(ExportCacheEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        byte[] result = monthlyStatementService.generateStatement(1001L, "2024-01", adminCaller);
        assertThat(result).isEqualTo(fakePdf);
    }

    @Test
    void generateStatement_shouldGenerateForCurrentMonth() {
        byte[] fakePdf = new byte[]{8, 9};
        String currentPeriod = YearMonth.now().toString();

        when(accountRepository.findById(1001L)).thenReturn(Optional.of(account));
        when(exportCacheRepository.findByAccountIdAndParamHash(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(transactionQueryRepository.findByAccount_AccountIdAndTimestampBetweenOrderByTimestampAsc(
                anyLong(), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());
        when(pdfStatementService.buildStatementPdf(anyLong(), anyString(), anyString(), anyString(),
                any(YearMonth.class), anyBoolean(), any(BigDecimal.class), any(BigDecimal.class),
                any(BigDecimal.class), any(BigDecimal.class), anyList()))
                .thenReturn(fakePdf);
        when(exportCacheRepository.save(any(ExportCacheEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        byte[] result = monthlyStatementService.generateStatement(1001L, currentPeriod, customerCaller);
        assertThat(result).isEqualTo(fakePdf);
    }
}
