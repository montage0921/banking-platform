package com.group1.banking.service.impl;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;

import com.group1.banking.dto.chat.ChatQueryResponse;
import com.group1.banking.entity.AuditEventType;
import com.group1.banking.entity.AuditOutcome;
import com.group1.banking.enums.RoleName;
import com.group1.banking.repository.ChatInteractionLogRepository;
import com.group1.banking.service.AuditService;
import com.group1.banking.service.impl.SavingsChatGuardrailService.GuardrailResult;

/**
 * Orchestrates a single Savings Insight Chatbot turn (T-BRD 6.1):
 * guardrail check -> agentic Groq chat call (the model calls {@link SavingsChatTools}
 * itself, as many times as it needs, to look up account/spending/goal data or search
 * the knowledge base) -> interaction logging.
 *
 * Unlike a fixed retrieve-then-generate pipeline, this service does not decide up
 * front what data the model needs - it just binds the customer's identity into the
 * {@code ToolContext} (so tools can never be tricked into fetching another customer's
 * data) and lets the model decide which tools, if any, to call. Which data sources
 * were actually used is reconstructed afterwards via {@link SavingsChatCitationTracker}.
 *
 * Conversation memory: the {@code ChatClient} bean carries a {@code MessageChatMemoryAdvisor}
 * (see {@code ChatbotAiConfig}), and every call here binds {@link ChatMemory#CONVERSATION_ID}
 * to the customer's ID, so follow-up questions in the same conversation ("is that a lot?")
 * resolve against prior turns. Memory is per-customer (not per browser session), holds the
 * last ~20 messages (tool-call/tool-response messages are never persisted to it - only the
 * user's questions and the model's final replies), and lives in-process only - it resets on
 * app restart and isn't shared across instances. Guardrail-blocked turns never reach the
 * chat client, so they're never added to memory.
 *
 * Audit logging (CFG-03): every path (blocked, error, answered, fallback) is also
 * recorded through the shared {@link AuditService} - action {@code CHATBOT_QUERY},
 * the caller's customer ID and role as actor, and the same outcome value used in the
 * chat-specific log below. The audit row's resourceId points at the corresponding
 * {@code chat_interaction_log} row (see {@link ChatInteractionLogRepository#log}),
 * since the shared audit_log table is meant as a lightweight actor/action/outcome
 * trail, not a place for full query/response text or the list of sources consulted -
 * that detail lives in the chat-specific log and is reachable by following
 * resourceId. "Intent" is not yet a separate classification step in this service
 * (the model decides tool calls and drafts the reply in one pass), so action stays a
 * fixed code identifying the request type rather than a free-text intent description.
 * A failure writing the audit entry is logged but never allowed to fail the chat
 * response itself - see {@link #audit}.
 *
 * Every path (blocked, fallback, answered, error) returns a normal 200 response with
 * a controlled message rather than an HTTP error, since a chatbot declining or
 * hedging is a normal conversational outcome, not a failure.
 */
@Service
public class SavingsInsightChatService {

    private static final Logger log = LoggerFactory.getLogger(SavingsInsightChatService.class);
    private static final String CUSTOMER_ID_KEY = "customerId";
    private static final String AUDIT_SOURCE_FEATURE = "SAVINGS_INSIGHT_CHATBOT";
    private static final String AUDIT_RESOURCE_TYPE = "CHATBOT_INTERACTION";
    private static final String ACTOR_ROLE_KEY = "actorRole";

    private static final String GENERATION_ERROR_MESSAGE =
            "I'm unable to generate a response right now. Please try again in a moment.";

    private final SavingsChatGuardrailService guardrailService;
    private final ChatClient chatClient;
    private final ChatInteractionLogRepository chatLogRepository;
    private final SavingsChatCitationTracker citationTracker;
    private final PendingActionTracker pendingActionTracker;
    private final ToolSelectionTracker toolSelectionTracker;
    private final AuditService auditService;

    public SavingsInsightChatService(SavingsChatGuardrailService guardrailService,
                                      ChatClient chatClient,
                                      ChatInteractionLogRepository chatLogRepository,
                                      SavingsChatCitationTracker citationTracker,
                                      PendingActionTracker pendingActionTracker,
                                      ToolSelectionTracker toolSelectionTracker,
                                      AuditService auditService) {
        this.guardrailService = guardrailService;
        this.chatClient = chatClient;
        this.chatLogRepository = chatLogRepository;
        this.citationTracker = citationTracker;
        this.pendingActionTracker = pendingActionTracker;
        this.toolSelectionTracker = toolSelectionTracker;
        this.auditService = auditService;
    }

