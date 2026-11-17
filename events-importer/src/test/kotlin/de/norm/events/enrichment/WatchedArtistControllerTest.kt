package de.norm.events.enrichment

import de.norm.events.BaseControllerTest
import de.norm.events.account.WatchCounts
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.await

/** The AMBIGUOUS artists visitors wait for (#5214), against the real `accounts.follow` rows the BFF writes (V259). */
class WatchedArtistControllerTest : BaseControllerTest() {
    @Autowired
    private lateinit var watchCounts: WatchCounts

    @BeforeEach
    fun seed() =
        runBlocking {
            sql("TRUNCATE TABLE accounts.follow, accounts.session, accounts.account CASCADE")
            sql(
                "INSERT INTO accounts.account (id, email, locale) VALUES " +
                    "('00000000-0000-4000-8000-000000000001', 'one@example.de', 'de'), " +
                    "('00000000-0000-4000-8000-000000000002', 'two@example.de', 'en')"
            )
            artist("Café Nebel", "AMBIGUOUS")
            artist("Moderat", "AMBIGUOUS")
            artist("Moderat Live", "AMBIGUOUS")
            artist("Kesselwerk", "EXACT", "aaaaaaaa-0000-4000-8000-0000000000ee")
            artist("Unwatched", "AMBIGUOUS")
            watch(ONE, MODERAT, "MODERAT")
            watch(TWO, MODERAT, "Moderat")
            watch(ONE, NEBEL, "cafe  nebel")
            watch(TWO, KESSEL, "Kesselwerk")
        }

    private suspend fun sql(statement: String) = databaseClient.sql(statement).await()

    private suspend fun artist(
        name: String,
        match: String,
        mbid: String? = null
    ) = sql(
        "INSERT INTO events.artist (name, slug, musicbrainz_match, musicbrainz_id) VALUES " +
            "('$name', '${name.lowercase().replace(' ', '-').replace("é", "e")}', '$match', ${mbid?.let { "'$it'" } ?: "NULL"})"
    )

    private suspend fun watch(
        account: String,
        mbid: String,
        name: String
    ) = sql("INSERT INTO accounts.follow (account_id, kind, mbid, name) VALUES ('$account', 'watch', '$mbid', '$name')")

    @Test
    fun `lists the AMBIGUOUS rows whose folded name visitors watch, the most watched first, and names no account`() {
        webTestClient
            .get()
            .uri("/api/admin/artists/watched")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$[*].name")
            .isEqualTo(listOf("Moderat", "Café Nebel"))
            .jsonPath("$[*].watchers")
            .isEqualTo(listOf(2, 1))
            .jsonPath("$[0].musicbrainzMatch")
            .isEqualTo("AMBIGUOUS")
            .consumeWith { it.responseBody!!.decodeToString().contains("example.de") shouldBe false }
    }

    @Test
    fun `counts the watches per MBID through the array bind, and leaves out an id nobody watches`() =
        runBlocking<Unit> {
            // The candidates endpoint's read; its controller test mocks it, so only this runs the SQL.
            watchCounts.byMbid(listOf(MODERAT, NEBEL, UNWATCHED)) shouldBe mapOf(MODERAT to 2, NEBEL to 1)
            watchCounts.byMbid(emptyList()) shouldBe emptyMap()
        }

    @Test
    fun `answers an empty list when nobody watches anything`() =
        runBlocking {
            sql("TRUNCATE TABLE accounts.follow")
            webTestClient
                .get()
                .uri("/api/admin/artists/watched")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .json("[]")
            Unit
        }

    private companion object {
        const val ONE = "00000000-0000-4000-8000-000000000001"
        const val TWO = "00000000-0000-4000-8000-000000000002"
        const val MODERAT = "aaaaaaaa-0000-4000-8000-000000000001"
        const val NEBEL = "aaaaaaaa-0000-4000-8000-000000000002"
        const val KESSEL = "aaaaaaaa-0000-4000-8000-000000000003"
        const val UNWATCHED = "aaaaaaaa-0000-4000-8000-000000000009"
    }
}
