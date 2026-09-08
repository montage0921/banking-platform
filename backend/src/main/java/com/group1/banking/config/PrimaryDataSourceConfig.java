package com.group1.banking.config;

import javax.sql.DataSource;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The application's primary datasource: the H2/MySQL database holding all core banking
 * data (customers, accounts, transactions, users, roles, savings goals, risk scores...).
 * Bound to the standard {@code spring.datasource.*} properties.
 *
 * Why this has to be declared explicitly
 *
 * Spring Boot normally auto-configures this bean, so declaring it by hand would be
 * redundant - except that auto-configuration switches itself off as soon as the context
 * contains <em>any</em> {@link DataSource} bean:
 *
 * <pre>
 * DataSourceAutoConfiguration.PooledDataSourceConfiguration
 *     &#64;ConditionalOnMissingBean({ DataSource.class, XADataSource.class })
 * </pre>
 *
 * {@link ChatbotDataSourceConfig} declares {@code chatbotVectorDataSource} (the chatbot's
 * separate Postgres/pgvector database), which trips that condition. The primary datasource
 * was therefore never created at all, leaving the chatbot's Postgres instance as the only
 * {@code DataSource} in the context - so JPA bound to it and, with
 * {@code spring.jpa.hibernate.ddl-auto=update}, Hibernate created a table there for every
 * {@code @Entity} in the application and wrote live banking data into the chatbot database.
 *
 * Declaring both datasources explicitly and marking this one {@link Primary} is the
 * documented way to run two datasources: JPA, the transaction manager and the
 * auto-configured {@code JdbcTemplate} all resolve to the primary, while the chatbot's
 * beans reach their own datasource through {@code @Qualifier}.
 *
 * Do not remove this class while {@link ChatbotDataSourceConfig} exists.
 * Deleting it silently re-points the entire application at the chatbot's database rather
 * than failing fast.
 */
@Configuration
public class PrimaryDataSourceConfig {

    /**
     * Built from the auto-registered {@link DataSourceProperties} bean (bound to
     * {@code spring.datasource.*} by {@code DataSourceAutoConfiguration}, whose outer
     * class still applies - only the nested datasource-creating configuration backed off).
     *
     * {@code @ConfigurationProperties} on this method preserves Boot's usual behaviour of
     * binding pool settings from {@code spring.datasource.hikari.*}.
     */
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource dataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }

    @Bean(name = "primaryJdbcTemplate")
    @Primary
    public JdbcTemplate primaryJdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