    public ChatQueryResponse ask(Long customerId, String actorRole, String rawMessage) {
        GuardrailResult guardrail = guardrailService.evaluate(rawMessage);
        if (!guardrail.allowed()) {
            // Blocked before any tool could run, so nothing was retrieved. This is a
            // refusal rather than a fallback: the customer is told the topic is out of
            // scope, not given degraded guidance.
            Long chatLogId = chatLogRepository.log(customerId, safe(rawMessage), guardrail.declineMessage(),
                    "GUARDRAIL_BLOCKED", false, false, List.of(), List.of());
            audit(customerId, actorRole, chatLogId, "GUARDRAIL_BLOCKED", List.of());
            return new ChatQueryResponse(guardrail.declineMessage(), List.of(), false, true);
        }

        citationTracker.reset();
        pendingActionTracker.reset();
        toolSelectionTracker.reset();
        String reply;
        try {
            reply = chatClient.prompt()
                    .user(rawMessage)
                    .toolContext(Map.of(CUSTOMER_ID_KEY, customerId, ACTOR_ROLE_KEY, actorRole))
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, customerId.toString()))
                    .call()
                    .content();
        } catch (Exception ex) {
            log.error("Chat model call failed for customer {}", customerId, ex);
            // Drain (not just reset) before clearing: any tool that ran before the
            // failure is still meaningful for the audit trail - it shows how far the
            // turn got, not just that it failed.
            List<String> toolsUsedBeforeFailure = toolSelectionTracker.drainToolsUsed();
            citationTracker.reset();
            pendingActionTracker.reset();
            toolSelectionTracker.reset();
            // Retrieval may have partially succeeded before the failure, but nothing from
            // it reached the customer, so this is logged as no retrieval + fallback.
            Long chatLogId = chatLogRepository.log(customerId, rawMessage, GENERATION_ERROR_MESSAGE,
                    "ERROR", false, true, List.of(), List.of());
            audit(customerId, actorRole, chatLogId, "ERROR", toolsUsedBeforeFailure);
            return new ChatQueryResponse(GENERATION_ERROR_MESSAGE, List.of(), true, false);
        }

        List<String> basedOn = citationTracker.drainCitations();
        boolean limitedData = !citationTracker.drainUsedPersonalData();
        // Distinct from limitedData on purpose: a knowledge-base-only answer retrieved
        // real content but is still non-personalized, so it is retrieval + fallback.
        boolean retrievalOccurred = !basedOn.isEmpty();
        List<String> toolsUsed = toolSelectionTracker.drainToolsUsed();
        String outcome = limitedData ? "FALLBACK" : "ANSWERED";

        Long chatLogId = chatLogRepository.log(customerId, rawMessage, reply, outcome,
            retrievalOccurred, limitedData, basedOn, toolsUsed);
        audit(customerId, actorRole, chatLogId, outcome, toolsUsed);

        ChatQueryResponse response = new ChatQueryResponse(reply, basedOn, limitedData, false);
        pendingActionTracker.drainProposal().ifPresent(response::setPendingConfirmation);
        return response;
    }

    /**
     * Records the outcome of one chatbot turn through the shared audit capability
     * (CFG-03). As of the Agent-orchestration ticket (Scenario 5: "selected Tool(s)...
     * logged through the shared audit/event logging capability"), the tool names this
     * turn selected are folded into eventDetails directly, rather than only being
     * reachable by following subject_id to the chat-specific log - so a reviewer can
     * see what ran without a join. What is deliberately NOT duplicated here: this
     * service has no distinct "intent" classification step to log (the model decides
     * tool calls and drafts the reply in one pass - see the class-level note above), so
     * inventing a label here would be fiction, not an audit fact; and the full
     * query/response text and citation list stay in the chat-specific log only,
     * consistent with audit_log being a lightweight trail rather than a content store.
     */
    private void audit(Long customerId, String actorRole, Long chatLogId, String outcome, List<String> toolsUsed) {
        try {
            String eventDetails = toolsUsed.isEmpty() ? outcome : outcome + "; tools=" + toolsUsed;
            auditService.log(
                    AuditEventType.CHATBOT_QUERY_ANSWERED,
                    AUDIT_SOURCE_FEATURE,
                    roleFor(actorRole),
                    customerId.toString(),
                    AUDIT_RESOURCE_TYPE,
                    chatLogId == null ? null : chatLogId.toString(),
                    outcomeFor(outcome),
                    eventDetails);
        } catch (Exception ex) {
            // The shared audit trail matters, but it must never take down a chat
            // response that already succeeded (or already failed for its own reason).
            log.error("Failed to write audit log entry for customer {} chat turn", customerId, ex);
        }
    }

    private static AuditOutcome outcomeFor(String outcome) {
        return switch (outcome) {
            case "ANSWERED", "FALLBACK" -> AuditOutcome.SUCCESS;
            case "GUARDRAIL_BLOCKED" -> AuditOutcome.DENIED;
            default -> AuditOutcome.ERROR;
        };
    }

    private static RoleName roleFor(String actorRole) {
        if (actorRole == null) {
            return RoleName.RETAIL_CUSTOMER;
        }
        try {
            return RoleName.valueOf(actorRole.replace("ROLE_", "").toUpperCase());
        } catch (IllegalArgumentException ex) {
            return RoleName.RETAIL_CUSTOMER;
        }
    }

    private String safe(String message) {
        return message == null ? "" : message;
    }
}
