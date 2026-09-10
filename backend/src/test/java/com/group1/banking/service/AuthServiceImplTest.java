package com.group1.banking.service;

import com.group1.banking.dto.auth.AuthResponse;
import com.group1.banking.dto.auth.LoginRequest;
import com.group1.banking.dto.auth.RegisterRequest;
import com.group1.banking.dto.auth.UserResponse;
import com.group1.banking.entity.Customer;
import com.group1.banking.entity.User;
import com.group1.banking.enums.RoleName;
import com.group1.banking.exception.ConflictException;
import com.group1.banking.exception.ForbiddenException;
import com.group1.banking.exception.UnauthorisedException;
import com.group1.banking.mapper.UserMapper;
import com.group1.banking.repository.UserRepository;
import com.group1.banking.security.JwtService;
import com.group1.banking.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private UserMapper userMapper;

    @Mock
    private JwtService jwtService;

    @InjectMocks
    private AuthServiceImpl authService;

    private User savedUser;
    private UserResponse userResponse;

    @BeforeEach
    void setUp() {
        savedUser = new User();
        savedUser.setUserId(UUID.randomUUID());
        savedUser.setUsername("test@example.com");
        savedUser.setPasswordHash("hashed_password");
        savedUser.setRoles(List.of(RoleName.RETAIL_CUSTOMER));
        savedUser.setActive(true);

        userResponse = new UserResponse();
        userResponse.setUserId(savedUser.getUserId());
        userResponse.setUsername(savedUser.getUsername());
        userResponse.setRoles(savedUser.getRoles());
        userResponse.setActive(true);
    }

    // ===== REGISTER TESTS =====

    @Test
    void register_shouldReturnUserResponse_whenValidRequest() {
        RegisterRequest req = new RegisterRequest();
        req.setUsername("Test@Example.com");
        req.setPassword("Secure@123");

        when(userRepository.existsByUsernameIgnoreCase("test@example.com")).thenReturn(false);
        when(passwordEncoder.encode("Secure@123")).thenReturn("hashed_password");
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(userMapper.toResponse(savedUser)).thenReturn(userResponse);

        UserResponse result = authService.register(req);

        assertThat(result).isNotNull();
        assertThat(result.getUsername()).isEqualTo("test@example.com");
        verify(userRepository).save(any(User.class));
    }

    @Test
    void register_shouldTrimAndLowercaseUsername() {
        RegisterRequest req = new RegisterRequest();
        req.setUsername("  TEST@EXAMPLE.COM  ");
        req.setPassword("Secure@123");

        when(userRepository.existsByUsernameIgnoreCase("test@example.com")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(userMapper.toResponse(any())).thenReturn(userResponse);

        authService.register(req);

        verify(userRepository).existsByUsernameIgnoreCase("test@example.com");
    }

    @Test
    void register_shouldThrowConflict_whenUsernameAlreadyExists() {
        RegisterRequest req = new RegisterRequest();
        req.setUsername("existing@example.com");
        req.setPassword("Secure@123");

        when(userRepository.existsByUsernameIgnoreCase("existing@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(req))
                .isInstanceOf(ConflictException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void register_shouldDefaultToCustomerRole_whenNoRolesProvided() {
        RegisterRequest req = new RegisterRequest();
        req.setUsername("newuser@example.com");
        req.setPassword("Secure@123");
        req.setRoles(null);

        when(userRepository.existsByUsernameIgnoreCase(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            assertThat(u.getRoles()).containsExactly(RoleName.RETAIL_CUSTOMER);
            return savedUser;
        });
        when(userMapper.toResponse(any())).thenReturn(userResponse);

        authService.register(req);
        verify(userRepository).save(any(User.class));
    }

    @Test
    void register_shouldUseProvidedRoles_whenRolesSupplied() {
        RegisterRequest req = new RegisterRequest();
        req.setUsername("admin@example.com");
        req.setPassword("Secure@123");
        req.setRoles(List.of(RoleName.BANK_ADMINISTRATOR));

        when(userRepository.existsByUsernameIgnoreCase(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            assertThat(u.getRoles()).containsExactly(RoleName.BANK_ADMINISTRATOR);
            return savedUser;
        });
        when(userMapper.toResponse(any())).thenReturn(userResponse);

        authService.register(req);
    }

    @Test
    void register_shouldHashPassword_beforeSaving() {
        RegisterRequest req = new RegisterRequest();
        req.setUsername("user@example.com");
        req.setPassword("PlainPassword@1");

        when(userRepository.existsByUsernameIgnoreCase(anyString())).thenReturn(false);
        when(passwordEncoder.encode("PlainPassword@1")).thenReturn("bcrypt_hash");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            assertThat(u.getPasswordHash()).isEqualTo("bcrypt_hash");
            return savedUser;
        });
        when(userMapper.toResponse(any())).thenReturn(userResponse);

        authService.register(req);
        verify(passwordEncoder).encode("PlainPassword@1");
    }

    // ===== LOGIN TESTS =====

    @Test
    void login_shouldReturnAuthResponse_whenValidCredentials() {
        LoginRequest req = new LoginRequest();
        req.setUsername("test@example.com");
        req.setPassword("Secure@123");

        when(userRepository.findByUsernameIgnoreCase("test@example.com"))
                .thenReturn(Optional.of(savedUser));
        when(passwordEncoder.matches("Secure@123", "hashed_password")).thenReturn(true);
        when(jwtService.generateAccessToken(savedUser)).thenReturn("access_token");
        when(jwtService.generateRefreshToken(savedUser)).thenReturn("refresh_token");

        AuthResponse result = authService.login(req);

        assertThat(result).isNotNull();
        assertThat(result.getAccessToken()).isEqualTo("access_token");
        assertThat(result.getRefreshToken()).isEqualTo("refresh_token");
        assertThat(result.getTokenType()).isEqualTo("Bearer");
        assertThat(result.getExpiresIn()).isEqualTo(3600);
    }

    @Test
    void login_shouldThrowUnauthorised_whenUserNotFound() {
        LoginRequest req = new LoginRequest();
        req.setUsername("unknown@example.com");
        req.setPassword("Secure@123");

        when(userRepository.findByUsernameIgnoreCase("unknown@example.com"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(req))
                .isInstanceOf(UnauthorisedException.class);
    }

    @Test
    void login_shouldThrowUnauthorised_whenPasswordDoesNotMatch() {
        LoginRequest req = new LoginRequest();
        req.setUsername("test@example.com");
        req.setPassword("WrongPassword@1");

        when(userRepository.findByUsernameIgnoreCase("test@example.com"))
                .thenReturn(Optional.of(savedUser));
        when(passwordEncoder.matches("WrongPassword@1", "hashed_password")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(req))
                .isInstanceOf(UnauthorisedException.class);
    }

    @Test
    void login_shouldThrowForbidden_whenAccountInactive() {
        savedUser.setActive(false);

        LoginRequest req = new LoginRequest();
        req.setUsername("test@example.com");
        req.setPassword("Secure@123");

        when(userRepository.findByUsernameIgnoreCase("test@example.com"))
                .thenReturn(Optional.of(savedUser));
        when(passwordEncoder.matches("Secure@123", "hashed_password")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(req))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void login_shouldNormaliseCaseOfUsername() {
        LoginRequest req = new LoginRequest();
        req.setUsername("  TEST@EXAMPLE.COM  ");
        req.setPassword("Secure@123");

        when(userRepository.findByUsernameIgnoreCase("test@example.com"))
                .thenReturn(Optional.of(savedUser));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        when(jwtService.generateAccessToken(savedUser)).thenReturn("token");
        when(jwtService.generateRefreshToken(savedUser)).thenReturn("refresh");

        authService.login(req);
        verify(userRepository).findByUsernameIgnoreCase("test@example.com");
    }

    // ===== assertCanAccessCustomer TESTS =====

    @Test
    void assertCanAccessCustomer_shouldPass_whenAdmin() {
        com.group1.banking.security.AuthenticatedUser adminUser =
                new com.group1.banking.security.AuthenticatedUser("u1", 10L, List.of("BANK_ADMINISTRATOR"), List.of());

        // Should not throw
        authService.assertCanAccessCustomer(adminUser, 99L);
    }

    @Test
    void assertCanAccessCustomer_shouldPass_whenCustomerOwnsResource() {
        com.group1.banking.security.AuthenticatedUser customerUser =
                new com.group1.banking.security.AuthenticatedUser("u1", 42L, List.of("RETAIL_CUSTOMER"), List.of());

        // Should not throw
        authService.assertCanAccessCustomer(customerUser, 42L);
    }

    @Test
    void assertCanAccessCustomer_shouldThrow_whenCustomerDoesNotOwnResource() {
        com.group1.banking.security.AuthenticatedUser customerUser =
                new com.group1.banking.security.AuthenticatedUser("u1", 42L, List.of("RETAIL_CUSTOMER"), List.of());

        assertThatThrownBy(() -> authService.assertCanAccessCustomer(customerUser, 99L))
                .isInstanceOf(UnauthorisedException.class);
    }

    @Test
    void assertCanAccessCustomer_shouldThrow_whenUserIsNull() {
        assertThatThrownBy(() -> authService.assertCanAccessCustomer(null, 42L))
                .isInstanceOf(UnauthorisedException.class);
    }

    @Test
    void assertCanAccessCustomer_shouldThrow_whenCustomerIdIsNull() {
        com.group1.banking.security.AuthenticatedUser customerUser =
                new com.group1.banking.security.AuthenticatedUser("u1", null, List.of("RETAIL_CUSTOMER"), List.of());

        assertThatThrownBy(() -> authService.assertCanAccessCustomer(customerUser, 42L))
                .isInstanceOf(UnauthorisedException.class);
    }

    // ===== assertCanAccessAccount TESTS =====

    @Test
    void assertCanAccessAccount_shouldPass_whenAdmin() {
        com.group1.banking.security.AuthenticatedUser adminUser =
                new com.group1.banking.security.AuthenticatedUser("u1", null, List.of("BANK_ADMINISTRATOR"), List.of());

        Customer customer = new Customer();
        customer.setCustomerId(42L);
        com.group1.banking.entity.Account account = new com.group1.banking.entity.Account();
        account.setCustomer(customer);

        // Should not throw
        authService.assertCanAccessAccount(adminUser, account);
    }

    @Test
    void assertCanAccessAccount_shouldThrow_whenCustomerDoesNotOwnAccount() {
        com.group1.banking.security.AuthenticatedUser customerUser =
                new com.group1.banking.security.AuthenticatedUser("u1", 10L, List.of("RETAIL_CUSTOMER"), List.of());

        Customer customer = new Customer();
        customer.setCustomerId(42L);
        com.group1.banking.entity.Account account = new com.group1.banking.entity.Account();
        account.setCustomer(customer);

        assertThatThrownBy(() -> authService.assertCanAccessAccount(customerUser, account))
                .isInstanceOf(UnauthorisedException.class);
    }
}
