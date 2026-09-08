package com.group1.banking.service;

import tools.jackson.databind.json.JsonMapper;
import com.group1.banking.dto.customer.MonetaryRequest;
import com.group1.banking.dto.customer.OperationResult;
import com.group1.banking.dto.customer.TransferRequest;
import com.group1.banking.entity.*;
import com.group1.banking.enums.RoleName;
import com.group1.banking.repository.AccountRepository;
import com.group1.banking.repository.IdempotencyRecordRepository;
import com.group1.banking.repository.TransactionRepository;
import com.group1.banking.repository.UserRepository;
import com.group1.banking.security.CustomUserPrincipal;
import com.group1.banking.service.AuditService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import com.group1.banking.service.impl.MonetaryOperationService;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.junit.jupiter.api.AfterEach;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MonetaryOperationServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Mock
    private AuthService authorizationService;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private MonetaryOperationService monetaryOperationService;
    @Mock
    private AuditService auditService;

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    private UUID userId;
    private User customerUser;
    private User adminUser;
    private Customer customer;
    private Account account;
    private Account toAccount;

        @BeforeEach
        void setUp() {
        // Inject real ObjectMapper - since @InjectMocks won't pick it up properly with @Mock,
        // we need to create service manually or inject via reflection
            monetaryOperationService = new MonetaryOperationService(
                accountRepository, transactionRepository, idempotencyRecordRepository,
                authorizationService, objectMapper, userRepository, auditService);

        userId = UUID.randomUUID();

        customerUser = new User();
        customerUser.setUserId(userId);
        customerUser.setCustomerId(42L);
        customerUser.setRoles(List.of(RoleName.RETAIL_CUSTOMER));
        customerUser.setActive(true);

        adminUser = new User();
        adminUser.setUserId(UUID.randomUUID());
        adminUser.setCustomerId(99L);
        adminUser.setRoles(List.of(RoleName.BANK_ADMINISTRATOR));
        adminUser.setActive(true);

        customer = new Customer();
        customer.setCustomerId(42L);

        account = new Account();
        account.setAccountId(1001L);
        account.setCustomer(customer);
        account.setAccountType(AccountType.CHECKING);
        account.setStatus(AccountStatus.ACTIVE);
        account.setBalance(new BigDecimal("100.00"));
        account.setAccountNumber("ACC0000001001");
        account.setDailyTransferLimit(new BigDecimal("3000.00"));

        Customer toCustomer = new Customer();
        toCustomer.setCustomerId(55L);
        toAccount = new Account();
        toAccount.setAccountId(2002L);
        toAccount.setCustomer(toCustomer);
        toAccount.setAccountType(AccountType.CHECKING);
        toAccount.setStatus(AccountStatus.ACTIVE);
        toAccount.setBalance(new BigDecimal("50.00"));
        toAccount.setAccountNumber("ACC0000002002");
        toAccount.setDailyTransferLimit(new BigDecimal("3000.00"));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void setUpSecurityContextWith(User user) {
        CustomUserPrincipal principal = new CustomUserPrincipal(user);
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(principal);
        SecurityContext securityContext = mock(SecurityContext.class);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        SecurityContextHolder.setContext(securityContext);
    }

    private IdempotencyRecord buildIdempotencyRecord(int status, String body) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setStorageKey("key");
        record.setIdempotencyKey("idem-key");
        record.setResponseStatus(status);
        record.setResponseBody(body);
        record.setCallerUserId(userId.toString());
        record.setOperationType("DEPOSIT");
        return record;
    }

    // ===== DEPOSIT TESTS =====

    @Test
    void deposit_shouldReturnOk_whenValidOwnerDeposit() {
        setUpSecurityContextWith(customerUser);
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(account));
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(idempotencyRecordRepository.findByStorageKey(anyString())).thenReturn(Optional.empty());
        when(accountRepository.save(any())).thenReturn(account);
        when(transactionRepository.save(any())).thenReturn(new Transaction());
        when(idempotencyRecordRepository.save(any())).thenReturn(new IdempotencyRecord());

        MonetaryRequest request = new MonetaryRequest(new BigDecimal("25.00"), "Test deposit");
        OperationResult result = monetaryOperationService.deposit(1001L, request, "idem-key-1");

        assertThat(result.status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void deposit_shouldReturnBadRequest_whenIdempotencyKeyIsNull() {
        setUpSecurityContextWith(customerUser);

        MonetaryRequest request = new MonetaryRequest(new BigDecimal("25.00"), "Test deposit");
        OperationResult result = monetaryOperationService.deposit(1001L, request, null);

        assertThat(result.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void deposit_shouldReturnBadRequest_whenIdempotencyKeyIsBlank() {
        setUpSecurityContextWith(customerUser);

        MonetaryRequest request = new MonetaryRequest(new BigDecimal("25.00"), "Test deposit");
        OperationResult result = monetaryOperationService.deposit(1001L, request, "  ");

        assertThat(result.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void deposit_shouldReplayPreviousResult_whenIdempotencyKeyAlreadyUsed() throws Exception {
        setUpSecurityContextWith(customerUser);

        String replayBody = objectMapper.writeValueAsString(new com.group1.banking.dto.common.ErrorResponse("OK", "Already done", null));
        IdempotencyRecord existingRecord = buildIdempotencyRecord(200, replayBody);
        when(idempotencyRecordRepository.findById(anyString())).thenReturn(Optional.of(existingRecord));

        MonetaryRequest request = new MonetaryRequest(new BigDecimal("25.00"), "Test deposit");
        OperationResult result = monetaryOperationService.deposit(1001L, request, "existing-key");

        assertThat(result.status()).isEqualTo(HttpStatus.OK);
        verify(accountRepository, never()).save(any());
    }

    @Test
    void deposit_shouldReturnNotFound_whenAccountNotFound() {
        setUpSecurityContextWith(customerUser);
        when(idempotencyRecordRepository.findByStorageKey(anyString())).thenReturn(Optional.empty());
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(999L)).thenReturn(Optional.empty());
        when(idempotencyRecordRepository.save(any())).thenReturn(new IdempotencyRecord());

        MonetaryRequest request = new MonetaryRequest(new BigDecimal("25.00"), "Test deposit");
        OperationResult result = monetaryOperationService.deposit(999L, request, "idem-key-3");

        assertThat(result.status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deposit_shouldReturnUnauthorised_whenCustomerDoesNotOwnAccount() {
        setUpSecurityContextWith(customerUser);
        when(idempotencyRecordRepository.findByStorageKey(anyString())).thenReturn(Optional.empty());

        Customer otherCustomer = new Customer();
        otherCustomer.setCustomerId(999L);
        account.setCustomer(otherCustomer);
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(account));
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(idempotencyRecordRepository.save(any())).thenReturn(new IdempotencyRecord());

        MonetaryRequest request = new MonetaryRequest(new BigDecimal("25.00"), "Test deposit");
        OperationResult result = monetaryOperationService.deposit(1001L, request, "idem-key-4");

        assertThat(result.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void deposit_shouldReturnUnprocessable_whenAmountIsZero() {
        setUpSecurityContextWith(customerUser);
        when(idempotencyRecordRepository.findByStorageKey(anyString())).thenReturn(Optional.empty());
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(account));
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(idempotencyRecordRepository.save(any())).thenReturn(new IdempotencyRecord());

        MonetaryRequest request = new MonetaryRequest(BigDecimal.ZERO, "Test deposit");
        OperationResult result = monetaryOperationService.deposit(1001L, request, "idem-key-5");

        assertThat(result.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void deposit_shouldReturnUnprocessable_whenAmountIsNegative() {
        setUpSecurityContextWith(customerUser);
        when(idempotencyRecordRepository.findByStorageKey(anyString())).thenReturn(Optional.empty());
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(account));
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(idempotencyRecordRepository.save(any())).thenReturn(new IdempotencyRecord());

        MonetaryRequest request = new MonetaryRequest(new BigDecimal("-10.00"), "Test");
        OperationResult result = monetaryOperationService.deposit(1001L, request, "idem-key-6");

        assertThat(result.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void deposit_shouldReturnUnauthorised_whenPrincipalInvalid() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn("anonymous");
        SecurityContext securityContext = mock(SecurityContext.class);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        SecurityContextHolder.setContext(securityContext);

        MonetaryRequest request = new MonetaryRequest(new BigDecimal("25.00"), "Test");
        OperationResult result = monetaryOperationService.deposit(1001L, request, "key");

        assertThat(result.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void deposit_shouldSucceed_whenAdminDepositsToAnyAccount() {
        setUpSecurityContextWith(adminUser);
        when(idempotencyRecordRepository.findByStorageKey(anyString())).thenReturn(Optional.empty());
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(account));
        when(userRepository.findById(adminUser.getUserId())).thenReturn(Optional.of(adminUser));
        when(accountRepository.save(any())).thenReturn(account);
        when(transactionRepository.save(any())).thenReturn(new Transaction());
        when(idempotencyRecordRepository.save(any())).thenReturn(new IdempotencyRecord());

        MonetaryRequest request = new MonetaryRequest(new BigDecimal("50.00"), "Admin deposit");
        OperationResult result = monetaryOperationService.deposit(1001L, request, "admin-key");

        assertThat(result.status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void deposit_shouldSucceed_whenAccountIsFrozen() {
        account.setStatus(AccountStatus.FROZEN);
        setUpSecurityContextWith(customerUser);
        when(idempotencyRecordRepository.findByStorageKey(anyString())).thenReturn(Optional.empty());
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(account));
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(accountRepository.save(any())).thenReturn(account);
        when(transactionRepository.save(any())).thenReturn(new Transaction());
        when(idempotencyRecordRepository.save(any())).thenReturn(new IdempotencyRecord());

        MonetaryRequest request = new MonetaryRequest(new BigDecimal("25.00"), "Frozen deposit");
        OperationResult result = monetaryOperationService.deposit(1001L, request, "deposit-frozen-key");

        assertThat(result.status()).isEqualTo(HttpStatus.OK);
    }

    // ===== WITHDRAW TESTS =====

    @Test
    void withdraw_shouldReturnOk_whenSufficientBalance() {
        setUpSecurityContextWith(customerUser);
        when(idempotencyRecordRepository.findByStorageKey(anyString())).thenReturn(Optional.empty());
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(account));
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(accountRepository.save(any())).thenReturn(account);
        when(transactionRepository.save(any())).thenReturn(new Transaction());
        when(idempotencyRecordRepository.save(any())).thenReturn(new IdempotencyRecord());

        MonetaryRequest request = new MonetaryRequest(new BigDecimal("50.00"), "Test withdraw");
        OperationResult result = monetaryOperationService.withdraw(1001L, request, "w-key-1");

        assertThat(result.status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void withdraw_shouldReturnConflict_whenInsufficientFunds() {
        setUpSecurityContextWith(customerUser);
        when(idempotencyRecordRepository.findByStorageKey(anyString())).thenReturn(Optional.empty());
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(account));
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(transactionRepository.save(any())).thenReturn(new Transaction());
        when(idempotencyRecordRepository.save(any())).thenReturn(new IdempotencyRecord());

        MonetaryRequest request = new MonetaryRequest(new BigDecimal("500.00"), "Big withdraw");
        OperationResult result = monetaryOperationService.withdraw(1001L, request, "w-key-2");

        assertThat(result.status()).isEqualTo(HttpStatus.CONFLICT);
        verify(transactionRepository).save(argThat(t -> t.getStatus() == TransactionStatus.FAILED));
    }

    @Test
    void withdraw_shouldReturnBadRequest_whenIdempotencyKeyMissing() {
        setUpSecurityContextWith(customerUser);

        MonetaryRequest request = new MonetaryRequest(new BigDecimal("10.00"), "Test");
        OperationResult result = monetaryOperationService.withdraw(1001L, request, null);

        assertThat(result.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void withdraw_shouldReturnNotFound_whenAccountNotFound() {
        setUpSecurityContextWith(customerUser);
        when(idempotencyRecordRepository.findByStorageKey(anyString())).thenReturn(Optional.empty());
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(999L)).thenReturn(Optional.empty());
        when(idempotencyRecordRepository.save(any())).thenReturn(new IdempotencyRecord());

        MonetaryRequest request = new MonetaryRequest(new BigDecimal("10.00"), "Test");
        OperationResult result = monetaryOperationService.withdraw(999L, request, "w-key-3");

        assertThat(result.status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void withdraw_shouldReturnUnauthorised_whenNotOwner() {
        setUpSecurityContextWith(customerUser);
        when(idempotencyRecordRepository.findByStorageKey(anyString())).thenReturn(Optional.empty());

        Customer otherCustomer = new Customer();
        otherCustomer.setCustomerId(999L);
        account.setCustomer(otherCustomer);
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(account));
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(idempotencyRecordRepository.save(any())).thenReturn(new IdempotencyRecord());

        MonetaryRequest request = new MonetaryRequest(new BigDecimal("10.00"), "Test");
        OperationResult result = monetaryOperationService.withdraw(1001L, request, "w-key-4");

        assertThat(result.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void withdraw_shouldReturnUnprocessable_whenAmountIsNull() {
        setUpSecurityContextWith(customerUser);
        when(idempotencyRecordRepository.findByStorageKey(anyString())).thenReturn(Optional.empty());
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(account));
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(idempotencyRecordRepository.save(any())).thenReturn(new IdempotencyRecord());

        MonetaryRequest request = new MonetaryRequest(null, "Test");
        OperationResult result = monetaryOperationService.withdraw(1001L, request, "w-key-5");

        assertThat(result.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void withdraw_shouldReturnConflict_whenAccountIsFrozen() {
        account.setStatus(AccountStatus.FROZEN);
        setUpSecurityContextWith(customerUser);
        when(idempotencyRecordRepository.findByStorageKey(anyString())).thenReturn(Optional.empty());
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(account));
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(idempotencyRecordRepository.save(any())).thenReturn(new IdempotencyRecord());

        MonetaryRequest request = new MonetaryRequest(new BigDecimal("10.00"), "Test");
        OperationResult result = monetaryOperationService.withdraw(1001L, request, "w-key-frozen");

        assertThat(result.status()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ===== TRANSFER TESTS =====

    @Test
    void transfer_shouldReturnOk_whenValidTransfer() {
        setUpSecurityContextWith(customerUser);
        when(idempotencyRecordRepository.findByStorageKey(anyString())).thenReturn(Optional.empty());
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(account));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(2002L)).thenReturn(Optional.of(toAccount));
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(accountRepository.save(any())).thenReturn(account);
        when(transactionRepository.save(any())).thenReturn(new Transaction());
        when(idempotencyRecordRepository.save(any())).thenReturn(new IdempotencyRecord());

        TransferRequest request = new TransferRequest(1001L, 2002L, new BigDecimal("30.00"), "Transfer test");
        OperationResult result = monetaryOperationService.transfer(request, "t-key-1");

        assertThat(result.status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void transfer_shouldReturnConflict_whenInsufficientFunds() {
        setUpSecurityContextWith(customerUser);
        when(idempotencyRecordRepository.findByStorageKey(anyString())).thenReturn(Optional.empty());
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(account));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(2002L)).thenReturn(Optional.of(toAccount));
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(transactionRepository.save(any())).thenReturn(new Transaction());
        when(idempotencyRecordRepository.save(any())).thenReturn(new IdempotencyRecord());

        TransferRequest request = new TransferRequest(1001L, 2002L, new BigDecimal("500.00"), "Big transfer");
        OperationResult result = monetaryOperationService.transfer(request, "t-key-2");

        assertThat(result.status()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void transfer_shouldReturnBadRequest_whenIdempotencyKeyMissing() {
        setUpSecurityContextWith(customerUser);

        TransferRequest request = new TransferRequest(1001L, 2002L, new BigDecimal("30.00"), "Test");
        OperationResult result = monetaryOperationService.transfer(request, null);

        assertThat(result.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void transfer_shouldReturnUnprocessable_whenSameAccountIds() {
        setUpSecurityContextWith(customerUser);
        when(idempotencyRecordRepository.findByStorageKey(anyString())).thenReturn(Optional.empty());
        when(idempotencyRecordRepository.save(any())).thenReturn(new IdempotencyRecord());

        TransferRequest request = new TransferRequest(1001L, 1001L, new BigDecimal("30.00"), "Same");
        OperationResult result = monetaryOperationService.transfer(request, "t-key-3");

        assertThat(result.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void transfer_shouldReturnNotFound_whenSourceAccountNotFound() {
        setUpSecurityContextWith(customerUser);
        when(idempotencyRecordRepository.findByStorageKey(anyString())).thenReturn(Optional.empty());
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.empty());
        when(idempotencyRecordRepository.save(any())).thenReturn(new IdempotencyRecord());

        TransferRequest request = new TransferRequest(1001L, 2002L, new BigDecimal("30.00"), "Test");
        OperationResult result = monetaryOperationService.transfer(request, "t-key-4");

        assertThat(result.status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void transfer_shouldReturnNotFound_whenDestinationAccountNotFound() {
        setUpSecurityContextWith(customerUser);
        when(idempotencyRecordRepository.findByStorageKey(anyString())).thenReturn(Optional.empty());
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(account));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(2002L)).thenReturn(Optional.empty());
        when(idempotencyRecordRepository.save(any())).thenReturn(new IdempotencyRecord());

        TransferRequest request = new TransferRequest(1001L, 2002L, new BigDecimal("30.00"), "Test");
        OperationResult result = monetaryOperationService.transfer(request, "t-key-5");

        assertThat(result.status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void transfer_shouldReturnUnauthorised_whenNotOwnerOfSourceAccount() {
        setUpSecurityContextWith(customerUser);
        when(idempotencyRecordRepository.findByStorageKey(anyString())).thenReturn(Optional.empty());

        Customer otherCustomer = new Customer();
        otherCustomer.setCustomerId(999L);
        account.setCustomer(otherCustomer);
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(account));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(2002L)).thenReturn(Optional.of(toAccount));
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(idempotencyRecordRepository.save(any())).thenReturn(new IdempotencyRecord());

        TransferRequest request = new TransferRequest(1001L, 2002L, new BigDecimal("30.00"), "Test");
        OperationResult result = monetaryOperationService.transfer(request, "t-key-6");

        assertThat(result.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void transfer_shouldReturnConflict_whenSourceAccountIsFrozen() {
        account.setStatus(AccountStatus.FROZEN);
        setUpSecurityContextWith(customerUser);
        when(idempotencyRecordRepository.findByStorageKey(anyString())).thenReturn(Optional.empty());
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(account));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(2002L)).thenReturn(Optional.of(toAccount));
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(idempotencyRecordRepository.save(any())).thenReturn(new IdempotencyRecord());

        TransferRequest request = new TransferRequest(1001L, 2002L, new BigDecimal("30.00"), "Test");
        OperationResult result = monetaryOperationService.transfer(request, "t-frozen");

        assertThat(result.status()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void withdrawAndTransfer_shouldSucceedAfterUnfreeze() {
        account.setStatus(AccountStatus.ACTIVE);
        setUpSecurityContextWith(customerUser);
        when(idempotencyRecordRepository.findByStorageKey(anyString())).thenReturn(Optional.empty());
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(account));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(2002L)).thenReturn(Optional.of(toAccount));
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(accountRepository.save(any())).thenReturn(account);
        when(transactionRepository.save(any())).thenReturn(new Transaction());
        when(idempotencyRecordRepository.save(any())).thenReturn(new IdempotencyRecord());

        OperationResult withdrawResult = monetaryOperationService.withdraw(
                1001L,
                new MonetaryRequest(new BigDecimal("10.00"), "withdraw"),
                "w-after-unfreeze");

        OperationResult transferResult = monetaryOperationService.transfer(
                new TransferRequest(1001L, 2002L, new BigDecimal("5.00"), "transfer"),
                "t-after-unfreeze");

        assertThat(withdrawResult.status()).isEqualTo(HttpStatus.OK);
        assertThat(transferResult.status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void transfer_shouldReturnUnprocessable_whenAmountHasTooManyDecimals() {
        setUpSecurityContextWith(customerUser);
        when(idempotencyRecordRepository.findByStorageKey(anyString())).thenReturn(Optional.empty());
        when(idempotencyRecordRepository.save(any())).thenReturn(new IdempotencyRecord());

        TransferRequest request = new TransferRequest(1001L, 2002L, new BigDecimal("30.123"), "Test");
        OperationResult result = monetaryOperationService.transfer(request, "t-key-7");

        assertThat(result.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void transfer_shouldReturnBadRequest_whenRequestIsNull() {
        setUpSecurityContextWith(customerUser);
        when(idempotencyRecordRepository.findByStorageKey(anyString())).thenReturn(Optional.empty());
        when(idempotencyRecordRepository.save(any())).thenReturn(new IdempotencyRecord());

        OperationResult result = monetaryOperationService.transfer(null, "t-key-8");

        assertThat(result.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void transfer_shouldReturnUnauthorised_whenPrincipalIsNotCustomUserPrincipal() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn("anonymous");
        SecurityContext securityContext = mock(SecurityContext.class);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        SecurityContextHolder.setContext(securityContext);

        TransferRequest request = new TransferRequest(1001L, 2002L, new BigDecimal("30.00"), "Test");
        OperationResult result = monetaryOperationService.transfer(request, "t-key-9");

        assertThat(result.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void transfer_shouldReturnBadRequest_whenFromAccountIdIsNull() {
        setUpSecurityContextWith(customerUser);
        when(idempotencyRecordRepository.findByStorageKey(anyString())).thenReturn(Optional.empty());
        when(idempotencyRecordRepository.save(any())).thenReturn(new IdempotencyRecord());

        TransferRequest request = new TransferRequest(null, 2002L, new BigDecimal("30.00"), "Test");
        OperationResult result = monetaryOperationService.transfer(request, "t-key-10");

        assertThat(result.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void transfer_shouldReturnBadRequest_whenToAccountIdIsNull() {
        setUpSecurityContextWith(customerUser);
        when(idempotencyRecordRepository.findByStorageKey(anyString())).thenReturn(Optional.empty());
        when(idempotencyRecordRepository.save(any())).thenReturn(new IdempotencyRecord());

        TransferRequest request = new TransferRequest(1001L, null, new BigDecimal("30.00"), "Test");
        OperationResult result = monetaryOperationService.transfer(request, "t-key-11");

        assertThat(result.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
