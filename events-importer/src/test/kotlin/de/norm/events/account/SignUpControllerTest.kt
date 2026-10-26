package de.norm.events.account

import de.norm.events.BaseControllerTest
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.awaitSingle

/** The sign-up switch (#3906): one row in `accounts.sign_up`, written here and only read by the BFF. */
class SignUpControllerTest : BaseControllerTest() {
    @BeforeEach
    fun open() = runBlocking { databaseClient.sql("UPDATE accounts.sign_up SET open = true, changed_at = now()").await() }

    private fun put(open: Boolean) =
        webTestClient
            .put()
            .uri("/api/admin/sign-up")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(SignUpRequest(open))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody(SignUpResponse::class.java)
            .returnResult()
            .responseBody!!

    @Test
    fun `sign-up is open until an operator pauses it`() {
        webTestClient
            .get()
            .uri("/api/admin/sign-up")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.open")
            .isEqualTo(true)

        put(open = false).open shouldBe false
        put(open = true).open shouldBe true
    }

    @Test
    fun `pausing twice keeps the time of the first pause`() {
        val first = put(open = false).changedAt
        val second = put(open = false).changedAt

        second shouldBe first
    }

    @Test
    fun `the BFF may read the switch but not flip it`() {
        fun privilege(name: String): Boolean =
            runBlocking {
                databaseClient
                    .sql("SELECT has_table_privilege('bff', 'accounts.sign_up', '$name') AS granted")
                    .map { row -> row.get("granted", Boolean::class.javaObjectType)!! }
                    .awaitSingle()
            }

        privilege("SELECT") shouldBe true
        privilege("UPDATE") shouldBe false
        privilege("INSERT") shouldBe false
        privilege("DELETE") shouldBe false
    }

    @Test
    fun `there is exactly one row`() {
        val rows =
            runBlocking {
                databaseClient
                    .sql("SELECT count(*) AS n FROM accounts.sign_up")
                    .map { row -> row.get("n", Long::class.javaObjectType)!! }
                    .awaitSingle()
            }

        rows shouldBe 1L
    }
}
