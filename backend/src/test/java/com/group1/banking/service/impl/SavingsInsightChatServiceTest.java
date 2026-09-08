package com.group1.banking.service.impl;

import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import com.group1.banking.dto.chat.ChatQueryResponse;
import com.group1.banking.entity.AuditEventType;
import com.group1.banking.entity.AuditOutcome;
import com.group1.banking.enums.RoleName;
import com.group1.banking.repository.ChatInteractionLogRepository;
import com.group1.banking.service.AuditService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the chatbot turn orchestration (T-BRD 6.1).
 *
 * These cover the acceptance criteria that live in this class rather than in the model:
 * that a personalized answer is distinguished from a limited-data fallback, that blocked
 * topics never reach the model, that a model failure still returns a controlled response
 * instead of an error, that each of those is logged with the right traceability flags, and
 * that each of those is also recorded through the shared audit trail (CFG-03).
 *
 * The {@link ChatClient} is mocked, so no Groq call is made and sampling temperature is
 * irrelevant here. Tool calls are simulated by having the stubbed response record
 * citations on a real {@link SavingsChatCitationTracker}, which is exactly what the real
 * tools do from inside the model's tool-calling loop.
 */
class SavingsInsightChatServiceTest {

    private static final Long CUSTOMER_ID = 42L;
        private static final String ACTOR_ROLE = "RETAIL_CUSTOMER";
    private static final Long CHAT_LOG_ID = 999L;

    private SavingsChatGuardrailService guardrailService;
    private ChatClient chatClient;
    private ChatInteractionLogRepository chatLogRepository;
    private SavingsChatCitationTracker citationTracker;
    private PendingActionTracker pendingActionTracker;
        private ToolSelectionTracker toolSelectionTracker;
    private AuditService auditService;
    private SavingsInsightChatService service;

    @BeforeEach
    void setUp() {
        guardrailService = new SavingsChatGuardrailService();
        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        chatLogRepository = mock(ChatInteractionLogRepository.class);
        citationTracker = new SavingsChatCitationTracker();
        pendingActionTracker = new PendingActionTracker();
        toolSelectionTracker = new ToolSelectionTracker();
        auditService = mock(AuditService.class);
        // Every scenario below cares that the audit row's resourceId is whatever the
        // chat-specific log returned, not what that ID actually is - a fixed stub value
        // keeps that assertion meaningful without coupling every test to a real insert.
        when(chatLogRepository.log(any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any()))
                .thenReturn(CHAT_LOG_ID);
        service = new SavingsInsightChatService(
                guardrailService, chatClient, chatLogRepository, citationTracker,
                pendingActionTracker, toolSelectionTracker, auditService);
    }

    /**
     * Stubs the fluent ChatClient chain, running {@code duringCall} at the point the model
     * would be invoked so tests can simulate whichever tools the agent decided to call.
     */
    private void stubChatReply(String reply, Runnable duringCall) {
        when(chatClient.prompt()
                .user(anyString())
                .toolContext(any())
                .advisors(anyAdvisorConsumer())
                .call()
                .content())
                .thenAnswer(invocation -> {
                    duringCall.run();
                    return reply;
                });
    }

    private void stubChatFailure(RuntimeException failure) {
        when(chatClient.prompt()
                .user(anyString())
                .toolContext(any())
                .advisors(anyAdvisorConsumer())
                .call()
                .content())
                .thenThrow(failure);
    }

    @SuppressWarnings("unchecked")
    private Consumer<ChatClient.AdvisorSpec> anyAdvisorConsumer() {
        return (Consumer<ChatClient.AdvisorSpec>) any(Consumer.class);
    }

    @Test
    @DisplayName("Scenario 1: personal data retrieved -> personalized answer, logged as ANSWERED")
    void ask_whenPersonalDataUsed_returnsPersonalizedAnswer() {
        stubChatReply("You spent $312 on Food & Drink over the last 30 days.", () -> {
            citationTracker.recordCitation("Your recent transaction history");
            citationTracker.markPersonalDataUsed();
        });

        ChatQueryResponse response = service.ask(CUSTOMER_ID, ACTOR_ROLE, "where is my money going?");

        assertThat(response.getResponse()).contains("$312");
        assertThat(response.isLimitedData()).isFalse();
        assertThat(response.isBlocked()).isFalse();
        assertThat(response.getBasedOn()).containsExactly("Your recent transaction history");

        verify(chatLogRepository).log(eq(CUSTOMER_ID), eq("where is my money going?"),
                anyString(), eq("ANSWERED"),
                eq(true), eq(false), eq(List.of("Your recent transaction history")), eq(List.of()));
        verify(auditService).log(eq(AuditEventType.CHATBOT_QUERY_ANSWERED), eq("SAVINGS_INSIGHT_CHATBOT"),
                eq(RoleName.RETAIL_CUSTOMER), eq("42"), eq("CHATBOT_INTERACTION"), eq(CHAT_LOG_ID.toString()),
                eq(AuditOutcome.SUCCESS), eq("ANSWERED"));
    }

