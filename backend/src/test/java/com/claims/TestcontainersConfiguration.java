package com.claims;

import javax.sql.DataSource;

import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.zaxxer.hikari.HikariDataSource;

/**
 * Test database provider with one rule: integration tests always run against a real
 * PostgreSQL, never H2.
 *
 * <p>When Docker is available (CI, or any machine with a running daemon) a
 * Testcontainers {@code postgres:16-alpine} container is started once per JVM. When it is
 * not (e.g. the local dev sandbox this repo was built in), tests fall back to a
 * <b>dedicated</b> test database supplied by TEST_DB_URL (default
 * {@code localhost:5432/claims_test}) — never the dev database, because tests truncate.
 * See docs/decisions.md.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    private static final PostgreSQLContainer CONTAINER;

    static {
        PostgreSQLContainer container = null;
        if (DockerClientFactory.instance().isDockerAvailable()) {
            container = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));
            container.start();
        }
        CONTAINER = container;
    }

    @Bean(destroyMethod = "")
    DataSource dataSource() {
        if (CONTAINER != null) {
            return DataSourceBuilder.create()
                    .type(HikariDataSource.class)
                    .url(CONTAINER.getJdbcUrl())
                    .username(CONTAINER.getUsername())
                    .password(CONTAINER.getPassword())
                    .build();
        }
        return DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(env("TEST_DB_URL", "jdbc:postgresql://localhost:5432/claims_test"))
                .username(env("TEST_DB_USER", "claims"))
                .password(env("TEST_DB_PASSWORD", "claims"))
                .build();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
