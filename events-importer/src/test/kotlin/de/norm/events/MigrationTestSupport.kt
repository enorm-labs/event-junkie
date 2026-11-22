package de.norm.events

import org.flywaydb.core.Flyway
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * The driver's `loginTimeout` and `socketTimeout` default to 0, no limit, so a sign-in the container never answered once stalled the
 * build for 30 minutes (#5406). Both are in seconds.
 */
private const val LOGIN_TIMEOUT_SECONDS = 10
private const val SOCKET_TIMEOUT_SECONDS = 120

/** [url] with the two timeouts as parameters, after any it already carries. */
private fun withTimeouts(url: String): String =
    url + (if ('?' in url) '&' else '?') + "loginTimeout=$LOGIN_TIMEOUT_SECONDS&socketTimeout=$SOCKET_TIMEOUT_SECONDS"

/** A Flyway on [url] that migrates the `events` schema up to and including version [target]. */
fun flyway(
    url: String,
    user: String,
    password: String,
    target: String
): Flyway =
    Flyway
        .configure()
        .dataSource(withTimeouts(url), user, password)
        .schemas("events")
        .target(target)
        .load()

/** A [flyway] on [postgres] as its own user. */
fun flyway(
    postgres: PostgreSQLContainer,
    target: String
): Flyway = flyway(postgres.jdbcUrl, postgres.username, postgres.password, target)

/** A JDBC connection to [url] that fails instead of hanging. */
fun connect(
    url: String,
    user: String,
    password: String
): Connection = DriverManager.getConnection(withTimeouts(url), user, password)

/** A [connect] to [postgres] as its own user. */
fun connect(postgres: PostgreSQLContainer): Connection = connect(postgres.jdbcUrl, postgres.username, postgres.password)

/** Every row [sql] returns on [connection], each one read by [read]. */
fun <T : Any> rows(
    connection: Connection,
    sql: String,
    read: (ResultSet) -> T
): List<T> =
    connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { rs -> generateSequence { if (rs.next()) read(rs) else null }.toList() }
    }
