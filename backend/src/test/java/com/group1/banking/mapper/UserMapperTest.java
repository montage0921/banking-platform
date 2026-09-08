package com.group1.banking.mapper;

import com.group1.banking.dto.auth.UserResponse;
import com.group1.banking.entity.User;
import com.group1.banking.enums.RoleName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UserMapperTest {

    private UserMapper userMapper;

    @BeforeEach
    void setUp() {
        userMapper = new UserMapper();
    }

    @Test
    void toResponse_shouldMapUserId() {
        User user = buildUser();
        UserResponse response = userMapper.toResponse(user);
        assertThat(response.getUserId()).isEqualTo(user.getUserId());
    }

    @Test
    void toResponse_shouldMapUsername() {
        User user = buildUser();
        UserResponse response = userMapper.toResponse(user);
        assertThat(response.getUsername()).isEqualTo("alice@example.com");
    }

    @Test
    void toResponse_shouldMapRoles() {
        User user = buildUser();
        UserResponse response = userMapper.toResponse(user);
        assertThat(response.getRoles()).containsExactly(RoleName.RETAIL_CUSTOMER);
    }

    @Test
    void toResponse_shouldMapCustomerId() {
        User user = buildUser();
        UserResponse response = userMapper.toResponse(user);
        assertThat(response.getCustomerId()).isEqualTo(42L);
    }

    @Test
    void toResponse_shouldMapIsActive() {
        User user = buildUser();
        UserResponse response = userMapper.toResponse(user);
        assertThat(response.isActive()).isTrue();
    }

    @Test
    void toResponse_shouldMapExternalSubjectId() {
        User user = buildUser();
        UserResponse response = userMapper.toResponse(user);
        assertThat(response.getExternalSubjectId()).isEqualTo("ext-sub-123");
    }

    private User buildUser() {
        User user = new User();
        user.setUserId(UUID.randomUUID());
        user.setUsername("alice@example.com");
        user.setPasswordHash("hash");
        user.setRoles(new ArrayList<>(List.of(RoleName.RETAIL_CUSTOMER)));
        user.setCustomerId(42L);
        user.setActive(true);
        user.setExternalSubjectId("ext-sub-123");
        return user;
    }
}
