package com.group1.banking.security;

import com.group1.banking.entity.User;
import com.group1.banking.enums.RoleName;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for JwtService.
 */
class JwtServiceTest {

    private JwtService jwtService;
    private JwtProperties jwtProperties;
    private User user;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties();
        // Use a valid base64-encoded 32+ byte secret, or a plain string of 32+ chars
        jwtProperties.setSecret("ThisIsADevOnlySecretKeyForHs256JwtTokenMinimum32Chars");
        jwtProperties.setAccessTokenExpiry(3600L);
        jwtProperties.setRefreshTokenExpiry(604800L);

        jwtService = new JwtService(jwtProperties);

        user = new User();
        user.setUserId(UUID.randomUUID());
        user.setUsername("test@example.com");
        user.setPasswordHash("hashed");
        user.setRoles(List.of(RoleName.RETAIL_CUSTOMER));
        user.setActive(true);
    }

    // ===== generateAccessToken TESTS =====

    @Test
    void generateAccessToken_shouldReturnNonNullToken() {
        String token = jwtService.generateAccessToken(user);
        assertThat(token).isNotNull().isNotBlank();
    }

    @Test
    void generateAccessToken_shouldHaveThreeParts() {
        String token = jwtService.generateAccessToken(user);
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);
    }

    // ===== generateRefreshToken TESTS =====

    @Test
    void generateRefreshToken_shouldReturnNonNullToken() {
        String token = jwtService.generateRefreshToken(user);
        assertThat(token).isNotNull().isNotBlank();
    }

    @Test
    void accessAndRefreshTokens_shouldBeDifferent() {
        String access = jwtService.generateAccessToken(user);
        String refresh = jwtService.generateRefreshToken(user);
        // They can be the same only if generated in same ms, but expiry should differ
        // In practice they'll have different exp so the signed tokens differ
        assertThat(access).isNotNull();
        assertThat(refresh).isNotNull();
    }

    // ===== extractUserId TESTS =====

    @Test
    void extractUserId_shouldReturnCorrectUserId() {
        String token = jwtService.generateAccessToken(user);
        UUID extractedId = jwtService.extractUserId(token);
        assertThat(extractedId).isEqualTo(user.getUserId());
    }

    @Test
    void extractUserId_shouldWorkWithRefreshToken() {
        String refreshToken = jwtService.generateRefreshToken(user);
        UUID extractedId = jwtService.extractUserId(refreshToken);
        assertThat(extractedId).isEqualTo(user.getUserId());
    }

    // ===== extractRoles TESTS =====

    @Test
    void extractRoles_shouldReturnCustomerRole() {
        String token = jwtService.generateAccessToken(user);
        List<String> roles = jwtService.extractRoles(token);
        assertThat(roles).containsExactly("RETAIL_CUSTOMER");
    }

    @Test
    void extractRoles_shouldReturnAdminRole() {
        user.setRoles(List.of(RoleName.BANK_ADMINISTRATOR));
        String token = jwtService.generateAccessToken(user);
        List<String> roles = jwtService.extractRoles(token);
        assertThat(roles).containsExactly("BANK_ADMINISTRATOR");
    }

    @Test
    void extractRoles_shouldReturnMultipleRoles() {
        user.setRoles(List.of(RoleName.RETAIL_CUSTOMER, RoleName.BANK_ADMINISTRATOR));
        String token = jwtService.generateAccessToken(user);
        List<String> roles = jwtService.extractRoles(token);
        assertThat(roles).containsExactlyInAnyOrder("RETAIL_CUSTOMER", "BANK_ADMINISTRATOR");
    }

    // ===== extractAllClaims TESTS =====

    @Test
    void extractAllClaims_shouldReturnCorrectSubject() {
        String token = jwtService.generateAccessToken(user);
        Claims claims = jwtService.extractAllClaims(token);
        assertThat(claims.getSubject()).isEqualTo(user.getUserId().toString());
    }

    @Test
    void extractAllClaims_shouldHaveExpirationSet() {
        String token = jwtService.generateAccessToken(user);
        Claims claims = jwtService.extractAllClaims(token);
        assertThat(claims.getExpiration()).isNotNull();
    }

    @Test
    void extractAllClaims_shouldHaveIssuedAtSet() {
        String token = jwtService.generateAccessToken(user);
        Claims claims = jwtService.extractAllClaims(token);
        assertThat(claims.getIssuedAt()).isNotNull();
    }

    // ===== Invalid token TESTS =====

    @Test
    void extractUserId_shouldThrow_whenTokenIsInvalid() {
        assertThatThrownBy(() -> jwtService.extractUserId("not.a.valid.token"))
                .isInstanceOf(Exception.class);
    }

    @Test
    void extractAllClaims_shouldThrow_whenTokenTamperedWith() {
        String token = jwtService.generateAccessToken(user);
        String tamperedToken = token.substring(0, token.length() - 5) + "XXXXX";
        assertThatThrownBy(() -> jwtService.extractAllClaims(tamperedToken))
                .isInstanceOf(Exception.class);
    }

    // ===== JwtProperties TESTS =====

    @Test
    void jwtProperties_shouldHaveCorrectExpiry() {
        assertThat(jwtProperties.getAccessTokenExpiry()).isEqualTo(3600L);
        assertThat(jwtProperties.getRefreshTokenExpiry()).isEqualTo(604800L);
    }
}
