package com.group1.banking.controller;

import tools.jackson.databind.json.JsonMapper;
import com.group1.banking.dto.auth.AuthResponse;
import com.group1.banking.dto.auth.LoginRequest;
import com.group1.banking.dto.auth.RegisterRequest;
import com.group1.banking.dto.auth.UserResponse;
import com.group1.banking.enums.RoleName;
import com.group1.banking.repository.UserRepository;
import com.group1.banking.security.JwtService;
import com.group1.banking.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Controller tests for AuthController using @WebMvcTest.
 * Security filters are disabled to focus on controller logic.
 */
@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper objectMapper;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserRepository userRepository;

    // ===== REGISTER =====

    @Test
    void register_shouldReturn201_whenValidRequest() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("jane@example.com");
        request.setPassword("Secure@123");

        UserResponse response = new UserResponse();
        response.setUserId(UUID.randomUUID());
        response.setUsername("jane@example.com");
        response.setRoles(List.of(RoleName.RETAIL_CUSTOMER));
        response.setActive(true);
        response.setCreatedAt(Instant.now());

        when(authService.register(any(RegisterRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("jane@example.com"));
    }

    @Test
    void register_shouldReturn400_whenMissingUsername() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setPassword("Secure@123");
        // username not set

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void register_shouldReturn400_whenInvalidEmail() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("not-an-email");
        request.setPassword("Secure@123");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void register_shouldReturn400_whenWeakPassword() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("jane@example.com");
        request.setPassword("weakpassword"); // no uppercase, digit, special char

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void register_shouldReturn400_whenMissingPassword() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("jane@example.com");
        // password not set

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void register_shouldNotContainPasswordHash_inResponse() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("jane@example.com");
        request.setPassword("Secure@123");

        UserResponse response = new UserResponse();
        response.setUserId(UUID.randomUUID());
        response.setUsername("jane@example.com");
        response.setRoles(List.of(RoleName.RETAIL_CUSTOMER));
        response.setActive(true);

        when(authService.register(any(RegisterRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    // ===== LOGIN =====

    @Test
    void login_shouldReturn200_whenValidCredentials() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setUsername("jane@example.com");
        request.setPassword("Secure@123");

        AuthResponse response = AuthResponse.builder()
                .accessToken("access-token-xyz")
                .refreshToken("refresh-token-xyz")
                .tokenType("Bearer")
                .expiresIn(3600L)
                .build();

        when(authService.login(any(LoginRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-token-xyz"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600));
    }

    @Test
    void login_shouldReturn400_whenMissingUsername() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setPassword("Secure@123");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void login_shouldReturn400_whenMissingPassword() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setUsername("jane@example.com");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void login_shouldReturn400_whenInvalidEmailFormat() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setUsername("not-an-email");
        request.setPassword("Secure@123");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableContent());
    }
}
