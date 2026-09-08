package com.group1.banking.service;

import com.group1.banking.dto.customer.AccountResponse;
import com.group1.banking.dto.customer.CreateAccountRequest;
import com.group1.banking.dto.customer.UpdateAccountRequest;
import com.group1.banking.dto.accountcontrol.FreezeAccountRequest;
import com.group1.banking.dto.accountcontrol.UnfreezeAccountRequest;
import com.group1.banking.entity.*;
import com.group1.banking.enums.RoleName;
import com.group1.banking.exception.BadRequestException;
import com.group1.banking.exception.ConflictException;
import com.group1.banking.exception.NotFoundException;
import com.group1.banking.exception.UnauthorisedException;
import com.group1.banking.exception.UnprocessableException;
import com.group1.banking.repository.AccountRepository;
import com.group1.banking.repository.CustomerRepository;
import com.group1.banking.repository.GicRepository;
import com.group1.banking.repository.UserRepository;
import com.group1.banking.security.CustomUserPrincipal;
import com.group1.banking.service.impl.AccountService;
import com.group1.banking.service.AccountControlAuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

// ...existing test methods and code...
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private AuthService authorizationService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private GicRepository gicRepository;

    @Mock
    private AccountControlAuditService accountControlAuditService;

    @InjectMocks
    private AccountService accountService;

    private UUID userId;
    private User customerUser;
    private User adminUser;
    private Customer customer;
    private Account account;

    @BeforeEach
    void setUp() {
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
        customer.setName("Jane Doe");

        account = new Account();
        account.setAccountId(1001L);
        account.setAccountNumber("ACC0000001001");
        account.setCustomer(customer);
        account.setAccountType(AccountType.CHECKING);
        account.setStatus(AccountStatus.ACTIVE);
        account.setBalance(new BigDecimal("100.00"));
        account.setDailyTransferLimit(new BigDecimal("3000.00"));
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

    // ===== createAccount TESTS =====

    @Test
    void createAccount_shouldSucceed_whenCustomerOwnsCHECKING() {
        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(customerRepository.findByCustomerIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(customer));
        when(accountRepository.count()).thenReturn(0L);
        when(accountRepository.save(any(Account.class))).thenReturn(account);

        CreateAccountRequest request = new CreateAccountRequest(
                AccountType.CHECKING, new BigDecimal("100.00"), null);

        AccountResponse result = accountService.createAccount(42L, request);

        assertThat(result).isNotNull();
        verify(accountRepository).save(any(Account.class));
    }

    @Test
    void createAccount_shouldSucceed_whenCustomerOwnsSAVINGS() {
        account.setAccountType(AccountType.SAVINGS);
        account.setInterestRate(new BigDecimal("1.5000"));
        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(customerRepository.findByCustomerIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(customer));
        when(accountRepository.count()).thenReturn(0L);
        when(accountRepository.save(any(Account.class))).thenReturn(account);

        CreateAccountRequest request = new CreateAccountRequest(
                AccountType.SAVINGS, new BigDecimal("200.00"), new BigDecimal("1.5000"));

        AccountResponse result = accountService.createAccount(42L, request);

        assertThat(result).isNotNull();
    }

    @Test
    void createAccount_shouldSucceed_whenAdminCreatesForOtherCustomer() {
        setUpSecurityContextWith(adminUser);
        when(userRepository.findById(adminUser.getUserId())).thenReturn(Optional.of(adminUser));
        when(customerRepository.findByCustomerIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(customer));
        when(accountRepository.count()).thenReturn(0L);
        when(accountRepository.save(any(Account.class))).thenReturn(account);

        CreateAccountRequest request = new CreateAccountRequest(
                AccountType.CHECKING, new BigDecimal("500.00"), null);

        AccountResponse result = accountService.createAccount(42L, request);
        assertThat(result).isNotNull();
    }

    @Test
    void createAccount_shouldThrowNotFoundException_whenCustomerNotFound() {
        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(customerRepository.findByCustomerIdAndDeletedAtIsNull(42L)).thenReturn(Optional.empty());

        CreateAccountRequest request = new CreateAccountRequest(
                AccountType.CHECKING, new BigDecimal("100.00"), null);

        assertThatThrownBy(() -> accountService.createAccount(42L, request))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void createAccount_shouldThrowUnauthorised_whenCustomerDoesNotOwn() {
        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));

        Customer otherCustomer = new Customer();
        otherCustomer.setCustomerId(999L);
        when(customerRepository.findByCustomerIdAndDeletedAtIsNull(999L)).thenReturn(Optional.of(otherCustomer));

        CreateAccountRequest request = new CreateAccountRequest(
                AccountType.CHECKING, new BigDecimal("100.00"), null);

        assertThatThrownBy(() -> accountService.createAccount(999L, request))
                .isInstanceOf(UnauthorisedException.class);
    }

    @Test
    void createAccount_shouldThrowUnprocessable_whenSavingsLacksInterestRate() {
        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(customerRepository.findByCustomerIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(customer));

        CreateAccountRequest request = new CreateAccountRequest(
                AccountType.SAVINGS, new BigDecimal("100.00"), null);

        assertThatThrownBy(() -> accountService.createAccount(42L, request))
                .isInstanceOf(UnprocessableException.class);
    }

    @Test
    void createAccount_shouldThrowUnprocessable_whenCheckingHasInterestRate() {
        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(customerRepository.findByCustomerIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(customer));

        CreateAccountRequest request = new CreateAccountRequest(
                AccountType.CHECKING, new BigDecimal("100.00"), new BigDecimal("1.00"));

        assertThatThrownBy(() -> accountService.createAccount(42L, request))
                .isInstanceOf(UnprocessableException.class);
    }

    @Test
    void createAccount_shouldThrowUnauthorised_whenPrincipalIsNotCustomUserPrincipal() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn("anonymous");
        SecurityContext securityContext = mock(SecurityContext.class);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        SecurityContextHolder.setContext(securityContext);

        CreateAccountRequest request = new CreateAccountRequest(
                AccountType.CHECKING, new BigDecimal("100.00"), null);

        assertThatThrownBy(() -> accountService.createAccount(42L, request))
                .isInstanceOf(UnauthorisedException.class);
    }

    // ===== getAccount TESTS =====

    @Test
    void getAccount_shouldReturnAccount_whenOwnerAccesses() {
        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(account));

        AccountResponse result = accountService.getAccount(1001L);

        assertThat(result).isNotNull();
        assertThat(result.accountId()).isEqualTo(1001L);
    }

    @Test
    void getAccount_shouldReturnAccount_whenAdminAccesses() {
        setUpSecurityContextWith(adminUser);
        when(userRepository.findById(adminUser.getUserId())).thenReturn(Optional.of(adminUser));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(account));

        AccountResponse result = accountService.getAccount(1001L);
        assertThat(result).isNotNull();
    }

    @Test
    void getAccount_shouldThrowNotFoundException_whenAccountNotFound() {
        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.getAccount(999L))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void getAccount_shouldThrowUnauthorised_whenCustomerDoesNotOwnAccount() {
        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));

        Customer otherCustomer = new Customer();
        otherCustomer.setCustomerId(999L);
        account.setCustomer(otherCustomer);

        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> accountService.getAccount(1001L))
                .isInstanceOf(UnauthorisedException.class);
    }

    // ===== listCustomerAccounts TESTS =====

    @Test
    void listCustomerAccounts_shouldReturnList_whenOwnerAccesses() {
        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(customerRepository.existsByCustomerIdAndDeletedAtIsNull(42L)).thenReturn(true);
        when(accountRepository.findAllByCustomerCustomerIdAndDeletedAtIsNullAndStatus(42L, AccountStatus.ACTIVE))
                .thenReturn(List.of(account));

        List<AccountResponse> result = accountService.listCustomerAccounts(42L);

        assertThat(result).hasSize(1);
    }

    @Test
    void listCustomerAccounts_shouldThrowNotFoundException_whenCustomerNotFound() {
        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(customerRepository.existsByCustomerIdAndDeletedAtIsNull(99L)).thenReturn(false);

        assertThatThrownBy(() -> accountService.listCustomerAccounts(99L))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void listCustomerAccounts_shouldThrowUnauthorised_whenCustomerDoesNotOwn() {
        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(customerRepository.existsByCustomerIdAndDeletedAtIsNull(999L)).thenReturn(true);

        assertThatThrownBy(() -> accountService.listCustomerAccounts(999L))
                .isInstanceOf(UnauthorisedException.class);
    }

    // ===== updateAccount TESTS =====

    @Test
    void updateAccount_shouldUpdateInterestRate_whenOwnerUpdatesSAVINGS() {
        account.setAccountType(AccountType.SAVINGS);
        account.setInterestRate(new BigDecimal("1.0000"));

        Account savedAccount = new Account();
        savedAccount.setAccountId(1001L);
        savedAccount.setCustomer(customer);
        savedAccount.setAccountType(AccountType.SAVINGS);
        savedAccount.setStatus(AccountStatus.ACTIVE);
        savedAccount.setBalance(new BigDecimal("100.00"));
        savedAccount.setInterestRate(new BigDecimal("2.5000"));

        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(account));
        when(accountRepository.save(any(Account.class))).thenReturn(savedAccount);

        UpdateAccountRequest request = new UpdateAccountRequest(new BigDecimal("2.5000"));
        AccountResponse result = accountService.updateAccount(1001L, request);

        assertThat(result).isNotNull();
        verify(accountRepository).save(any(Account.class));
    }

    @Test
    void updateAccount_shouldThrowUnprocessable_whenCheckingAccountUpdated() {
        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(account));

        UpdateAccountRequest request = new UpdateAccountRequest(new BigDecimal("1.0000"));
        assertThatThrownBy(() -> accountService.updateAccount(1001L, request))
                .isInstanceOf(UnprocessableException.class);
    }

    @Test
    void updateAccount_shouldThrowUnprocessable_whenRequestIsEmpty() {
        account.setAccountType(AccountType.SAVINGS);
        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(account));

        UpdateAccountRequest emptyRequest = new UpdateAccountRequest(null);
        assertThatThrownBy(() -> accountService.updateAccount(1001L, emptyRequest))
                .isInstanceOf(UnprocessableException.class);
    }

    @Test
    void updateAccount_shouldThrowUnauthorised_whenNotOwner() {
        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));

        Customer otherCustomer = new Customer();
        otherCustomer.setCustomerId(999L);
        account.setCustomer(otherCustomer);

        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(account));

        UpdateAccountRequest request = new UpdateAccountRequest(new BigDecimal("1.0000"));
        assertThatThrownBy(() -> accountService.updateAccount(1001L, request))
                .isInstanceOf(UnauthorisedException.class);
    }

    // ===== deleteAccount TESTS =====

    @Test
    void deleteAccount_shouldSucceed_whenBalanceIsZero() {
        account.setBalance(new BigDecimal("0.00"));
        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(account));
        when(accountRepository.save(any(Account.class))).thenReturn(account);

        accountService.deleteAccount(1001L);

        verify(accountRepository).save(argThat(a -> a.getStatus() == AccountStatus.CLOSED));
    }

    @Test
    void deleteAccount_shouldThrowConflict_whenBalanceIsNonZero() {
        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> accountService.deleteAccount(1001L))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void deleteAccount_shouldThrowNotFoundException_whenAccountNotFound() {
        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.deleteAccount(999L))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void deleteAccount_shouldThrowUnauthorised_whenNotOwner() {
        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));

        Customer otherCustomer = new Customer();
        otherCustomer.setCustomerId(999L);
        account.setCustomer(otherCustomer);
        account.setBalance(new BigDecimal("0.00"));

        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> accountService.deleteAccount(1001L))
                .isInstanceOf(UnauthorisedException.class);
    }

    @Test
    void deleteAccount_shouldSucceed_whenAdminDeletesAnyAccount() {
        account.setBalance(new BigDecimal("0.00"));
        setUpSecurityContextWith(adminUser);
        when(userRepository.findById(adminUser.getUserId())).thenReturn(Optional.of(adminUser));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(account));
        when(accountRepository.save(any(Account.class))).thenReturn(account);

        accountService.deleteAccount(1001L);

        verify(accountRepository).save(argThat(a -> a.getStatus() == AccountStatus.CLOSED));
    }

    // ===== closeRrspAccount TESTS =====

    @Test
    void closeRrspAccount_shouldSucceed_whenRrspWithZeroBalanceAndNoActiveGic() {
        account.setAccountType(AccountType.RRSP);
        account.setBalance(new BigDecimal("0.00"));
        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(account));
        when(gicRepository.existsByAccount_AccountIdAndDeletedAtIsNullAndStatus(1001L, GicStatus.ACTIVE)).thenReturn(false);
        when(accountRepository.save(any(Account.class))).thenReturn(account);

        var result = accountService.closeRrspAccount(1001L);

        assertThat(result).containsKey("message");
        verify(accountRepository).save(argThat(a -> a.getStatus() == AccountStatus.CLOSED));
    }

    @Test
    void closeRrspAccount_shouldThrowBadRequest_whenNotRrspAccount() {
        account.setAccountType(AccountType.CHECKING);
        account.setBalance(new BigDecimal("0.00"));
        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> accountService.closeRrspAccount(1001L))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void closeRrspAccount_shouldThrowBadRequest_whenActiveGicExists() {
        account.setAccountType(AccountType.RRSP);
        account.setBalance(new BigDecimal("0.00"));
        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(account));
        when(gicRepository.existsByAccount_AccountIdAndDeletedAtIsNullAndStatus(1001L, GicStatus.ACTIVE)).thenReturn(true);

        assertThatThrownBy(() -> accountService.closeRrspAccount(1001L))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void closeRrspAccount_shouldThrowBadRequest_whenNonZeroBalance() {
        account.setAccountType(AccountType.RRSP);
        account.setBalance(new BigDecimal("100.00"));
        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(account));
        when(gicRepository.existsByAccount_AccountIdAndDeletedAtIsNullAndStatus(1001L, GicStatus.ACTIVE)).thenReturn(false);

        assertThatThrownBy(() -> accountService.closeRrspAccount(1001L))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void closeRrspAccount_shouldThrowNotFoundException_whenAccountNotFound() {
        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.closeRrspAccount(999L))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void freezeAccount_shouldSucceed_whenAdminAndActiveAccount() {
        setUpSecurityContextWith(adminUser);
        when(userRepository.findById(adminUser.getUserId())).thenReturn(Optional.of(adminUser));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(account));
        when(accountRepository.save(any(Account.class))).thenReturn(account);

        var response = accountService.freezeAccount(1001L, new FreezeAccountRequest("Fraud review", "SECURITY", null));

        assertThat(response.newStatus()).isEqualTo(AccountStatus.FROZEN);
        verify(accountControlAuditService).logEvent(eq(1001L), anyString(), anyString(), any(), eq(AccountStatus.ACTIVE), eq(AccountStatus.FROZEN), eq("Fraud review"), isNull());
    }

    @Test
    void freezeAccount_shouldRejectMissingReason() {
        setUpSecurityContextWith(adminUser);
        when(userRepository.findById(adminUser.getUserId())).thenReturn(Optional.of(adminUser));

        assertThatThrownBy(() -> accountService.freezeAccount(1001L, new FreezeAccountRequest("", null, null)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void freezeAccount_shouldRejectAlreadyFrozen() {
        account.setStatus(AccountStatus.FROZEN);
        setUpSecurityContextWith(adminUser);
        when(userRepository.findById(adminUser.getUserId())).thenReturn(Optional.of(adminUser));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> accountService.freezeAccount(1001L, new FreezeAccountRequest("Fraud", null, null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void freezeAccount_shouldThrowNotFound_whenAccountDoesNotExist() {
        setUpSecurityContextWith(adminUser);
        when(userRepository.findById(adminUser.getUserId())).thenReturn(Optional.of(adminUser));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.freezeAccount(999L, new FreezeAccountRequest("Fraud", null, null)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void unfreezeAccount_shouldRejectAlreadyActive() {
        setUpSecurityContextWith(adminUser);
        when(userRepository.findById(adminUser.getUserId())).thenReturn(Optional.of(adminUser));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> accountService.unfreezeAccount(1001L, new UnfreezeAccountRequest("done", null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void unfreezeAccount_shouldSucceed_whenFrozen() {
        account.setStatus(AccountStatus.FROZEN);
        setUpSecurityContextWith(adminUser);
        when(userRepository.findById(adminUser.getUserId())).thenReturn(Optional.of(adminUser));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(account));
        when(accountRepository.save(any(Account.class))).thenReturn(account);

        var response = accountService.unfreezeAccount(1001L, new UnfreezeAccountRequest("case closed", "note"));

        assertThat(response.newStatus()).isEqualTo(AccountStatus.ACTIVE);
        verify(accountControlAuditService).logEvent(eq(1001L), anyString(), anyString(), any(), eq(AccountStatus.FROZEN), eq(AccountStatus.ACTIVE), eq("case closed"), eq("note"));
    }

    @Test
    void unfreezeAccount_shouldThrowNotFound_whenAccountDoesNotExist() {
        setUpSecurityContextWith(adminUser);
        when(userRepository.findById(adminUser.getUserId())).thenReturn(Optional.of(adminUser));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.unfreezeAccount(999L, new UnfreezeAccountRequest("ok", null)))
                .isInstanceOf(NotFoundException.class);
    }
}

