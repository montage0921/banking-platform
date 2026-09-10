package com.group1.banking.integration;

import com.group1.banking.DigitalBankingPlatformApplication;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = DigitalBankingPlatformApplication.class)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb",
        "spring.datasource.driverClassName=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class AuthCustomerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper objectMapper;

    @Test
    void registerLoginCreateCustomerAndGetCustomer_asAdmin_success() throws Exception {

        // 1. Register admin user
        HashMap<String, Object> registerBody = new HashMap<>();
        registerBody.put("username", "admin@fdmgroup.com");
        registerBody.put("password", "Secure@123");
        registerBody.put("roles", new String[]{"BANK_ADMINISTRATOR"});

        String registerResponse = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerBody)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode registerJson = objectMapper.readTree(registerResponse);
        assertThat(registerJson.get("username").asText()).isEqualTo("admin@fdmgroup.com");
        assertThat(registerJson.get("roles").get(0).asText()).isEqualTo("BANK_ADMINISTRATOR");

        // 2. Login
        HashMap<String, Object> loginBody = new HashMap<>();
        loginBody.put("username", "admin@fdmgroup.com");
        loginBody.put("password", "Secure@123");

        String loginResponse = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginBody)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode loginJson = objectMapper.readTree(loginResponse);
        String accessToken = loginJson.get("accessToken").asText();
        assertThat(accessToken).isNotBlank();

        // 3. Create customer
        HashMap<String, Object> createCustomerBody = new HashMap<>();
        createCustomerBody.put("name", "Jane Doe");
        createCustomerBody.put("address", "123 Main St, Toronto, ON");
        createCustomerBody.put("type", "PERSON");
        createCustomerBody.put("dateOfBirth", "1990-01-01");

        String createCustomerResponse = mockMvc.perform(post("/api/customers")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createCustomerBody)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode customerJson = objectMapper.readTree(createCustomerResponse);
        Long customerId = customerJson.get("customerId").asLong();

        assertThat(customerId).isNotNull();
        assertThat(customerJson.get("name").asText()).isEqualTo("Jane Doe");

        // 4. Get customer
        mockMvc.perform(get("/api/customers/{customerId}", customerId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());
    }

    @Test
    void registerDuplicateUser_returnsConflict() throws Exception {
        HashMap<String, Object> registerBody = new HashMap<>();
        registerBody.put("username", "tarun@example.com");
        registerBody.put("password", "Secure@123");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerBody)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerBody)))
                .andExpect(status().isConflict());
    }

    @Test
    void loginWrongPassword_returnsUnauthorized() throws Exception {
        HashMap<String, Object> registerBody = new HashMap<>();
        registerBody.put("username", "tarun@example.com");
        registerBody.put("password", "Secure@123");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerBody)))
                .andExpect(status().isCreated());

        HashMap<String, Object> loginBody = new HashMap<>();
        loginBody.put("username", "tarun@example.com");
        loginBody.put("password", "Wrong@123");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginBody)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createCustomerWithoutToken_returnsUnauthorized() throws Exception {
        HashMap<String, Object> createCustomerBody = new HashMap<>();
        createCustomerBody.put("name", "Jane Doe");
        createCustomerBody.put("address", "123 Main St, Toronto, ON");
        createCustomerBody.put("type", "PERSON");

        mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createCustomerBody)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateCustomerWithAdminToken_success() throws Exception {
        // register admin
        HashMap<String, Object> registerBody = new HashMap<>();
        registerBody.put("username", "admin2@fdmgroup.com");
        registerBody.put("password", "Secure@123");
        registerBody.put("roles", new String[]{"BANK_ADMINISTRATOR"});

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerBody)))
                .andExpect(status().isCreated());

        // login
        HashMap<String, Object> loginBody = new HashMap<>();
        loginBody.put("username", "admin2@fdmgroup.com");
        loginBody.put("password", "Secure@123");

        String loginResponse = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginBody)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String accessToken = objectMapper.readTree(loginResponse).get("accessToken").asText();

        // create customer
        HashMap<String, Object> createCustomerBody = new HashMap<>();
        createCustomerBody.put("name", "Original Name");
        createCustomerBody.put("address", "Old Address");
        createCustomerBody.put("type", "PERSON");
        createCustomerBody.put("dateOfBirth", "1990-01-01");

        String createCustomerResponse = mockMvc.perform(post("/api/customers")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createCustomerBody)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Long customerId = objectMapper.readTree(createCustomerResponse).get("customerId").asLong();

        // patch customer
        HashMap<String, Object> patchBody = new HashMap<>();
        patchBody.put("name", "Updated Name");
        patchBody.put("address", "New Address");

        mockMvc.perform(patch("/api/customers/{customerId}", customerId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(patchBody)))
                .andExpect(status().isOk());
    }

    @Test
    void patchCustomerWithBlockedField_returnsBadRequest() throws Exception {
        // register admin
        HashMap<String, Object> registerBody = new HashMap<>();
        registerBody.put("username", "admin3@fdmgroup.com");
        registerBody.put("password", "Secure@123");
        registerBody.put("roles", new String[]{"BANK_ADMINISTRATOR"});

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerBody)))
                .andExpect(status().isCreated());

        // login
        HashMap<String, Object> loginBody = new HashMap<>();
        loginBody.put("username", "admin3@fdmgroup.com");
        loginBody.put("password", "Secure@123");

        String loginResponse = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginBody)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String accessToken = objectMapper.readTree(loginResponse).get("accessToken").asText();

        // create customer
        HashMap<String, Object> createCustomerBody = new HashMap<>();
        createCustomerBody.put("name", "Blocked Field User");
        createCustomerBody.put("address", "Any Address");
        createCustomerBody.put("type", "PERSON");
        createCustomerBody.put("dateOfBirth", "1990-01-01");

        String createCustomerResponse = mockMvc.perform(post("/api/customers")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createCustomerBody)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Long customerId = objectMapper.readTree(createCustomerResponse).get("customerId").asLong();

        // blocked patch
        HashMap<String, Object> patchBody = new HashMap<>();
        patchBody.put("email", "new@example.com");

        mockMvc.perform(patch("/api/customers/{customerId}", customerId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(patchBody)))
                .andExpect(status().isBadRequest());
    }
}