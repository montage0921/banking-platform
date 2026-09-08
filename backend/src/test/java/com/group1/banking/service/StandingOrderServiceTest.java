package com.group1.banking.service;

import com.group1.banking.dto.request.CreateStandingOrderRequest;
import com.group1.banking.dto.response.CancelStandingOrderResponse;
import com.group1.banking.dto.response.StandingOrderListResponse;
import com.group1.banking.dto.response.StandingOrderResponse;
import com.group1.banking.entity.*;
import com.group1.banking.exception.*;
import com.group1.banking.mapper.StandingOrderMapper;
import com.group1.banking.repository.AccountRepository;
import com.group1.banking.repository.StandingOrderRepository;
import com.group1.banking.security.CustomUserPrincipal;
import com.group1.banking.security.OwnershipValidator;
import com.group1.banking.util.CanadianHolidayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for StandingOrderService.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StandingOrderServiceTest {

    @Mock
    private StandingOrderRepository standingOrderRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private OwnershipValidator ownershipValidator;

    @Mock
    private CanadianHolidayService canadianHolidayService;

    @Mock
    private StandingOrderMapper mapper;

    @Mock
    private AuditService auditService;

    @InjectMocks
    private StandingOrderService standingOrderService;

    private CustomUserPrincipal customerPrincipal;
    private Account payeeAccount;
    private Account sourceAccount;
    private CreateStandingOrderRequest createRequest;
    private StandingOrderResponse standingOrderResponse;

    @BeforeEach
    void setUp() {
        // Create a CUSTOMER user
        com.group1.banking.entity.User customerUser = new com.group1.banking.entity.User();
        customerUser.setUserId(UUID.randomUUID());
        customerUser.setCustomerId(42L);
        customerUser.setRoles(List.of(com.group1.banking.enums.RoleName.RETAIL_CUSTOMER));
        customerUser.setActive(true);
        customerPrincipal = new CustomUserPrincipal(customerUser);

        Customer customer = new Customer();
        customer.setCustomerId(42L);

        sourceAccount = new Account();
        sourceAccount.setAccountId(1001L);
        sourceAccount.setCustomer(customer);
        sourceAccount.setStatus(AccountStatus.ACTIVE);
        sourceAccount.setDailyTransferLimit(new BigDecimal("3000.00"));

        Customer payeeCustomer = new Customer();
        payeeCustomer.setCustomerId(99L);
        payeeAccount = new Account();
        payeeAccount.setAccountId(2002L);
        payeeAccount.setCustomer(payeeCustomer);
        payeeAccount.setStatus(AccountStatus.ACTIVE);
        payeeAccount.setDailyTransferLimit(new BigDecimal("3000.00"));

        createRequest = new CreateStandingOrderRequest();
        createRequest.setPayeeAccount(2002L);
        createRequest.setPayeeName("John Payee");
        createRequest.setAmount(new BigDecimal("100.00"));
        createRequest.setFrequency("MONTHLY");
        createRequest.setStartDate(LocalDateTime.now().plusDays(2));
        createRequest.setReference("REF001");

        standingOrderResponse = new StandingOrderResponse();
        standingOrderResponse.setStandingOrderId("so-001");

        // Default stubs
        doNothing().when(ownershipValidator).assertOwnership(anyLong(), any());
        when(accountRepository.findById(2002L)).thenReturn(Optional.of(payeeAccount));
        when(accountRepository.findByAccountId(2002L)).thenReturn(Optional.of(payeeAccount));
        when(standingOrderRepository.findBySourceAccountIdAndPayeeAccountAndAmountAndFrequencyAndStatus(
                anyLong(), anyLong(), any(), any(), any())).thenReturn(Optional.empty());
        when(canadianHolidayService.nextBusinessDay(any())).thenAnswer(inv -> inv.getArgument(0));
        when(standingOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mapper.toResponse(any())).thenReturn(standingOrderResponse);
        doNothing().when(auditService).log(any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ===== create() TESTS =====

    @Test
    void create_shouldReturnResponse_whenValidRequest() {
        StandingOrderResponse result = standingOrderService.create(1001L, createRequest, customerPrincipal);

        assertThat(result).isNotNull();
        verify(standingOrderRepository).save(any(StandingOrderEntity.class));
    }

    @Test
    void create_shouldSetStatusActive_onNewOrder() {
        standingOrderService.create(1001L, createRequest, customerPrincipal);

        verify(standingOrderRepository).save(argThat(e ->
                e.getStatus() == StandingOrderStatus.ACTIVE));
    }

    @Test
    void create_shouldThrow_whenStartDateIsNull() {
        createRequest.setStartDate(null);

        assertThatThrownBy(() -> standingOrderService.create(1001L, createRequest, customerPrincipal))
                .isInstanceOf(SemanticValidationException.class);
    }

    @Test
    void create_shouldThrow_whenStartDateWithin24Hours() {
        createRequest.setStartDate(LocalDateTime.now().plusHours(12));

        assertThatThrownBy(() -> standingOrderService.create(1001L, createRequest, customerPrincipal))
                .isInstanceOf(SemanticValidationException.class);
    }

    @Test
    void create_shouldThrow_whenEndDateBeforeStartDate() {
        createRequest.setStartDate(LocalDateTime.now().plusDays(3));
        createRequest.setEndDate(LocalDateTime.now().plusDays(2)); // before startDate

        assertThatThrownBy(() -> standingOrderService.create(1001L, createRequest, customerPrincipal))
                .isInstanceOf(BusinessStateException.class);
    }

    @Test
    void create_shouldThrow_whenPayeeAccountNotFound() {
        when(accountRepository.findById(2002L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> standingOrderService.create(1001L, createRequest, customerPrincipal))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void create_shouldThrow_whenAmountExceedsDailyTransferLimit() {
        createRequest.setAmount(new BigDecimal("5000.00")); // exceeds 3000.00 limit

        assertThatThrownBy(() -> standingOrderService.create(1001L, createRequest, customerPrincipal))
                .isInstanceOf(SemanticValidationException.class);
    }

    @Test
    void create_shouldThrow_whenInvalidFrequency() {
        createRequest.setFrequency("ANNUALLY");

        assertThatThrownBy(() -> standingOrderService.create(1001L, createRequest, customerPrincipal))
                .isInstanceOf(SemanticValidationException.class);
    }

    @Test
    void create_shouldThrow_whenDuplicateActiveOrderExists() {
        StandingOrderEntity existing = new StandingOrderEntity();
        existing.setStatus(StandingOrderStatus.ACTIVE);
        when(standingOrderRepository.findBySourceAccountIdAndPayeeAccountAndAmountAndFrequencyAndStatus(
                anyLong(), anyLong(), any(), any(), any())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> standingOrderService.create(1001L, createRequest, customerPrincipal))
                .isInstanceOf(BusinessStateException.class);
    }

    @Test
    void create_shouldThrow_whenNoPermission() {
        // Create user with no CUSTOMER_CREATE permission (e.g., ADMIN has it, but let's try empty roles)
        com.group1.banking.entity.User noPermUser = new com.group1.banking.entity.User();
        noPermUser.setUserId(UUID.randomUUID());
        noPermUser.setCustomerId(42L);
        noPermUser.setRoles(List.of()); // no roles → no permissions
        noPermUser.setActive(true);
        CustomUserPrincipal noPermPrincipal = new CustomUserPrincipal(noPermUser);

        assertThatThrownBy(() -> standingOrderService.create(1001L, createRequest, noPermPrincipal))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void create_shouldThrow_whenOwnershipFails() {
        doThrow(new OwnershipException("Not owner"))
                .when(ownershipValidator).assertOwnership(anyLong(), any());

        assertThatThrownBy(() -> standingOrderService.create(1001L, createRequest, customerPrincipal))
                .isInstanceOf(OwnershipException.class);
    }

    @Test
    void create_shouldUseWeeklyFrequency() {
        createRequest.setFrequency("WEEKLY");

        StandingOrderResponse result = standingOrderService.create(1001L, createRequest, customerPrincipal);
        assertThat(result).isNotNull();
        verify(standingOrderRepository).save(argThat(e -> e.getFrequency() == Frequency.WEEKLY));
    }

    @Test
    void create_shouldUseQuarterlyFrequency() {
        createRequest.setFrequency("QUARTERLY");

        StandingOrderResponse result = standingOrderService.create(1001L, createRequest, customerPrincipal);
        assertThat(result).isNotNull();
        verify(standingOrderRepository).save(argThat(e -> e.getFrequency() == Frequency.QUARTERLY));
    }

    @Test
    void create_shouldAcceptEndDate_whenAfterStartDate() {
        createRequest.setEndDate(LocalDateTime.now().plusMonths(6));

        StandingOrderResponse result = standingOrderService.create(1001L, createRequest, customerPrincipal);
        assertThat(result).isNotNull();
    }

    // ===== list() TESTS =====

    @Test
    void list_shouldReturnAllOrdersForAccount() {
        StandingOrderEntity entity1 = new StandingOrderEntity();
        entity1.setStandingOrderId("so-001");
        StandingOrderEntity entity2 = new StandingOrderEntity();
        entity2.setStandingOrderId("so-002");
        when(standingOrderRepository.findBySourceAccountId(1001L)).thenReturn(List.of(entity1, entity2));

        StandingOrderListResponse result = standingOrderService.list(1001L, customerPrincipal);

        assertThat(result.getStandingOrderCount()).isEqualTo(2);
    }

    @Test
    void list_shouldReturnEmptyList_whenNoOrders() {
        when(standingOrderRepository.findBySourceAccountId(1001L)).thenReturn(List.of());

        StandingOrderListResponse result = standingOrderService.list(1001L, customerPrincipal);

        assertThat(result.getStandingOrderCount()).isEqualTo(0);
        assertThat(result.getStandingOrders()).isEmpty();
    }

    @Test
    void list_shouldThrow_whenNoPermission() {
        com.group1.banking.entity.User noPermUser = new com.group1.banking.entity.User();
        noPermUser.setUserId(UUID.randomUUID());
        noPermUser.setCustomerId(42L);
        noPermUser.setRoles(List.of());
        noPermUser.setActive(true);
        CustomUserPrincipal noPermPrincipal = new CustomUserPrincipal(noPermUser);

        assertThatThrownBy(() -> standingOrderService.list(1001L, noPermPrincipal))
                .isInstanceOf(PermissionDeniedException.class);
    }

    // ===== cancel() TESTS =====

    @Test
    void cancel_shouldSetStatusCancelled() {
        StandingOrderEntity entity = new StandingOrderEntity();
        entity.setStandingOrderId("so-001");
        entity.setSourceAccountId(1001L);
        entity.setStatus(StandingOrderStatus.ACTIVE);
        entity.setNextRunDate(LocalDateTime.now().plusDays(5)); // far enough away
        when(standingOrderRepository.findById("so-001")).thenReturn(Optional.of(entity));
        when(standingOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CancelStandingOrderResponse result = standingOrderService.cancel("so-001", customerPrincipal);

        assertThat(result.getStatus()).isEqualTo("CANCELLED");
        verify(standingOrderRepository).save(argThat(e ->
                e.getStatus() == StandingOrderStatus.CANCELLED));
    }

    @Test
    void cancel_shouldThrow_whenOrderNotFound() {
        when(standingOrderRepository.findById("so-missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> standingOrderService.cancel("so-missing", customerPrincipal))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void cancel_shouldThrow_whenWithin24HoursOfNextRun() {
        StandingOrderEntity entity = new StandingOrderEntity();
        entity.setStandingOrderId("so-001");
        entity.setSourceAccountId(1001L);
        entity.setStatus(StandingOrderStatus.ACTIVE);
        entity.setNextRunDate(LocalDateTime.now().plusHours(12)); // within 24h lock window
        when(standingOrderRepository.findById("so-001")).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> standingOrderService.cancel("so-001", customerPrincipal))
                .isInstanceOf(LockException.class);
    }

    @Test
    void cancel_shouldThrow_whenNoPermission() {
        com.group1.banking.entity.User noPermUser = new com.group1.banking.entity.User();
        noPermUser.setUserId(UUID.randomUUID());
        noPermUser.setCustomerId(42L);
        noPermUser.setRoles(List.of());
        noPermUser.setActive(true);
        CustomUserPrincipal noPermPrincipal = new CustomUserPrincipal(noPermUser);

        assertThatThrownBy(() -> standingOrderService.cancel("so-001", noPermPrincipal))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void cancel_shouldThrow_whenOwnershipFails() {
        StandingOrderEntity entity = new StandingOrderEntity();
        entity.setStandingOrderId("so-001");
        entity.setSourceAccountId(1001L);
        entity.setStatus(StandingOrderStatus.ACTIVE);
        entity.setNextRunDate(LocalDateTime.now().plusDays(5));
        when(standingOrderRepository.findById("so-001")).thenReturn(Optional.of(entity));
        doThrow(new OwnershipException("Not owner"))
                .when(ownershipValidator).assertOwnership(eq(1001L), any());

        assertThatThrownBy(() -> standingOrderService.cancel("so-001", customerPrincipal))
                .isInstanceOf(OwnershipException.class);
    }

    @Test
    void cancel_shouldSucceed_whenNextRunDateIsNull() {
        StandingOrderEntity entity = new StandingOrderEntity();
        entity.setStandingOrderId("so-001");
        entity.setSourceAccountId(1001L);
        entity.setStatus(StandingOrderStatus.ACTIVE);
        entity.setNextRunDate(null); // No next run date — should not be locked
        when(standingOrderRepository.findById("so-001")).thenReturn(Optional.of(entity));
        when(standingOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CancelStandingOrderResponse result = standingOrderService.cancel("so-001", customerPrincipal);
        assertThat(result.getStatus()).isEqualTo("CANCELLED");
    }
}
