package com.group1.banking.service.impl;

import com.group1.banking.dto.chat.AccountSummary;
import com.group1.banking.entity.PendingAgentActionEntity;
import com.group1.banking.entity.PendingAgentActionStatus;
import com.group1.banking.entity.PendingAgentActionType;
import com.group1.banking.service.ConfirmationGateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TransferChatToolTest {

    private static final Long CUSTOMER_ID = 42L;

    private SavingsChatContextService contextService;
    private ConfirmationGateService confirmationGateService;
    private PendingActionTracker pendingActionTracker;
    private ToolSelectionTracker toolSelectionTracker;
    private TransferChatTool tool;

    @BeforeEach
    void setUp() {
        contextService = mock(SavingsChatContextService.class);
        confirmationGateService = mock(ConfirmationGateService.class);
        pendingActionTracker = new PendingActionTracker();
        toolSelectionTracker = new ToolSelectionTracker();
        tool = new TransferChatTool(contextService, confirmationGateService, pendingActionTracker, toolSelectionTracker);

        when(contextService.getAccountSummaries(CUSTOMER_ID)).thenReturn(List.of(
                new AccountSummary(1L, "CHECKING", "ACTIVE", new BigDecimal("300.00")),
                new AccountSummary(2L, "SAVINGS", "ACTIVE", new BigDecimal("50.00"))
        ));
    }

    private ToolContext toolContext(String actorRole) {
        return new ToolContext(Map.of("customerId", CUSTOMER_ID, "actorRole", actorRole));
    }

    @Test
    void proposeTransfer_shouldCreateProposal_andRecordItOnTracker() {
        PendingAgentActionEntity proposed = proposedEntity();
        when(confirmationGateService.propose(eq(CUSTOMER_ID), eq("RETAIL_CUSTOMER"), eq(PendingAgentActionType.TRANSFER),
                any(), any())).thenReturn(proposed);

        String reply = tool.proposeTransfer(1L, 2L, new BigDecimal("50.00"), "rent", toolContext("RETAIL_CUSTOMER"));

        assertThat(reply).contains("50.00").contains(proposed.getToken());
        assertThat(pendingActionTracker.drainProposal()).isPresent();
        assertThat(pendingActionTracker.drainProposal()).isEmpty();
    }

    @Test
    void proposeTransfer_shouldRecordItselfOnToolSelectionTracker_evenWhenRefused() {
        tool.proposeTransfer(1L, 999L, new BigDecimal("50.00"), null, toolContext("RETAIL_CUSTOMER"));

        assertThat(toolSelectionTracker.drainToolsUsed()).containsExactly("proposeTransfer");
    }

    @Test
    void proposeTransfer_shouldRefuse_whenAccountNotOwnedByCustomer() {
        String reply = tool.proposeTransfer(1L, 999L, new BigDecimal("50.00"), null, toolContext("RETAIL_CUSTOMER"));

        assertThat(reply).containsIgnoringCase("aren't available");
        assertThat(pendingActionTracker.drainProposal()).isEmpty();
    }

    @Test
    void proposeTransfer_shouldRefuse_whenAmountExceedsBalance() {
        String reply = tool.proposeTransfer(2L, 1L, new BigDecimal("999.00"), null, toolContext("RETAIL_CUSTOMER"));

        assertThat(reply).containsIgnoringCase("balance");
        assertThat(pendingActionTracker.drainProposal()).isEmpty();
    }

    @Test
    void proposeTransfer_shouldRefuse_whenSameAccountBothSides() {
        String reply = tool.proposeTransfer(1L, 1L, new BigDecimal("10.00"), null, toolContext("RETAIL_CUSTOMER"));

        assertThat(reply).containsIgnoringCase("same account");
    }

    @Test
    void proposeTransfer_shouldRefuse_whenAmountNotPositive() {
        String reply = tool.proposeTransfer(1L, 2L, new BigDecimal("0.00"), null, toolContext("RETAIL_CUSTOMER"));

        assertThat(reply).containsIgnoringCase("at least");
    }

    private PendingAgentActionEntity proposedEntity() {
        PendingAgentActionEntity entity = new PendingAgentActionEntity();
        entity.setToken("tok-abc");
        entity.setCustomerId(CUSTOMER_ID);
        entity.setActionType(PendingAgentActionType.TRANSFER);
        entity.setStatus(PendingAgentActionStatus.PENDING);
        entity.setHumanSummary("Transfer $50.00 from your CHECKING account (#1) to your SAVINGS account (#2).");
        entity.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        return entity;
    }
}
