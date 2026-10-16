package de.norm.events

import org.flywaydb.core.Flyway
import org.testcontainers.postgresql.PostgreSQLContainer

/** A Flyway on [postgres] that migrates the `events` schema up to and including version [target]. */
fun flyway(
    postgres: PostgreSQLContainer,
    target: String
): Flyway =
    Flyway
        .configure()
        .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        .schemas("events")
        .target(target)
        .load()
