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
            return pooled(CONTAINER.getJdbcUrl(), CONTAINER.getUsername(),
                    CONTAINER.getPassword());
        }
        return pooled(env("TEST_DB_URL", "jdbc:postgresql://localhost:5432/claims_test"),
                env("TEST_DB_USER", "claims"), env("TEST_DB_PASSWORD", "claims"));
    }

    /**
     * Small pools on purpose: the suite now holds ~14 cached Spring contexts (one per
     * integration-test class, each with distinct properties), and the default pool of 10
     * each exhausts Postgres max_connections (100) mid-suite ("too many clients"). Three
     * per context is plenty — tests file sequentially; the tightest concurrent use is
     * the assignment lock test (2 connections).
     */
    private static DataSource pooled(String url, String username, String password) {
        HikariDataSource ds = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(url)
                .username(username)
                .password(password)
                .build();
        ds.setMaximumPoolSize(3);
        ds.setMinimumIdle(1);
        return ds;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