    @Test
    @DisplayName("Scenario 2: knowledge base only -> retrieval happened but answer is still a fallback")
    void ask_whenOnlyKnowledgeBaseUsed_flagsLimitedDataButRecordsRetrieval() {
        // The knowledge base tool records a citation but never marks personal data used,
        // which is the case the single `outcome` column used to hide.
        stubChatReply("An emergency fund usually covers three to six months of expenses.",
                () -> citationTracker.recordCitation("Savings knowledge base: emergency-funds.md"));

        ChatQueryResponse response = service.ask(CUSTOMER_ID, ACTOR_ROLE, "what is an emergency fund?");

        assertThat(response.isLimitedData()).isTrue();
        assertThat(response.isBlocked()).isFalse();
        assertThat(response.getBasedOn()).containsExactly("Savings knowledge base: emergency-funds.md");

        verify(chatLogRepository).log(eq(CUSTOMER_ID), anyString(), anyString(), eq("FALLBACK"),
                eq(true), eq(true),
                eq(List.of("Savings knowledge base: emergency-funds.md")), eq(List.of()));
        verify(auditService).log(eq(AuditEventType.CHATBOT_QUERY_ANSWERED), eq("SAVINGS_INSIGHT_CHATBOT"),
                eq(RoleName.RETAIL_CUSTOMER), eq("42"), eq("CHATBOT_INTERACTION"), eq(CHAT_LOG_ID.toString()),
                eq(AuditOutcome.SUCCESS), eq("FALLBACK"));
    }

    @Test
    @DisplayName("Scenario 2: no data retrieved at all -> limited data, no sources logged")
    void ask_whenNothingRetrieved_flagsLimitedDataAndNoRetrieval() {
        stubChatReply("Hello! I can help with savings and budgeting questions.", () -> { });

        ChatQueryResponse response = service.ask(CUSTOMER_ID, ACTOR_ROLE, "hello");

        assertThat(response.isLimitedData()).isTrue();
        assertThat(response.getBasedOn()).isEmpty();

        verify(chatLogRepository).log(eq(CUSTOMER_ID), anyString(), anyString(), eq("FALLBACK"),
                eq(false), eq(true), eq(List.of()), eq(List.of()));
        verify(auditService).log(eq(AuditEventType.CHATBOT_QUERY_ANSWERED), eq("SAVINGS_INSIGHT_CHATBOT"),
                eq(RoleName.RETAIL_CUSTOMER), eq("42"), eq("CHATBOT_INTERACTION"), eq(CHAT_LOG_ID.toString()),
                eq(AuditOutcome.SUCCESS), eq("FALLBACK"));
    }

    @Test
    @DisplayName("Scenario 5: blocked topic never reaches the model and is logged as GUARDRAIL_BLOCKED")
    void ask_whenTopicBlocked_declinesWithoutCallingModel() {
        ChatQueryResponse response = service.ask(CUSTOMER_ID, ACTOR_ROLE, "which stock should I buy?");

        assertThat(response.isBlocked()).isTrue();
        assertThat(response.isLimitedData()).isFalse();
        assertThat(response.getBasedOn()).isEmpty();
        assertThat(response.getResponse()).contains("not able to advise on that topic");

        verify(chatClient, never()).prompt();
        verify(chatLogRepository).log(eq(CUSTOMER_ID), eq("which stock should I buy?"),
                anyString(), eq("GUARDRAIL_BLOCKED"),
                eq(false), eq(false), eq(List.of()), eq(List.of()));
        verify(auditService).log(eq(AuditEventType.CHATBOT_QUERY_ANSWERED), eq("SAVINGS_INSIGHT_CHATBOT"),
                eq(RoleName.RETAIL_CUSTOMER), eq("42"), eq("CHATBOT_INTERACTION"), eq(CHAT_LOG_ID.toString()),
                eq(AuditOutcome.DENIED), eq("GUARDRAIL_BLOCKED"));
    }

