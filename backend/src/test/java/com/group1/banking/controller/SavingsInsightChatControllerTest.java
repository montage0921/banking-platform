package com.group1.banking.controller;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.json.JsonMapper;

import com.group1.banking.dto.chat.ChatQueryRequest;
import com.group1.banking.dto.chat.ChatQueryResponse;
import com.group1.banking.repository.UserRepository;
import com.group1.banking.security.JwtService;
import com.group1.banking.service.impl.SavingsInsightChatService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SavingsInsightChatController.class)
@AutoConfigureMockMvc(addFilters = false)
class SavingsInsightChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper objectMapper;

    @MockitoBean
    private SavingsInsightChatService savingsInsightChatService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    @WithCustomUser(customerId = 42L)
    void askSavingsInsight_validRequest_returns200WithResponse() throws Exception {
        ChatQueryResponse serviceResponse = new ChatQueryResponse(
                "Based on your dining spend over the last 30 days, you could save by cooking in more.",
                List.of("Your recent transaction history", "Savings knowledge base: 04-reducing-discretionary-spend.md"),
                false, false);
        // @WithCustomUser uses the retail customer role, so actor extraction resolves to that value.
        when(savingsInsightChatService.ask(eq(42L), eq("RETAIL_CUSTOMER"), any())).thenReturn(serviceResponse);

        mockMvc.perform(post("/api/chat/savings-insights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChatQueryRequest("How can I save more on dining out?"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response").value(serviceResponse.getResponse()))
                .andExpect(jsonPath("$.limited_data").value(false))
                .andExpect(jsonPath("$.blocked").value(false))
                .andExpect(jsonPath("$.based_on").isArray());

        verify(savingsInsightChatService).ask(eq(42L), eq("RETAIL_CUSTOMER"), eq("How can I save more on dining out?"));
    }

    @Test
    @WithCustomUser
    void askSavingsInsight_blockedTopic_returns200WithBlockedFlag() throws Exception {
        ChatQueryResponse serviceResponse = new ChatQueryResponse(
                "I can't help with that topic. Please speak with a licensed advisor.",
                List.of(), false, true);
        when(savingsInsightChatService.ask(anyLong(), anyString(), any())).thenReturn(serviceResponse);

        mockMvc.perform(post("/api/chat/savings-insights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChatQueryRequest("Which stock should I buy?"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocked").value(true));
    }

    @Test
    @WithCustomUser
    void askSavingsInsight_blankMessage_returns422() throws Exception {
        mockMvc.perform(post("/api/chat/savings-insights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChatQueryRequest("   "))))
                .andExpect(status().isUnprocessableEntity());
    }
}
