package com.group1.banking.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;

@Component
public class RoleDataMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RoleDataMigrationRunner.class);

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    public RoleDataMigrationRunner(@Qualifier("primaryJdbcTemplate") JdbcTemplate jdbcTemplate,
                                   @Qualifier("dataSource") DataSource dataSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        // This runner is for the primary banking database only; the chatbot database
        // has its own qualified JdbcTemplate and must never be migrated here.
        if (!tableExists("USER_ROLES")) {
            return;
        }

        // Widen before replacing legacy values so the canonical role names always fit.
        widenRoleColumnForExistingValues();
        migrateLegacyRoles("ADMIN", "BANK_ADMINISTRATOR");
        migrateLegacyRoles("CUSTOMER", "RETAIL_CUSTOMER");
    }

    private void migrateLegacyRoles(String legacyRole, String replacementRole) {
        // Insert only missing canonical pairs first. This remains safe if a unique
        // (user_id, role_name) constraint exists and makes repeated startups idempotent.
        jdbcTemplate.update(
                "INSERT INTO user_roles (user_id, role_name) "
                        + "SELECT DISTINCT legacy.user_id, ? "
                        + "FROM user_roles legacy "
                        + "WHERE legacy.role_name = ? "
                        + "AND NOT EXISTS (SELECT 1 FROM user_roles canonical "
                        + "WHERE canonical.user_id = legacy.user_id "
                        + "AND canonical.role_name = ?)",
                replacementRole, legacyRole, replacementRole);

        jdbcTemplate.update(
                "DELETE FROM user_roles WHERE role_name = ?",
                legacyRole);
    }

    private void widenRoleColumnForExistingValues() {
        try (var connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            String productName = metadata.getDatabaseProductName();
            String url = connection.getMetaData().getURL();
            String databaseType = detectDatabaseType(productName, url);

            log.info("RoleDataMigrationRunner detected databaseType={} productName={} url={}",
                    databaseType, productName, url);

                // ALTER COLUMN syntax differs across supported database engines.
            if (databaseType.equals("H2")) {
                jdbcTemplate.execute("ALTER TABLE user_roles ALTER COLUMN role_name VARCHAR(64)");
                return;
            }

            if (databaseType.equals("MYSQL")) {
                jdbcTemplate.execute("ALTER TABLE user_roles MODIFY role_name VARCHAR(64) NOT NULL");
                return;
            }

            if (databaseType.equals("POSTGRESQL")) {
                String dataType = jdbcTemplate.queryForObject(
                        "SELECT data_type FROM information_schema.columns " +
                                "WHERE table_name = 'user_roles' AND column_name = 'role_name' AND table_schema = current_schema()",
                        String.class);

                log.info("RoleDataMigrationRunner PostgreSQL column type for user_roles.role_name: {}", dataType);

                // PostgreSQL enum columns need an explicit cast when converted to text.
                if (dataType == null || dataType.equalsIgnoreCase("character varying") || dataType.equalsIgnoreCase("varchar")) {
                    return;
                }

                jdbcTemplate.execute(
                        "ALTER TABLE user_roles ALTER COLUMN role_name TYPE VARCHAR(64) USING role_name::text");
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Unable to prepare the user_roles role column", ex);
        }
    }

    private String detectDatabaseType(String productName, String url) {
        String normalizedProductName = productName == null ? "" : productName.toLowerCase();
        String normalizedUrl = url == null ? "" : url.toLowerCase();

        if (normalizedProductName.contains("h2") || normalizedUrl.contains("h2:")) {
            return "H2";
        }
        if (normalizedProductName.contains("mysql") || normalizedUrl.contains("mysql:")) {
            return "MYSQL";
        }
        if (normalizedProductName.contains("postgresql") || normalizedUrl.contains("postgresql:")) {
            return "POSTGRESQL";
        }
        if (normalizedUrl.contains("jdbc:postgresql")) {
            return "POSTGRESQL";
        }
        return "UNKNOWN";
    }

    private boolean tableExists(String tableName) {
        try (var connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet tables = metadata.getTables(null, null, tableName, new String[]{"TABLE"})) {
                if (tables.next()) {
                    return true;
                }
            }
            try (ResultSet tables = metadata.getTables(null, null, tableName.toLowerCase(), new String[]{"TABLE"})) {
                return tables.next();
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Unable to inspect the user_roles table", ex);
        }
    }
}