    @Test
    @DisplayName("Scenario 3: model failure returns the controlled fallback, not an error")
    void ask_whenChatClientFails_returnsFallbackResponse() {
        stubChatFailure(new IllegalStateException("groq unavailable"));

        ChatQueryResponse response = service.ask(CUSTOMER_ID, ACTOR_ROLE, "how are my savings goals doing?");

        assertThat(response.getResponse()).isEqualTo(
                "I'm unable to generate a response right now. Please try again in a moment.");
        assertThat(response.isLimitedData()).isTrue();
        assertThat(response.isBlocked()).isFalse();
        assertThat(response.getBasedOn()).isEmpty();

        verify(chatLogRepository).log(eq(CUSTOMER_ID), anyString(), anyString(), eq("ERROR"),
                eq(false), eq(true), eq(List.of()), eq(List.of()));
        verify(auditService).log(eq(AuditEventType.CHATBOT_QUERY_ANSWERED), eq("SAVINGS_INSIGHT_CHATBOT"),
                eq(RoleName.RETAIL_CUSTOMER), eq("42"), eq("CHATBOT_INTERACTION"), eq(CHAT_LOG_ID.toString()),
                eq(AuditOutcome.ERROR), eq("ERROR"));
    }

    @Test
    @DisplayName("A blank message is refused before the model is called")
    void ask_whenMessageBlank_isBlocked() {
        ChatQueryResponse response = service.ask(CUSTOMER_ID, ACTOR_ROLE, "   ");

        assertThat(response.isBlocked()).isTrue();
        verify(chatClient, never()).prompt();
        verify(chatLogRepository).log(anyLong(), anyString(), anyString(),
                eq("GUARDRAIL_BLOCKED"), anyBoolean(), anyBoolean(), anyList(), anyList());
        verify(auditService).log(eq(AuditEventType.CHATBOT_QUERY_ANSWERED), eq("SAVINGS_INSIGHT_CHATBOT"),
                any(), anyString(), eq("CHATBOT_INTERACTION"), any(), eq(AuditOutcome.DENIED), eq("GUARDRAIL_BLOCKED"));
    }

    @Test
    @DisplayName("A failure writing the audit entry does not fail the chat response")
    void ask_whenAuditLoggingFails_stillReturnsResponse() {
        stubChatReply("Your Emergency Fund goal is 50% complete.", () -> {
            citationTracker.recordCitation("Your savings goal progress");
            citationTracker.markPersonalDataUsed();
        });
        doThrow(new RuntimeException("audit datasource unavailable"))
                .when(auditService).log(any(), any(), any(), any(), any(), any(), any(), any());

        ChatQueryResponse response = service.ask(CUSTOMER_ID, ACTOR_ROLE, "how is my goal going?");

        assertThat(response.getResponse()).isEqualTo("Your Emergency Fund goal is 50% complete.");
        assertThat(response.isBlocked()).isFalse();
    }

    @Test
    @DisplayName("Citation state does not leak from one turn into the next on the same thread")
    void ask_doesNotLeakCitationsBetweenTurns() {
        stubChatReply("Your Emergency Fund goal is 50% complete.", () -> {
            citationTracker.recordCitation("Your savings goal progress");
            citationTracker.markPersonalDataUsed();
        });
        service.ask(CUSTOMER_ID, ACTOR_ROLE, "how is my goal going?");

        // Second turn retrieves nothing; it must not inherit the first turn's citations.
        stubChatReply("Happy to help with savings questions.", () -> { });
        ChatQueryResponse second = service.ask(CUSTOMER_ID, ACTOR_ROLE, "thanks");

        assertThat(second.getBasedOn()).isEmpty();
        assertThat(second.isLimitedData()).isTrue();
    }

    @Test
    @DisplayName("When the transfer tool proposed a transfer this turn, the response carries pending_confirmation")
    void ask_whenTransferProposed_attachesPendingConfirmation() {
        java.time.LocalDateTime expiresAt = java.time.LocalDateTime.now().plusMinutes(5);
        stubChatReply("I've prepared that transfer for you to confirm.", () ->
                pendingActionTracker.recordProposal("tok-9", "TRANSFER", "Transfer $50.00 from Checking to Savings.", expiresAt));

        ChatQueryResponse response = service.ask(CUSTOMER_ID, ACTOR_ROLE, "transfer $50 from checking to savings");

        assertThat(response.getPendingConfirmation()).isNotNull();
        assertThat(response.getPendingConfirmation().getToken()).isEqualTo("tok-9");
        assertThat(response.getPendingConfirmation().getActionType()).isEqualTo("TRANSFER");
    }

