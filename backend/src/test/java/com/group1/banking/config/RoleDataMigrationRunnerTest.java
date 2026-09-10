package com.group1.banking.config;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class RoleDataMigrationRunnerTest {

    @Test
    void actualUserRolesSchemaHasNoPrimaryKeyOrUniqueConstraint() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:schema-check;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("CREATE TABLE users (user_id UUID PRIMARY KEY, username VARCHAR(255) NOT NULL UNIQUE)");
        jdbcTemplate.execute(
                "CREATE TABLE user_roles (user_id UUID NOT NULL, role_name VARCHAR(64) NOT NULL, " +
                        "CONSTRAINT fk_user_roles_users FOREIGN KEY(user_id) REFERENCES users(user_id))");

        List<String> constraintTypes = jdbcTemplate.queryForList(
                "SELECT CONSTRAINT_TYPE FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE TABLE_NAME = 'USER_ROLES' ORDER BY CONSTRAINT_TYPE",
                String.class);

        assertThat(constraintTypes).containsExactly("FOREIGN KEY");
    }

    @Test
    void freshStartupWithCurrentRolesIsNoop() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:fresh-startup;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        jdbcTemplate.execute("CREATE TABLE users (user_id UUID PRIMARY KEY, username VARCHAR(255) NOT NULL UNIQUE)");
        jdbcTemplate.execute(
                "CREATE TABLE user_roles (" +
                        "user_id UUID NOT NULL, " +
                        "role_name VARCHAR(64) NOT NULL, " +
                        "CONSTRAINT fk_user_roles_users FOREIGN KEY(user_id) REFERENCES users(user_id), " +
                        "CONSTRAINT uq_user_roles_user_role UNIQUE(user_id, role_name))");

        String user1 = "11111111-1111-1111-1111-111111111111";
        String user2 = "22222222-2222-2222-2222-222222222222";
        jdbcTemplate.update("INSERT INTO users (user_id, username) VALUES (?, 'user1')", user1);
        jdbcTemplate.update("INSERT INTO users (user_id, username) VALUES (?, 'user2')", user2);
        jdbcTemplate.update("INSERT INTO user_roles (user_id, role_name) VALUES (?, 'BANK_ADMINISTRATOR')", user1);
        jdbcTemplate.update("INSERT INTO user_roles (user_id, role_name) VALUES (?, 'RETAIL_CUSTOMER')", user2);

        RoleDataMigrationRunner runner = new RoleDataMigrationRunner(jdbcTemplate, dataSource);
        assertThatCode(() -> runner.run(new DefaultApplicationArguments())).doesNotThrowAnyException();

        List<String> roles = jdbcTemplate.queryForList(
                "SELECT user_id || ':' || role_name FROM user_roles ORDER BY user_id, role_name", String.class);
        assertThat(roles).containsExactlyInAnyOrder(
                user1 + ":BANK_ADMINISTRATOR",
                user2 + ":RETAIL_CUSTOMER");
    }

    @Test
    void migrationIsSafeWhenUniqueUserRolePairsArePresent() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:role-migration;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        jdbcTemplate.execute("CREATE TABLE users (user_id UUID PRIMARY KEY, username VARCHAR(255) NOT NULL UNIQUE)");
        jdbcTemplate.execute(
                "CREATE TABLE user_roles (" +
                        "user_id UUID NOT NULL, " +
                        "role_name VARCHAR(64) NOT NULL, " +
                        "CONSTRAINT fk_user_roles_users FOREIGN KEY(user_id) REFERENCES users(user_id), " +
                        "CONSTRAINT uq_user_roles_user_role UNIQUE(user_id, role_name))");

        String user1 = "11111111-1111-1111-1111-111111111111";
        String user2 = "22222222-2222-2222-2222-222222222222";
        String user3 = "33333333-3333-3333-3333-333333333333";
        String user4 = "44444444-4444-4444-4444-444444444444";
        String user5 = "55555555-5555-5555-5555-555555555555";

        jdbcTemplate.update("INSERT INTO users (user_id, username) VALUES (?, 'user1')", user1);
        jdbcTemplate.update("INSERT INTO users (user_id, username) VALUES (?, 'user2')", user2);
        jdbcTemplate.update("INSERT INTO users (user_id, username) VALUES (?, 'user3')", user3);
        jdbcTemplate.update("INSERT INTO users (user_id, username) VALUES (?, 'user4')", user4);
        jdbcTemplate.update("INSERT INTO users (user_id, username) VALUES (?, 'user5')", user5);

        jdbcTemplate.update("INSERT INTO user_roles (user_id, role_name) VALUES (?, 'ADMIN')", user1);
        jdbcTemplate.update("INSERT INTO user_roles (user_id, role_name) VALUES (?, 'ADMIN')", user2);
        jdbcTemplate.update("INSERT INTO user_roles (user_id, role_name) VALUES (?, 'ADMIN')", user3);
        jdbcTemplate.update("INSERT INTO user_roles (user_id, role_name) VALUES (?, 'BANK_ADMINISTRATOR')", user3);
        jdbcTemplate.update("INSERT INTO user_roles (user_id, role_name) VALUES (?, 'CUSTOMER')", user4);
        jdbcTemplate.update("INSERT INTO user_roles (user_id, role_name) VALUES (?, 'CUSTOMER')", user5);
        jdbcTemplate.update("INSERT INTO user_roles (user_id, role_name) VALUES (?, 'RETAIL_CUSTOMER')", user5);

        RoleDataMigrationRunner runner = new RoleDataMigrationRunner(jdbcTemplate, dataSource);

        assertThatCode(() -> runner.run(new DefaultApplicationArguments())).doesNotThrowAnyException();

        List<String> afterRunRoles = jdbcTemplate.queryForList(
                "SELECT user_id || ':' || role_name FROM user_roles ORDER BY user_id, role_name", String.class);
        assertThat(afterRunRoles).containsExactlyInAnyOrder(
                user1 + ":BANK_ADMINISTRATOR",
                user2 + ":BANK_ADMINISTRATOR",
                user3 + ":BANK_ADMINISTRATOR",
                user4 + ":RETAIL_CUSTOMER",
                user5 + ":RETAIL_CUSTOMER");

        assertThatCode(() -> runner.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
        assertThat(jdbcTemplate.queryForList(
                "SELECT user_id || ':' || role_name FROM user_roles ORDER BY user_id, role_name", String.class))
                .isEqualTo(afterRunRoles);
    }

    @Test
    void postgresRoleNameEnumMigrationConvertsPreservingExistingValues() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:postgres-like-migration;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        jdbcTemplate.execute("CREATE TABLE users (user_id UUID PRIMARY KEY, username VARCHAR(255) NOT NULL UNIQUE)");
        jdbcTemplate.execute(
                "CREATE TABLE user_roles (" +
                        "user_id UUID NOT NULL, " +
                        "role_name VARCHAR(64) NOT NULL, " +
                        "CONSTRAINT fk_user_roles_users FOREIGN KEY(user_id) REFERENCES users(user_id))");
        jdbcTemplate.update("INSERT INTO users (user_id, username) VALUES (?, 'user1')", "11111111-1111-1111-1111-111111111111");
        jdbcTemplate.update("INSERT INTO user_roles (user_id, role_name) VALUES (?, 'ADMIN')", "11111111-1111-1111-1111-111111111111");

        RoleDataMigrationRunner runner = new RoleDataMigrationRunner(jdbcTemplate, dataSource);
        assertThatCode(() -> runner.run(new DefaultApplicationArguments())).doesNotThrowAnyException();

        String storedRole = jdbcTemplate.queryForObject(
                "SELECT role_name FROM user_roles WHERE user_id = ?",
                String.class,
                "11111111-1111-1111-1111-111111111111");
        assertThat(storedRole).isEqualTo("BANK_ADMINISTRATOR");
    }
}
