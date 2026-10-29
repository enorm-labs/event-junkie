package de.norm.events.account

import de.norm.events.POSTGRES_TEST_IMAGE
import de.norm.events.rows
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager

/**
 * Runs V193 (#3887) the way the clusters do: as a database owner that cannot create roles. The
 * container's own user is a superuser, which is how tests, compose and k3d run it.
 *
 * Ordered, because a role is global to the server: once a database grants to `bff`, the role can no
 * longer be dropped, so the two cases that need it absent run first.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CreateAccountsSchemaMigrationTest {
    private val postgres = PostgreSQLContainer(POSTGRES_TEST_IMAGE)
    private lateinit var admin: Connection

    @BeforeAll
    fun start() {
        postgres.start()
        admin = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
    }

    @AfterAll
    fun stop() {
        admin.close()
        postgres.stop()
    }

    /** A fresh database owned by [owner], a role without CREATEROLE, as `events` is on a cluster. */
    private fun ownedDatabase(
        name: String,
        owner: String
    ): String {
        admin.createStatement().use { statement ->
            statement.execute("CREATE ROLE $owner LOGIN PASSWORD 'secret' NOCREATEROLE")
            statement.execute("CREATE DATABASE $name OWNER $owner")
        }
        return postgres.jdbcUrl.replaceAfterLast('/', name)
    }

    private fun flyway(
        url: String,
        user: String,
        target: String
    ) = Flyway
        .configure()
        .dataSource(url, user, "secret")
        .schemas("events")
        .target(target)
        .load()

    private fun privilege(
        connection: Connection,
        table: String,
        privilege: String
    ) = rows(connection, "SELECT has_table_privilege('bff', '$table', '$privilege')") { it.getBoolean(1) }.single()

    @Test
    @Order(1)
    fun `stops with a pointer when the role is missing and the owner cannot create it`() {
        val url = ownedDatabase("without_bff", "owner_a")
        flyway(url, "owner_a", "192").migrate()
        admin.createStatement().use { it.execute("DROP ROLE IF EXISTS bff") }

        val failure = shouldThrow<FlywayException> { flyway(url, "owner_a", "193").migrate() }

        failure.message shouldContain "docs/ops/SECRETS.md"
    }

    @Test
    @Order(3)
    fun `grants read on events and write on accounts to a role an operator created`() {
        val url = ownedDatabase("with_bff", "owner_b")
        admin.createStatement().use { it.execute("DO \$\$ BEGIN CREATE ROLE bff LOGIN; EXCEPTION WHEN duplicate_object THEN NULL; END \$\$") }
        flyway(url, "owner_b", "193").migrate()

        DriverManager.getConnection(url, "owner_b", "secret").use { owner ->
            privilege(owner, "events.event", "SELECT") shouldBe true
            privilege(owner, "events.event", "INSERT") shouldBe false
            privilege(owner, "events.event", "DELETE") shouldBe false
            privilege(owner, "events.flyway_schema_history", "SELECT") shouldBe true
            privilege(owner, "accounts.account", "INSERT") shouldBe true
            privilege(owner, "accounts.session", "DELETE") shouldBe true
            privilege(owner, "accounts.one_time_token", "UPDATE") shouldBe true

            // A table a later migration adds is readable too, through the default privileges.
            owner.createStatement().use { it.execute("CREATE TABLE events.later_table (id INT)") }
            privilege(owner, "events.later_table", "SELECT") shouldBe true
            privilege(owner, "events.later_table", "UPDATE") shouldBe false
        }
    }

    @Test
    @Order(2)
    fun `creates the role itself when it runs as a superuser`() {
        admin.createStatement().use { it.execute("DROP ROLE IF EXISTS bff") }
        val url = postgres.jdbcUrl.replaceAfterLast('/', "as_superuser")
        admin.createStatement().use { it.execute("CREATE DATABASE as_superuser") }

        Flyway
            .configure()
            .dataSource(url, postgres.username, postgres.password)
            .schemas("events")
            .target("193")
            .load()
            .migrate()

        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            rows(connection, "SELECT rolcanlogin FROM pg_roles WHERE rolname = 'bff'") { it.getBoolean(1) }.single() shouldBe false
            privilege(connection, "accounts.account", "INSERT") shouldBe true
        }
    }
}
