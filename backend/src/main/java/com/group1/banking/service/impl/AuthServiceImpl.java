package com.group1.banking.service.impl;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.group1.banking.dto.auth.AuthResponse;
import com.group1.banking.dto.auth.LoginRequest;
import com.group1.banking.dto.auth.RegisterRequest;
import com.group1.banking.dto.auth.UserResponse;
import com.group1.banking.entity.Account;
import com.group1.banking.entity.User;
import com.group1.banking.enums.RoleName;
import com.group1.banking.exception.ConflictException;
import com.group1.banking.exception.ForbiddenException;
import com.group1.banking.exception.UnauthorisedException;
import com.group1.banking.mapper.UserMapper;
import com.group1.banking.repository.UserRepository;
import com.group1.banking.security.AuthenticatedUser;
import com.group1.banking.security.JwtService;
import com.group1.banking.service.AuthService;

@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthServiceImpl.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserMapper userMapper;
    private final JwtService jwtService;

    public AuthServiceImpl(UserRepository userRepository,
                           PasswordEncoder passwordEncoder,
                           UserMapper userMapper,
                           JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.userMapper = userMapper;
        this.jwtService = jwtService;
    }

    @Override
    public UserResponse register(RegisterRequest req) {
        String normalizedUsername = req.getUsername().trim().toLowerCase();
        logger.info("Register request received for username={}", normalizedUsername);

        if (userRepository.existsByUsernameIgnoreCase(normalizedUsername)) {
            logger.warn("Registration failed. User already exists for username={}", normalizedUsername);
            throw new ConflictException("USER_ALREADY_EXISTS", "A user with this email is already registered.", normalizedUsername);
        }

        List<RoleName> requestedRoles =
                (req.getRoles() == null || req.getRoles().isEmpty())
                        ? List.of(RoleName.RETAIL_CUSTOMER)
                        : req.getRoles();

        User user = new User();
        user.setUsername(normalizedUsername);
        user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        user.setRoles(requestedRoles);
        user.setActive(true);
        user.setExternalSubjectId(null);
        user.setCustomerId(null);

        User savedUser = userRepository.save(user);
        logger.info("User registered successfully. userId={}, username={}, roles={}",
                savedUser.getUserId(), savedUser.getUsername(), savedUser.getRoles());

        return userMapper.toResponse(savedUser);
    }

    @Override
    public AuthResponse login(LoginRequest req) {
        String username = req.getUsername().trim().toLowerCase();
        logger.info("Login request received for username={}", username);

        User user = userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> {
                    logger.warn("Login failed. User not found for username={}", username);
                    return new UnauthorisedException("INVALID_CREDENTIALS", "Invalid credentials.");
                });

        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            logger.warn("Login failed. Invalid password for username={}", username);
            throw new UnauthorisedException("INVALID_CREDENTIALS", "Invalid credentials.");
        }

        if (!user.isActive()) {
            logger.warn("Login blocked. Inactive account for username={}", username);
            throw new ForbiddenException("ACCOUNT_INACTIVE", "Account is inactive.");
        }

        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);

        logger.info("Login successful for userId={}, username={}", user.getUserId(), user.getUsername());
        return new AuthResponse(accessToken, refreshToken, "Bearer", 3600);
    }
    
    public void assertCanAccessCustomer(AuthenticatedUser user, Long customerId) {
        if (user == null) {
            throw new UnauthorisedException("UNAUTHORIZED", "Unauthorized.");
        }
        if (user.isAdmin()) {
            return;
        }
        if (user.customerId() == null || !user.customerId().equals(customerId)) {
            throw new UnauthorisedException("UNAUTHORIZED", "Unauthorized.");
        }
    }

    public void assertCanAccessAccount(AuthenticatedUser user, Account account) {
        assertCanAccessCustomer(user, account.getCustomer().getCustomerId());
    }
}