    @Test
    @DisplayName("When nothing was proposed this turn, pending_confirmation is null")
    void ask_whenNothingProposed_leavesPendingConfirmationNull() {
        stubChatReply("You spent $312 on Food & Drink over the last 30 days.", () -> {
            citationTracker.recordCitation("Your recent transaction history");
            citationTracker.markPersonalDataUsed();
        });

        ChatQueryResponse response = service.ask(CUSTOMER_ID, ACTOR_ROLE, "where is my money going?");

        assertThat(response.getPendingConfirmation()).isNull();
    }

    @Test
    @DisplayName("GIC rate question: MCP rate tool and knowledge base tool both invoked -> "
            + "combined answer, both tools logged in invocation order")
    void ask_whenGicRateQuestion_combinesMcpAndKnowledgeBaseAndLogsBothTools() {
        stubChatReply("A one-year GIC currently pays 5.00% and locks in your principal until maturity.", () -> {
            toolSelectionTracker.recordTool("getGicRates");
            toolSelectionTracker.recordTool("searchSavingsKnowledgeBase");
            citationTracker.recordCitation("Savings knowledge base: 07-gics-explained.md");
        });

        ChatQueryResponse response = service.ask(CUSTOMER_ID, ACTOR_ROLE,
                "what's the rate on a one year GIC and is it a good fit for me?");

        assertThat(response.getResponse()).contains("5.00%");
        assertThat(response.isBlocked()).isFalse();

        verify(chatLogRepository).log(eq(CUSTOMER_ID), anyString(), anyString(), anyString(),
                anyBoolean(), anyBoolean(), any(),
                eq(List.of("getGicRates", "searchSavingsKnowledgeBase")));
    }

    @Test
    @DisplayName("Tool selection log captures invocations across all tool families "
            + "(in-process savings tools, transfer tool, MCP) in invocation order")
    void ask_capturesToolsFromAllFamiliesInInvocationOrder() {
        stubChatReply("Here is your balance and today's GIC rate; I've also prepared that transfer for you to confirm.",
                () -> {
                    toolSelectionTracker.recordTool("getAccountSummaries");
                    toolSelectionTracker.recordTool("getGicRates");
                    toolSelectionTracker.recordTool("proposeTransfer");
                });

        service.ask(CUSTOMER_ID, ACTOR_ROLE, "show my balance, today's GIC rate, and move $50 to savings");

        verify(chatLogRepository).log(eq(CUSTOMER_ID), anyString(), anyString(), anyString(),
                anyBoolean(), anyBoolean(), any(),
                eq(List.of("getAccountSummaries", "getGicRates", "proposeTransfer")));
    }

    @Test
    @DisplayName("Scenario (US3): GIC rate MCP tool unreachable -> chat still answers from the "
            + "knowledge base instead of failing the request")
    void ask_whenGicRateMcpToolFails_stillReturnsDegradedAnswerNotError() {
        // Spring AI's default ToolExecutionExceptionProcessor sends a RuntimeException from a tool
        // (in-process or MCP) back to the model as a message rather than failing the request, so from
        // this service's perspective a failed MCP tool call still ends in a normal ChatClient reply -
        // just one that couldn't use the failed tool's output.
        stubChatReply("I couldn't check today's live GIC rates right now, but generally a GIC locks in "
                + "your principal for a fixed term and pays a guaranteed return.", () -> {
            toolSelectionTracker.recordTool("getGicRates");
            toolSelectionTracker.recordTool("searchSavingsKnowledgeBase");
            citationTracker.recordCitation("Savings knowledge base: 07-gics-explained.md");
        });

        ChatQueryResponse response = service.ask(CUSTOMER_ID, ACTOR_ROLE, "what's the GIC rate right now?");

        assertThat(response.isBlocked()).isFalse();
        assertThat(response.getResponse()).doesNotContain("unable to generate a response");
        verify(chatLogRepository).log(eq(CUSTOMER_ID), anyString(), anyString(), anyString(),
                anyBoolean(), anyBoolean(), any(),
                eq(List.of("getGicRates", "searchSavingsKnowledgeBase")));
    }
}
