package com.group1.banking.repository;

import com.group1.banking.entity.User;
import com.group1.banking.enums.RoleName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository tests for UserRepository using @DataJpaTest.
 */
@DataJpaTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    private User savedUser;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setUsername("test@example.com");
        user.setPasswordHash("$2a$10$hashedpassword");
        user.setRoles(new ArrayList<>(List.of(RoleName.RETAIL_CUSTOMER)));
        user.setActive(true);
        user.setCustomerId(42L);
        savedUser = userRepository.save(user);
    }

    @Test
    void save_shouldPersistUser_withGeneratedId() {
        assertThat(savedUser.getUserId()).isNotNull();
        assertThat(savedUser.getUsername()).isEqualTo("test@example.com");
    }

    @Test
    void existsByUsernameIgnoreCase_shouldReturnTrue_whenExists() {
        boolean exists = userRepository.existsByUsernameIgnoreCase("test@example.com");
        assertThat(exists).isTrue();
    }

    @Test
    void existsByUsernameIgnoreCase_shouldReturnTrue_whenDifferentCase() {
        boolean exists = userRepository.existsByUsernameIgnoreCase("TEST@EXAMPLE.COM");
        assertThat(exists).isTrue();
    }

    @Test
    void existsByUsernameIgnoreCase_shouldReturnFalse_whenNotExists() {
        boolean exists = userRepository.existsByUsernameIgnoreCase("nobody@example.com");
        assertThat(exists).isFalse();
    }

    @Test
    void findByUsernameIgnoreCase_shouldReturnUser_whenExists() {
        Optional<User> found = userRepository.findByUsernameIgnoreCase("TEST@EXAMPLE.COM");
        assertThat(found).isPresent();
        assertThat(found.get().getCustomerId()).isEqualTo(42L);
    }

    @Test
    void findByUsernameIgnoreCase_shouldReturnEmpty_whenNotExists() {
        Optional<User> found = userRepository.findByUsernameIgnoreCase("nobody@example.com");
        assertThat(found).isEmpty();
    }

    @Test
    void findByUsername_shouldReturnUser_whenExactMatch() {
        Optional<User> found = userRepository.findByUsername("test@example.com");
        assertThat(found).isPresent();
    }

    @Test
    void findByUsername_shouldReturnEmpty_whenNotExists() {
        Optional<User> found = userRepository.findByUsername("nobody@example.com");
        assertThat(found).isEmpty();
    }

    @Test
    void findByExternalSubjectId_shouldReturnUser_whenSet() {
        savedUser.setExternalSubjectId("ext-sub-001");
        userRepository.save(savedUser);

        Optional<User> found = userRepository.findByExternalSubjectId("ext-sub-001");
        assertThat(found).isPresent();
        assertThat(found.get().getUsername()).isEqualTo("test@example.com");
    }

    @Test
    void findByExternalSubjectId_shouldReturnEmpty_whenNotSet() {
        Optional<User> found = userRepository.findByExternalSubjectId("nonexistent-ext-id");
        assertThat(found).isEmpty();
    }

    @Test
    void user_roles_shouldBePersisted() {
        Optional<User> found = userRepository.findById(savedUser.getUserId());
        assertThat(found).isPresent();
        assertThat(found.get().getRoles()).containsExactly(RoleName.RETAIL_CUSTOMER);
    }

    @Test
    void user_shouldSupportAdminRole() {
        User admin = new User();
        admin.setUsername("admin@example.com");
        admin.setPasswordHash("$2a$10$adminpasswordhash");
        admin.setRoles(new ArrayList<>(List.of(RoleName.BANK_ADMINISTRATOR)));
        admin.setActive(true);
        User saved = userRepository.save(admin);

        Optional<User> found = userRepository.findById(saved.getUserId());
        assertThat(found).isPresent();
        assertThat(found.get().getRoles()).containsExactly(RoleName.BANK_ADMINISTRATOR);
    }

    @Test
    void findById_shouldReturnEmpty_whenNotExists() {
        Optional<User> found = userRepository.findById(UUID.randomUUID());
        assertThat(found).isEmpty();
    }
}
