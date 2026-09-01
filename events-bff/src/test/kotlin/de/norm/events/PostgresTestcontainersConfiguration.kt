package de.norm.events

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * Docs: https://docs.spring.io/spring-boot/reference/testing/testcontainers.html
 *
 * **No `withReuse(true)`, and that is measured rather than assumed (#954).** Reuse saved about 13
 * seconds across a full backend test cycle here, and nothing at all in CI, where the runner is
 * ephemeral. It costs more than that: every context in both modules builds an identical container,
 * so they all hash to the *same* reusable one and share a single database while they are cached and
 * alive together. `BaseControllerTest.cleanUp` then truncates twelve tables another live context is
 * querying, which is the `40P01` deadlock #949 fixed, reintroduced across contexts.
 *
 * The image tag matches `compose.yaml` on purpose, so tests and local development meet the same
 * PostgreSQL.
 */
@TestConfiguration(proxyBeanMethods = false)
class PostgresTestcontainersConfiguration {
    @Bean
    @ServiceConnection(name = "postgres")
    fun postgresContainer(): PostgreSQLContainer = PostgreSQLContainer("postgres:18.3-alpine")
}
