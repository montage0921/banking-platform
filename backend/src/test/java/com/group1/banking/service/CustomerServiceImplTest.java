package com.group1.banking.service;

import com.group1.banking.dto.customer.CreateCustomerRequest;
import com.group1.banking.dto.customer.CustomerResponse;
import com.group1.banking.dto.customer.PatchCustomerRequest;
import com.group1.banking.entity.Account;
import com.group1.banking.entity.AccountStatus;
import com.group1.banking.entity.Customer;
import com.group1.banking.entity.User;
import com.group1.banking.enums.CustomerType;
import com.group1.banking.enums.RoleName;
import com.group1.banking.exception.BadRequestException;
import com.group1.banking.exception.ConflictException;
import com.group1.banking.exception.NotFoundException;
import com.group1.banking.exception.UnauthorisedException;
import com.group1.banking.mapper.CustomerMapper;
import com.group1.banking.repository.AccountRepository;
import com.group1.banking.repository.CustomerRepository;
import com.group1.banking.repository.UserRepository;
import com.group1.banking.security.CustomUserPrincipal;
import com.group1.banking.service.impl.CustomerServiceImpl;
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

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomerServiceImplTest {

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private CustomerMapper customerMapper;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AccountRepository accountRepository;

    @InjectMocks
    private CustomerServiceImpl customerService;

    private UUID userId;
    private User customerUser;
    private User adminUser;
    private Customer customer;
    private CustomerResponse customerResponse;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();

        customerUser = new User();
        customerUser.setUserId(userId);
        customerUser.setCustomerId(null);
        customerUser.setRoles(List.of(RoleName.RETAIL_CUSTOMER));
        customerUser.setActive(true);

        adminUser = new User();
        adminUser.setUserId(UUID.randomUUID());
        adminUser.setCustomerId(null);
        adminUser.setRoles(List.of(RoleName.BANK_ADMINISTRATOR));
        adminUser.setActive(true);

        customer = new Customer();
        customer.setCustomerId(42L);
        customer.setName("Jane Doe");
        customer.setAddress("123 Main St");
        customer.setType(CustomerType.PERSON);
        customer.setCreatedAt(Instant.now());
        customer.setUpdatedAt(Instant.now());

        customerResponse = CustomerResponse.builder()
                .customerId(42L)
                .name("Jane Doe")
                .address("123 Main St")
                .type(CustomerType.PERSON)
                .accounts(Collections.emptyList())
                .createdAt(customer.getCreatedAt())
                .updatedAt(customer.getUpdatedAt())
                .build();
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

    // ===== createCustomer TESTS =====

    @Test
    void createCustomer_shouldSucceed_whenValidRequestAndNoExistingCustomer() {
        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));
        when(customerRepository.save(any(Customer.class))).thenReturn(customer);
        when(userRepository.save(any(User.class))).thenReturn(customerUser);
        when(customerMapper.toResponse(customer)).thenReturn(customerResponse);

        CreateCustomerRequest request = new CreateCustomerRequest();
        request.setName("Jane Doe");
        request.setAddress("123 Main St");
        request.setType(CustomerType.PERSON);

        CustomerResponse result = customerService.createCustomer(request);

        assertThat(result).isNotNull();
        assertThat(result.getCustomerId()).isEqualTo(42L);
        verify(customerRepository).save(any(Customer.class));
    }

    @Test
    void createCustomer_shouldThrowBadRequest_whenUserAlreadyHasCustomer() {
        customerUser.setCustomerId(10L); // already linked
        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));

        CreateCustomerRequest request = new CreateCustomerRequest();
        request.setName("Jane Doe");
        request.setAddress("123 Main St");
        request.setType(CustomerType.PERSON);

        assertThatThrownBy(() -> customerService.createCustomer(request))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void createCustomer_shouldThrowNotFoundException_whenUserNotFound() {
        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        CreateCustomerRequest request = new CreateCustomerRequest();
        request.setName("Jane Doe");
        request.setAddress("123 Main St");
        request.setType(CustomerType.PERSON);

        assertThatThrownBy(() -> customerService.createCustomer(request))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void createCustomer_shouldThrowBadRequest_whenPrincipalIsNotCustomUserPrincipal() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn("anonymous");
        SecurityContext securityContext = mock(SecurityContext.class);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        SecurityContextHolder.setContext(securityContext);

        CreateCustomerRequest request = new CreateCustomerRequest();
        request.setName("Jane Doe");
        request.setAddress("123 Main St");
        request.setType(CustomerType.PERSON);

        assertThatThrownBy(() -> customerService.createCustomer(request))
                .isInstanceOf(BadRequestException.class);
    }

    // ===== updateCustomer TESTS =====

    @Test
    void updateCustomer_shouldUpdateName_whenNameProvided() {
        when(customerRepository.findById(42L)).thenReturn(Optional.of(customer));
        when(customerRepository.save(any(Customer.class))).thenReturn(customer);
        when(customerMapper.toResponse(customer)).thenReturn(customerResponse);

        PatchCustomerRequest request = new PatchCustomerRequest();
        request.setName("Jane Smith");

        CustomerResponse result = customerService.updateCustomer(42L, request);

        assertThat(result).isNotNull();
        verify(customerRepository).save(customer);
    }

    @Test
    void updateCustomer_shouldUpdateAddress_whenAddressProvided() {
        when(customerRepository.findById(42L)).thenReturn(Optional.of(customer));
        when(customerRepository.save(any(Customer.class))).thenReturn(customer);
        when(customerMapper.toResponse(customer)).thenReturn(customerResponse);

        PatchCustomerRequest request = new PatchCustomerRequest();
        request.setAddress("456 New St");

        CustomerResponse result = customerService.updateCustomer(42L, request);
        assertThat(result).isNotNull();
    }

    @Test
    void updateCustomer_shouldThrowBadRequest_whenEmailIncluded() {
        PatchCustomerRequest request = new PatchCustomerRequest();
        request.setEmail("new@example.com");

        assertThatThrownBy(() -> customerService.updateCustomer(42L, request))
                .isInstanceOf(BadRequestException.class);

        verify(customerRepository, never()).findById(any());
    }

    @Test
    void updateCustomer_shouldThrowBadRequest_whenAccountNumberIncluded() {
        PatchCustomerRequest request = new PatchCustomerRequest();
        request.setAccountNumber("ACC123");

        assertThatThrownBy(() -> customerService.updateCustomer(42L, request))
                .isInstanceOf(BadRequestException.class);

        verify(customerRepository, never()).findById(any());
    }

    @Test
    void updateCustomer_shouldThrowNotFoundException_whenCustomerNotFound() {
        when(customerRepository.findById(999L)).thenReturn(Optional.empty());

        PatchCustomerRequest request = new PatchCustomerRequest();
        request.setName("Updated Name");

        assertThatThrownBy(() -> customerService.updateCustomer(999L, request))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void updateCustomer_shouldUpdateType_whenTypeProvided() {
        when(customerRepository.findById(42L)).thenReturn(Optional.of(customer));
        when(customerRepository.save(any(Customer.class))).thenReturn(customer);
        when(customerMapper.toResponse(customer)).thenReturn(customerResponse);

        PatchCustomerRequest request = new PatchCustomerRequest();
        request.setType(CustomerType.COMPANY);

        customerService.updateCustomer(42L, request);

        verify(customerRepository).save(argThat(c -> c.getType() == CustomerType.COMPANY));
    }

    // ===== getCustomer TESTS =====

    @Test
    void getCustomer_shouldReturnCustomer_whenExists() {
        when(customerRepository.findById(42L)).thenReturn(Optional.of(customer));
        when(accountRepository.findAllByCustomerCustomerIdAndDeletedAtIsNullAndStatus(42L, AccountStatus.ACTIVE))
                .thenReturn(Collections.emptyList());

        CustomerResponse result = customerService.getCustomer(42L);

        assertThat(result).isNotNull();
        assertThat(result.getCustomerId()).isEqualTo(42L);
    }

    @Test
    void getCustomer_shouldThrowNotFoundException_whenNotFound() {
        when(customerRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customerService.getCustomer(999L))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void getCustomer_shouldIncludeAccounts_whenAccountsExist() {
        Account account = new Account();
        account.setAccountId(1001L);
        account.setCustomer(customer);
        account.setAccountType(com.group1.banking.entity.AccountType.CHECKING);
        account.setStatus(AccountStatus.ACTIVE);
        account.setBalance(new java.math.BigDecimal("100.00"));
        account.setAccountNumber("ACC0000001001");
        account.setDailyTransferLimit(new java.math.BigDecimal("3000.00"));

        when(customerRepository.findById(42L)).thenReturn(Optional.of(customer));
        when(accountRepository.findAllByCustomerCustomerIdAndDeletedAtIsNullAndStatus(42L, AccountStatus.ACTIVE))
                .thenReturn(List.of(account));

        CustomerResponse result = customerService.getCustomer(42L);

        assertThat(result.getAccounts()).hasSize(1);
    }

    // ===== getAllCustomers TESTS =====

    @Test
    void getAllCustomers_shouldReturnAllCustomers() {
        Customer customer2 = new Customer();
        customer2.setCustomerId(43L);
        customer2.setName("John Smith");
        customer2.setAddress("456 Oak Ave");
        customer2.setType(CustomerType.COMPANY);
        customer2.setCreatedAt(Instant.now());
        customer2.setUpdatedAt(Instant.now());

        when(customerRepository.findAll()).thenReturn(List.of(customer, customer2));
        when(accountRepository.findAllByCustomerCustomerIdAndDeletedAtIsNullAndStatus(anyLong(), any()))
                .thenReturn(Collections.emptyList());

        List<CustomerResponse> result = customerService.getAllCustomers();

        assertThat(result).hasSize(2);
    }

    @Test
    void getAllCustomers_shouldReturnEmptyList_whenNoCustomers() {
        when(customerRepository.findAll()).thenReturn(Collections.emptyList());

        List<CustomerResponse> result = customerService.getAllCustomers();

        assertThat(result).isEmpty();
    }

    // ===== deleteCustomer TESTS =====

    @Test
    void deleteCustomer_shouldSoftDelete_whenAdminAndNoActiveAccounts() {
        setUpSecurityContextWith(adminUser);
        when(userRepository.findById(adminUser.getUserId())).thenReturn(Optional.of(adminUser));
        when(customerRepository.findByCustomerIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(customer));
        when(accountRepository.existsByCustomerCustomerIdAndDeletedAtIsNullAndStatus(42L, AccountStatus.ACTIVE))
                .thenReturn(false);
        when(customerRepository.save(any(Customer.class))).thenReturn(customer);

        customerService.deleteCustomer(42L);

        verify(customerRepository).save(argThat(c -> c.getDeletedAt() != null));
    }

    @Test
    void deleteCustomer_shouldThrowUnauthorised_whenCallerIsNotAdmin() {
        setUpSecurityContextWith(customerUser);
        when(userRepository.findById(userId)).thenReturn(Optional.of(customerUser));

        assertThatThrownBy(() -> customerService.deleteCustomer(42L))
                .isInstanceOf(UnauthorisedException.class);
    }

    @Test
    void deleteCustomer_shouldThrowNotFoundException_whenCustomerNotFound() {
        setUpSecurityContextWith(adminUser);
        when(userRepository.findById(adminUser.getUserId())).thenReturn(Optional.of(adminUser));
        when(customerRepository.findByCustomerIdAndDeletedAtIsNull(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customerService.deleteCustomer(999L))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void deleteCustomer_shouldThrowConflict_whenCustomerHasActiveAccounts() {
        setUpSecurityContextWith(adminUser);
        when(userRepository.findById(adminUser.getUserId())).thenReturn(Optional.of(adminUser));
        when(customerRepository.findByCustomerIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(customer));
        when(accountRepository.existsByCustomerCustomerIdAndDeletedAtIsNullAndStatus(42L, AccountStatus.ACTIVE))
                .thenReturn(true);

        assertThatThrownBy(() -> customerService.deleteCustomer(42L))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void deleteCustomer_shouldThrowUnauthorised_whenPrincipalInvalid() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn("anonymous");
        SecurityContext securityContext = mock(SecurityContext.class);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        SecurityContextHolder.setContext(securityContext);

        assertThatThrownBy(() -> customerService.deleteCustomer(42L))
                .isInstanceOf(UnauthorisedException.class);
    }
}
