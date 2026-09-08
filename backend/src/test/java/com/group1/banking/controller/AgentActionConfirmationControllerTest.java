package com.group1.banking.controller;

import com.group1.banking.dto.customer.OperationResult;
import com.group1.banking.entity.PendingAgentActionEntity;
import com.group1.banking.entity.PendingAgentActionStatus;
import com.group1.banking.entity.PendingAgentActionType;
import com.group1.banking.exception.ConflictException;
import com.group1.banking.exception.GoneException;
import com.group1.banking.exception.NotFoundException;
import com.group1.banking.repository.UserRepository;
import com.group1.banking.security.JwtService;
import com.group1.banking.service.ConfirmationGateService;
import com.group1.banking.service.impl.MonetaryOperationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgentActionConfirmationController.class)
@AutoConfigureMockMvc(addFilters = false)
class AgentActionConfirmationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConfirmationGateService confirmationGateService;

    @MockitoBean
    private MonetaryOperationService monetaryOperationService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    @WithCustomUser(customerId = 42L)
    void confirm_shouldExecuteTransfer_whenTokenValid() throws Exception {
        PendingAgentActionEntity resolved = transferEntity("tok-1");
        when(confirmationGateService.confirmAndConsume(eq("tok-1"), eq(42L), eq("RETAIL_CUSTOMER"))).thenReturn(resolved);
        when(monetaryOperationService.transfer(any(), eq("tok-1")))
                .thenReturn(new OperationResult(HttpStatus.OK, Map.of("message", "Transfer complete")));

        mockMvc.perform(post("/api/chat/confirmations/tok-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Transfer complete"));
    }

    @Test
    @WithCustomUser(customerId = 42L)
    void confirm_shouldReturn404_whenTokenNotFound() throws Exception {
        when(confirmationGateService.confirmAndConsume(eq("missing"), eq(42L), eq("RETAIL_CUSTOMER")))
                .thenThrow(new NotFoundException("CONFIRMATION_NOT_FOUND", "No pending action found for this token.", null));

        mockMvc.perform(post("/api/chat/confirmations/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONFIRMATION_NOT_FOUND"));
    }

    @Test
    @WithCustomUser(customerId = 42L)
    void confirm_shouldReturn409_whenAlreadyResolved() throws Exception {
        when(confirmationGateService.confirmAndConsume(eq("tok-2"), eq(42L), eq("RETAIL_CUSTOMER")))
                .thenThrow(new ConflictException("CONFIRMATION_ALREADY_RESOLVED", "This action has already been executed.", null));

        mockMvc.perform(post("/api/chat/confirmations/tok-2"))
                .andExpect(status().isConflict());
    }

    @Test
    @WithCustomUser(customerId = 42L)
    void confirm_shouldReturn410_whenExpired() throws Exception {
        when(confirmationGateService.confirmAndConsume(eq("tok-3"), eq(42L), eq("RETAIL_CUSTOMER")))
                .thenThrow(new GoneException("CONFIRMATION_EXPIRED", "This confirmation has expired.", null));

        mockMvc.perform(post("/api/chat/confirmations/tok-3"))
                .andExpect(status().isGone());
    }

    private PendingAgentActionEntity transferEntity(String token) {
        PendingAgentActionEntity entity = new PendingAgentActionEntity();
        entity.setToken(token);
        entity.setCustomerId(42L);
        entity.setActionType(PendingAgentActionType.TRANSFER);
        entity.setParametersJson("{\"fromAccountId\":1,\"toAccountId\":2,\"amount\":50.00,\"description\":null}");
        entity.setHumanSummary("Transfer $50.00");
        entity.setStatus(PendingAgentActionStatus.EXECUTED);
        entity.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        return entity;
    }
}
