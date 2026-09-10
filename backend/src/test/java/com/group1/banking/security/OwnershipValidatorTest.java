package com.group1.banking.security;

import com.group1.banking.entity.Account;
import com.group1.banking.entity.AccountStatus;
import com.group1.banking.entity.AccountType;
import com.group1.banking.entity.Customer;
import com.group1.banking.enums.CustomerType;
import com.group1.banking.exception.OwnershipException;
import com.group1.banking.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OwnershipValidatorTest {

    @Mock
    private AccountRepository accountRepository;

    @InjectMocks
    private OwnershipValidator ownershipValidator;

    private Account account;
    private UserPrincipal customerCaller;
    private UserPrincipal adminCaller;

    @BeforeEach
    void setUp() {
        Customer customer = new Customer();
        customer.setCustomerId(42L);
        customer.setName("Jane Doe");
        customer.setType(CustomerType.PERSON);
        customer.setAddress("123 Main St");

        account = new Account();
        account.setAccountId(1001L);
        account.setCustomer(customer);
        account.setAccountType(AccountType.CHECKING);
        account.setStatus(AccountStatus.ACTIVE);
        account.setBalance(new BigDecimal("500.00"));

        customerCaller = new UserPrincipal("uuid-customer", "jane@example.com",
                List.of("RETAIL_CUSTOMER"), List.of("CUSTOMER_READ"), 42L);
        adminCaller = new UserPrincipal("uuid-admin", "admin@example.com",
                List.of("BANK_ADMINISTRATOR"), List.of("CUSTOMER_READ", "CUSTOMER_DELETE"), null);
    }

    @Test
    void assertOwnership_shouldPass_whenAdminCaller() {
        assertThatNoException().isThrownBy(() -> ownershipValidator.assertOwnership(1001L, adminCaller));
        verify(accountRepository, never()).findById(any());
    }

    @Test
    void assertOwnership_shouldPass_whenCustomerOwnsAccount() {
        when(accountRepository.findById(1001L)).thenReturn(Optional.of(account));
        assertThatNoException().isThrownBy(() -> ownershipValidator.assertOwnership(1001L, customerCaller));
    }

    @Test
    void assertOwnership_shouldThrow_whenCustomerDoesNotOwnAccount() {
        UserPrincipal otherCustomer = new UserPrincipal("uuid-other", "other@example.com",
                List.of("RETAIL_CUSTOMER"), List.of("CUSTOMER_READ"), 99L);
        when(accountRepository.findById(1001L)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> ownershipValidator.assertOwnership(1001L, otherCustomer))
                .isInstanceOf(OwnershipException.class);
    }

    @Test
    void assertOwnership_shouldThrow_whenAccountNotFound() {
        when(accountRepository.findById(1001L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ownershipValidator.assertOwnership(1001L, customerCaller))
                .isInstanceOf(OwnershipException.class);
    }
}
