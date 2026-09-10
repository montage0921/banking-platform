package com.group1.banking.service;

import com.group1.banking.dto.gic.CreateGicRequest;
import com.group1.banking.dto.gic.GicResponse;
import com.group1.banking.dto.gic.RedeemGicResponse;
import com.group1.banking.entity.Account;
import com.group1.banking.entity.AccountStatus;
import com.group1.banking.entity.AccountType;
import com.group1.banking.entity.Customer;
import com.group1.banking.entity.GicInvestment;
import com.group1.banking.entity.GicStatus;
import com.group1.banking.entity.GicTerm;
import com.group1.banking.entity.User;
import com.group1.banking.enums.RoleName;
import com.group1.banking.exception.BadRequestException;
import com.group1.banking.exception.NotFoundException;
import com.group1.banking.exception.UnauthorisedException;
import com.group1.banking.repository.AccountRepository;
import com.group1.banking.repository.GicRepository;
import com.group1.banking.repository.UserRepository;
import com.group1.banking.security.CustomUserPrincipal;
import com.group1.banking.service.AuditService;
import com.group1.banking.service.impl.GicService;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GicServiceTest {

    @Mock
    private GicRepository gicRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuditService auditService;

    @InjectMocks
    private GicService gicService;

    private UUID userId;
    private User customerUser;
    private User adminUser;
    private Customer customer;
    private Account rrspAccount;
    private GicInvestment activeGic;

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

        rrspAccount = new Account();
        rrspAccount.setAccountId(1001L);
        rrspAccount.setAccountNumber("RRSP-001");
        rrspAccount.setCustomer(customer);
        rrspAccount.setAccountType(AccountType.RRSP);
        rrspAccount.setStatus(AccountStatus.ACTIVE);
        rrspAccount.setBalance(new BigDecimal("5000.00"));

        activeGic = new GicInvestment();
        activeGic.setGicId("GIC-TESTID001");
        activeGic.setAccount(rrspAccount);
        activeGic.setPrincipalAmount(new BigDecimal("1000.00"));
        activeGic.setInterestRate(GicTerm.ONE_YEAR.getAnnualRate());
        activeGic.setTerm(GicTerm.ONE_YEAR);
        activeGic.setStartDate(LocalDate.now());
        activeGic.setMaturityDate(LocalDate.now().plusYears(1));
        activeGic.setMaturityAmount(new BigDecimal("1050.00"));
        activeGic.setStatus(GicStatus.ACTIVE);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void setUpSecurityContextWith(User user) {
        CustomUserPrincipal principal = new CustomUserPrincipal(user);
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(principal);
        SecurityContext secCtx = mock(SecurityContext.class);
        when(secCtx.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(secCtx);
    }

    // ===== createGic TESTS =====

    @Test
    void createGic_shouldSucceed_whenValidRequest() {
        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(rrspAccount));
        when(accountRepository.save(any(Account.class))).thenReturn(rrspAccount);
        when(gicRepository.save(any(GicInvestment.class))).thenReturn(activeGic);

        CreateGicRequest request = new CreateGicRequest(new BigDecimal("1000.00"), GicTerm.ONE_YEAR);

        GicResponse result = gicService.createGic(1001L, request);

        assertThat(result).isNotNull();
        assertThat(result.accountId()).isEqualTo(1001L);
        verify(accountRepository).save(any(Account.class));
        verify(gicRepository).save(any(GicInvestment.class));
    }

    @Test
    void createGic_shouldThrowNotFoundException_whenAccountNotFound() {
        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(999L)).thenReturn(Optional.empty());

        CreateGicRequest request = new CreateGicRequest(new BigDecimal("1000.00"), GicTerm.ONE_YEAR);

        assertThatThrownBy(() -> gicService.createGic(999L, request))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void createGic_shouldThrowBadRequest_whenAccountIsNotRrsp() {
        rrspAccount.setAccountType(AccountType.CHECKING);
        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(rrspAccount));

        CreateGicRequest request = new CreateGicRequest(new BigDecimal("1000.00"), GicTerm.ONE_YEAR);

        assertThatThrownBy(() -> gicService.createGic(1001L, request))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void createGic_shouldThrowUnauthorised_whenCustomerDoesNotOwnAccount() {
        Customer otherCustomer = new Customer();
        otherCustomer.setCustomerId(999L);
        rrspAccount.setCustomer(otherCustomer);

        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(rrspAccount));

        CreateGicRequest request = new CreateGicRequest(new BigDecimal("1000.00"), GicTerm.ONE_YEAR);

        assertThatThrownBy(() -> gicService.createGic(1001L, request))
                .isInstanceOf(UnauthorisedException.class);
    }

    @Test
    void createGic_shouldThrowBadRequest_whenInsufficientFunds() {
        rrspAccount.setBalance(new BigDecimal("100.00"));
        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(rrspAccount));

        CreateGicRequest request = new CreateGicRequest(new BigDecimal("500.00"), GicTerm.ONE_YEAR);

        assertThatThrownBy(() -> gicService.createGic(1001L, request))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void createGic_shouldThrowUnauthorised_whenPrincipalNotCustomUserPrincipal() {
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn("anonymous");
        SecurityContext secCtx = mock(SecurityContext.class);
        when(secCtx.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(secCtx);

        CreateGicRequest request = new CreateGicRequest(new BigDecimal("1000.00"), GicTerm.ONE_YEAR);

        assertThatThrownBy(() -> gicService.createGic(1001L, request))
                .isInstanceOf(UnauthorisedException.class);
    }

    @Test
    void createGic_shouldSucceed_whenAdminCreatesForOtherCustomer() {
        setUpSecurityContextWith(adminUser);
        when(userRepository.findById(adminUser.getUserId())).thenReturn(Optional.of(adminUser));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(rrspAccount));
        when(accountRepository.save(any(Account.class))).thenReturn(rrspAccount);
        when(gicRepository.save(any(GicInvestment.class))).thenReturn(activeGic);

        CreateGicRequest request = new CreateGicRequest(new BigDecimal("1000.00"), GicTerm.ONE_YEAR);

        GicResponse result = gicService.createGic(1001L, request);

        assertThat(result).isNotNull();
    }

    @Test
    void createGic_shouldDeductBalanceFromRrspAccount() {
        BigDecimal initialBalance = new BigDecimal("5000.00");
        BigDecimal amount = new BigDecimal("1000.00");
        rrspAccount.setBalance(initialBalance);

        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(rrspAccount));
        when(accountRepository.save(any(Account.class))).thenReturn(rrspAccount);
        when(gicRepository.save(any(GicInvestment.class))).thenReturn(activeGic);

        gicService.createGic(1001L, new CreateGicRequest(amount, GicTerm.ONE_YEAR));

        verify(accountRepository).save(argThat(a ->
                a.getBalance().compareTo(initialBalance.subtract(amount)) == 0));
    }

    // ===== getGics TESTS =====

    @Test
    void getGics_shouldReturnList_whenGicsExist() {
        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(rrspAccount));
        when(gicRepository.findAllByAccount_AccountIdAndDeletedAtIsNull(1001L)).thenReturn(List.of(activeGic));

        List<GicResponse> result = gicService.getGics(1001L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).gicId()).isEqualTo("GIC-TESTID001");
    }

    @Test
    void getGics_shouldReturnEmptyList_whenNoGics() {
        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(rrspAccount));
        when(gicRepository.findAllByAccount_AccountIdAndDeletedAtIsNull(1001L)).thenReturn(List.of());

        List<GicResponse> result = gicService.getGics(1001L);

        assertThat(result).isEmpty();
    }

    @Test
    void getGics_shouldReturnMultipleGics_whenSeveralExist() {
        GicInvestment gic2 = new GicInvestment();
        gic2.setGicId("GIC-TESTID002");
        gic2.setAccount(rrspAccount);
        gic2.setPrincipalAmount(new BigDecimal("2000.00"));
        gic2.setInterestRate(GicTerm.TWO_YEARS.getAnnualRate());
        gic2.setTerm(GicTerm.TWO_YEARS);
        gic2.setStartDate(LocalDate.now());
        gic2.setMaturityDate(LocalDate.now().plusYears(2));
        gic2.setMaturityAmount(new BigDecimal("2220.00"));
        gic2.setStatus(GicStatus.ACTIVE);

        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(rrspAccount));
        when(gicRepository.findAllByAccount_AccountIdAndDeletedAtIsNull(1001L)).thenReturn(List.of(activeGic, gic2));

        List<GicResponse> result = gicService.getGics(1001L);

        assertThat(result).hasSize(2);
    }

    @Test
    void getGics_shouldThrowNotFoundException_whenAccountNotFound() {
        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> gicService.getGics(999L))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void getGics_shouldThrowUnauthorised_whenNotOwner() {
        Customer otherCustomer = new Customer();
        otherCustomer.setCustomerId(999L);
        rrspAccount.setCustomer(otherCustomer);

        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(rrspAccount));

        assertThatThrownBy(() -> gicService.getGics(1001L))
                .isInstanceOf(UnauthorisedException.class);
    }

    // ===== redeemGic TESTS =====

    @Test
    void redeemGic_shouldSucceed_whenActiveGicExists() {
        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(rrspAccount));
        when(gicRepository.findByGicIdAndAccount_AccountIdAndDeletedAtIsNull("GIC-TESTID001", 1001L))
                .thenReturn(Optional.of(activeGic));
        when(accountRepository.save(any(Account.class))).thenReturn(rrspAccount);
        when(gicRepository.save(any(GicInvestment.class))).thenReturn(activeGic);

        RedeemGicResponse result = gicService.redeemGic(1001L, "GIC-TESTID001");

        assertThat(result).isNotNull();
        assertThat(result.payoutAmount()).isEqualByComparingTo(new BigDecimal("1050.00"));
        verify(gicRepository).save(argThat(g -> g.getStatus() == GicStatus.REDEEMED));
        verify(gicRepository).save(argThat(g -> g.getDeletedAt() != null));
    }

    @Test
    void redeemGic_shouldCreditPayoutToRrspBalance() {
        BigDecimal initialBalance = new BigDecimal("500.00");
        rrspAccount.setBalance(initialBalance);

        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(rrspAccount));
        when(gicRepository.findByGicIdAndAccount_AccountIdAndDeletedAtIsNull("GIC-TESTID001", 1001L))
                .thenReturn(Optional.of(activeGic));
        when(accountRepository.save(any(Account.class))).thenReturn(rrspAccount);
        when(gicRepository.save(any(GicInvestment.class))).thenReturn(activeGic);

        gicService.redeemGic(1001L, "GIC-TESTID001");

        verify(accountRepository).save(argThat(a ->
                a.getBalance().compareTo(initialBalance.add(new BigDecimal("1050.00"))) == 0));
    }

    @Test
    void redeemGic_shouldThrowNotFoundException_whenGicNotFound() {
        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(rrspAccount));
        when(gicRepository.findByGicIdAndAccount_AccountIdAndDeletedAtIsNull("NONEXISTENT", 1001L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> gicService.redeemGic(1001L, "NONEXISTENT"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void redeemGic_shouldThrowBadRequest_whenGicAlreadyRedeemed() {
        activeGic.setStatus(GicStatus.REDEEMED);
        activeGic.setDeletedAt(Instant.now());

        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(rrspAccount));
        when(gicRepository.findByGicIdAndAccount_AccountIdAndDeletedAtIsNull("GIC-TESTID001", 1001L))
                .thenReturn(Optional.of(activeGic));

        assertThatThrownBy(() -> gicService.redeemGic(1001L, "GIC-TESTID001"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void redeemGic_shouldThrowUnauthorised_whenNotOwner() {
        Customer otherCustomer = new Customer();
        otherCustomer.setCustomerId(999L);
        rrspAccount.setCustomer(otherCustomer);

        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(1001L)).thenReturn(Optional.of(rrspAccount));

        assertThatThrownBy(() -> gicService.redeemGic(1001L, "GIC-TESTID001"))
                .isInstanceOf(UnauthorisedException.class);
    }

    @Test
    void redeemGic_shouldThrowNotFoundException_whenAccountNotFound() {
        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(accountRepository.findByAccountIdAndDeletedAtIsNull(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> gicService.redeemGic(999L, "GIC-TESTID001"))
                .isInstanceOf(NotFoundException.class);
    }
}
