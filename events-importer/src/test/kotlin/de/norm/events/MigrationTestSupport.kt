package de.norm.events

import org.flywaydb.core.Flyway
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.ResultSet

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

/** Every row [sql] returns on [connection], each one read by [read]. */
fun <T : Any> rows(
    connection: Connection,
    sql: String,
    read: (ResultSet) -> T
): List<T> =
    connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { rs -> generateSequence { if (rs.next()) read(rs) else null }.toList() }
    }
