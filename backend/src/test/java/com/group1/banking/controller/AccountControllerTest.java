package com.group1.banking.controller;

import tools.jackson.databind.json.JsonMapper;
import com.group1.banking.dto.accountcontrol.AccountControlActionResponse;
import com.group1.banking.dto.accountcontrol.AccountControlHistoryEventResponse;
import com.group1.banking.dto.accountcontrol.AccountControlHistoryResponse;
import com.group1.banking.dto.accountcontrol.FreezeAccountRequest;
import com.group1.banking.dto.accountcontrol.UnfreezeAccountRequest;
import com.group1.banking.dto.customer.*;
import com.group1.banking.entity.AccountStatus;
import com.group1.banking.entity.AccountType;
import com.group1.banking.entity.accountcontrol.AccountControlActionType;
import com.group1.banking.exception.ResourceNotFoundException;
import com.group1.banking.exception.ForbiddenException;
import com.group1.banking.exception.UnauthorisedException;
import com.group1.banking.service.impl.AccountService;
import com.group1.banking.repository.UserRepository;
import com.group1.banking.security.JwtService;
import com.group1.banking.service.impl.MonetaryOperationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Controller tests for AccountController using @WebMvcTest.
 */
@WebMvcTest(AccountController.class)
@AutoConfigureMockMvc(addFilters = false)
class AccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper objectMapper;

    @MockitoBean
    private AccountService accountService;

    @MockitoBean
    private MonetaryOperationService monetaryOperationService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserRepository userRepository;

    private AccountResponse sampleAccount() {
        return new AccountResponse(
                1001L, 42L, AccountType.CHECKING, AccountStatus.ACTIVE,
                new BigDecimal("500.00"), null, Instant.now(), Instant.now());
    }

    // ===== CREATE ACCOUNT =====

    @Test
    void createAccount_shouldReturn201_forCheckingAccount() throws Exception {
        CreateAccountRequest request = new CreateAccountRequest(
                AccountType.CHECKING, new BigDecimal("100.00"), null);

        when(accountService.createAccount(eq(42L), any(CreateAccountRequest.class)))
                .thenReturn(sampleAccount());

        mockMvc.perform(post("/customers/42/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountId").value(1001))
                .andExpect(jsonPath("$.accountType").value("CHECKING"));
    }

    @Test
    void createAccount_shouldReturn201_forSavingsAccount() throws Exception {
        CreateAccountRequest request = new CreateAccountRequest(
                AccountType.SAVINGS, new BigDecimal("200.00"), new BigDecimal("0.05"));

        AccountResponse savingsResponse = new AccountResponse(
                1002L, 42L, AccountType.SAVINGS, AccountStatus.ACTIVE,
                new BigDecimal("200.00"), new BigDecimal("0.05"), Instant.now(), Instant.now());

        when(accountService.createAccount(eq(42L), any(CreateAccountRequest.class)))
                .thenReturn(savingsResponse);

        mockMvc.perform(post("/customers/42/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountType").value("SAVINGS"));
    }

    @Test
    void createAccount_shouldReturn400_whenAccountTypeMissing() throws Exception {
        String body = "{\"balance\":100.00}"; // no accountType

        mockMvc.perform(post("/customers/42/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableContent());
    }

    // ===== GET ACCOUNT =====

    @Test
    void getAccount_shouldReturn200_whenFound() throws Exception {
        when(accountService.getAccount(1001L)).thenReturn(sampleAccount());

        mockMvc.perform(get("/accounts/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(1001));
    }

    @Test
    void getAccount_shouldReturn404_whenNotFound() throws Exception {
        when(accountService.getAccount(999L))
                .thenThrow(new ResourceNotFoundException("Account not found", "ERR_ACC_NOT_FOUND", null));

        mockMvc.perform(get("/accounts/999"))
                .andExpect(status().isNotFound());
    }

    // ===== LIST CUSTOMER ACCOUNTS =====

    @Test
    void listCustomerAccounts_shouldReturn200_withAccountList() throws Exception {
        when(accountService.listCustomerAccounts(42L)).thenReturn(List.of(sampleAccount()));

        mockMvc.perform(get("/customers/42/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].accountId").value(1001));
    }

    @Test
    void listCustomerAccounts_shouldReturn200_withEmptyList() throws Exception {
        when(accountService.listCustomerAccounts(42L)).thenReturn(List.of());

        mockMvc.perform(get("/customers/42/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    // ===== DELETE ACCOUNT =====

    @Test
    void deleteAccount_shouldReturn200_withMessage() throws Exception {
        doNothing().when(accountService).deleteAccount(1001L);

        mockMvc.perform(delete("/accounts/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Account deleted successfully"));
    }

    // ===== DEPOSIT =====

    @Test
    void deposit_shouldReturn200_whenSuccessful() throws Exception {
        MonetaryRequest request = new MonetaryRequest(new BigDecimal("100.00"), "deposit");
        OperationResult result = new OperationResult(HttpStatus.OK, Map.of("balance", "600.00"));

        when(monetaryOperationService.deposit(eq(1001L), any(), any())).thenReturn(result);

        mockMvc.perform(post("/accounts/1001/deposit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    void deposit_shouldReturn200_withIdempotencyKey() throws Exception {
        MonetaryRequest request = new MonetaryRequest(new BigDecimal("100.00"), "deposit");
        OperationResult result = new OperationResult(HttpStatus.OK, Map.of("balance", "600.00"));

        when(monetaryOperationService.deposit(eq(1001L), any(), eq("idem-key-123"))).thenReturn(result);

        mockMvc.perform(post("/accounts/1001/deposit")
                        .header("Idempotency-Key", "idem-key-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    // ===== WITHDRAW =====

    @Test
    void withdraw_shouldReturn200_whenSuccessful() throws Exception {
        MonetaryRequest request = new MonetaryRequest(new BigDecimal("50.00"), "withdraw");
        OperationResult result = new OperationResult(HttpStatus.OK, Map.of("balance", "450.00"));

        when(monetaryOperationService.withdraw(eq(1001L), any(), any())).thenReturn(result);

        mockMvc.perform(post("/accounts/1001/withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    void withdraw_shouldReturn409_whenInsufficientFunds() throws Exception {
        MonetaryRequest request = new MonetaryRequest(new BigDecimal("9999.00"), "withdraw");
        OperationResult result = new OperationResult(HttpStatus.CONFLICT, Map.of("error", "Insufficient funds"));

        when(monetaryOperationService.withdraw(eq(1001L), any(), any())).thenReturn(result);

        mockMvc.perform(post("/accounts/1001/withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    // ===== TRANSFER =====

    @Test
    void transfer_shouldReturn200_whenSuccessful() throws Exception {
        TransferRequest request = new TransferRequest(1001L, 1002L, new BigDecimal("100.00"), "transfer");
        OperationResult result = new OperationResult(HttpStatus.OK, Map.of("status", "SUCCESS"));

        when(monetaryOperationService.transfer(any(TransferRequest.class), any())).thenReturn(result);

        mockMvc.perform(post("/accounts/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    // ===== CLOSE RRSP ACCOUNT =====

    @Test
    void closeRrspAccount_shouldReturn200_whenSuccessful() throws Exception {
        when(accountService.closeRrspAccount(1001L))
                .thenReturn(java.util.Map.of("message", "RRSP account closed successfully", "accountId", 1001L));

        mockMvc.perform(post("/accounts/1001/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("RRSP account closed successfully"));
    }

    @Test
    void freezeAccount_shouldReturn200_whenSuccessful() throws Exception {
        var request = new FreezeAccountRequest("Fraud review", "SECURITY_REVIEW", null);
        var response = new AccountControlActionResponse(
                1001L,
                AccountStatus.ACTIVE,
                AccountStatus.FROZEN,
                AccountControlActionType.FREEZE,
                Instant.now());

        when(accountService.freezeAccount(eq(1001L), any(FreezeAccountRequest.class))).thenReturn(response);

        mockMvc.perform(post("/accounts/1001/freeze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newStatus").value("FROZEN"));
    }

    @Test
    void unfreezeAccount_shouldReturn200_whenSuccessful() throws Exception {
        var request = new UnfreezeAccountRequest("Resolved", "case closed");
        var response = new AccountControlActionResponse(
                1001L,
                AccountStatus.FROZEN,
                AccountStatus.ACTIVE,
                AccountControlActionType.UNFREEZE,
                Instant.now());

        when(accountService.unfreezeAccount(eq(1001L), any(UnfreezeAccountRequest.class))).thenReturn(response);

        mockMvc.perform(post("/accounts/1001/unfreeze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newStatus").value("ACTIVE"));
    }

    @Test
    void controlHistory_shouldReturn200_whenSuccessful() throws Exception {
        var event = new AccountControlHistoryEventResponse(
                1L,
                AccountControlActionType.FREEZE,
                AccountStatus.ACTIVE,
                AccountStatus.FROZEN,
                "admin-1",
                "BANK_ADMINISTRATOR",
                "Fraud review",
                null,
                Instant.now());

        when(accountService.getControlHistory(1001L)).thenReturn(new AccountControlHistoryResponse(1001L, List.of(event)));

        mockMvc.perform(get("/accounts/1001/control-history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(1001L))
                .andExpect(jsonPath("$.events[0].actionType").value("FREEZE"));
    }

    @Test
    void freezeAccount_shouldReturn401_whenUnauthorized() throws Exception {
        var request = new FreezeAccountRequest("Fraud review", "SECURITY_REVIEW", null);
        when(accountService.freezeAccount(eq(1001L), any(FreezeAccountRequest.class)))
                .thenThrow(new UnauthorisedException("UNAUTHORIZED", "Authenticated user not found"));

        mockMvc.perform(post("/accounts/1001/freeze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void freezeAccount_shouldReturn403_whenForbidden() throws Exception {
        var request = new FreezeAccountRequest("Fraud review", "SECURITY_REVIEW", null);
        when(accountService.freezeAccount(eq(1001L), any(FreezeAccountRequest.class)))
                .thenThrow(new ForbiddenException("FORBIDDEN", "Only admin users can perform this action"));

        mockMvc.perform(post("/accounts/1001/freeze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void freezeAccount_shouldReturn403_whenCustomerUserAccesses() throws Exception {
        var request = new FreezeAccountRequest("Fraud review", "SECURITY_REVIEW", null);
        when(accountService.freezeAccount(eq(1001L), any(FreezeAccountRequest.class)))
                .thenThrow(new ForbiddenException("FORBIDDEN", "Only admin users can perform this action"));

        mockMvc.perform(post("/accounts/1001/freeze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void unfreezeAccount_shouldReturn403_whenForbidden() throws Exception {
        var request = new UnfreezeAccountRequest("Resolved", "note");
        when(accountService.unfreezeAccount(eq(1001L), any(UnfreezeAccountRequest.class)))
                .thenThrow(new ForbiddenException("FORBIDDEN", "Only admin users can perform this action"));

        mockMvc.perform(post("/accounts/1001/unfreeze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }
}

