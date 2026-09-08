package com.group1.banking.service.impl;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;

import com.group1.banking.dto.chat.ChatQueryResponse;
import com.group1.banking.repository.ChatInteractionLogRepository;
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
//  * (see {@code ChatbotAiConfig}), and every call here binds {@link ChatMemory#CONVERSATION_ID}
 * to the customer's ID, so follow-up questions in the same conversation ("is that a lot?")
 * resolve against prior turns. Memory is per-customer (not per browser session), holds the
 * last ~20 messages (tool-call/tool-response messages are never persisted to it - only the
 * user's questions and the model's final replies), and lives in-process only - it resets on
 * app restart and isn't shared across instances. Guardrail-blocked turns never reach the
 * chat client, so they're never added to memory.
 *
 * Every path (blocked, fallback, answered, error) returns a normal 200 response with
 * a controlled message rather than an HTTP error, since a chatbot declining or
 * hedging is a normal conversational outcome, not a failure.
 */
@Service
public class SavingsInsightChatService {

    private static final Logger log = LoggerFactory.getLogger(SavingsInsightChatService.class);
    private static final String CUSTOMER_ID_KEY = "customerId";

    private static final String GENERATION_ERROR_MESSAGE =
            "I'm unable to generate a response right now. Please try again in a moment.";

    private final SavingsChatGuardrailService guardrailService;
    private final ChatClient chatClient;
    private final ChatInteractionLogRepository chatLogRepository;
    private final SavingsChatCitationTracker citationTracker;

    public SavingsInsightChatService(SavingsChatGuardrailService guardrailService,
                                      ChatClient chatClient,
                                      ChatInteractionLogRepository chatLogRepository,
                                      SavingsChatCitationTracker citationTracker) {
        this.guardrailService = guardrailService;
        this.chatClient = chatClient;
        this.chatLogRepository = chatLogRepository;
        this.citationTracker = citationTracker;
    }

    public ChatQueryResponse ask(Long customerId, String rawMessage) {
        GuardrailResult guardrail = guardrailService.evaluate(rawMessage);
        if (!guardrail.allowed()) {
            // Blocked before any tool could run, so nothing was retrieved. This is a
            // refusal rather than a fallback: the customer is told the topic is out of
            // scope, not given degraded guidance.
            chatLogRepository.log(customerId, safe(rawMessage), guardrail.declineMessage(),
                    "GUARDRAIL_BLOCKED", false, false, List.of());
            return new ChatQueryResponse(guardrail.declineMessage(), List.of(), false, true);
        }

        citationTracker.reset();
        String reply;
        try {
            reply = chatClient.prompt()
                    .user(rawMessage)
                    .toolContext(Map.of(CUSTOMER_ID_KEY, customerId))
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, customerId.toString()))
                    .call()
                    .content();
        } catch (Exception ex) {
            log.error("Chat model call failed for customer {}", customerId, ex);
            citationTracker.reset();
            // Retrieval may have partially succeeded before the failure, but nothing from
            // it reached the customer, so this is logged as no retrieval + fallback.
            chatLogRepository.log(customerId, rawMessage, GENERATION_ERROR_MESSAGE,
                    "ERROR", false, true, List.of());
            return new ChatQueryResponse(GENERATION_ERROR_MESSAGE, List.of(), true, false);
        }

        List<String> basedOn = citationTracker.drainCitations();
        boolean limitedData = !citationTracker.drainUsedPersonalData();
        // Distinct from limitedData on purpose: a knowledge-base-only answer retrieved
        // real content but is still non-personalized, so it is retrieval + fallback.
        boolean retrievalOccurred = !basedOn.isEmpty();
        String outcome = limitedData ? "FALLBACK" : "ANSWERED";

        chatLogRepository.log(customerId, rawMessage, reply, outcome,
                retrievalOccurred, limitedData, basedOn);

        return new ChatQueryResponse(reply, basedOn, limitedData, false);
    }

    private String safe(String message) {
        return message == null ? "" : message;
    }
}
