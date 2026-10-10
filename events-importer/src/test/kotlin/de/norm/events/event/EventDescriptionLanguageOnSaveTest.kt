package de.norm.events.event

import de.norm.events.BaseControllerTest
import de.norm.events.venue.VenueRequestFixtures
import de.norm.events.venue.VenueResponse
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.expectBody
import java.math.BigDecimal

/**
 * A description the admin app saves gets its language as an imported one does (#3079, ADR-026):
 * the POST and the PUT run [DescriptionLanguage.detect], and a text the classifier cannot call
 * stores a null language.
 */
class EventDescriptionLanguageOnSaveTest : BaseControllerTest() {
    @Test
    @DisplayName("a created event with a German description stores de")
    fun `create stores german`() {
        val created = create(GERMAN_DESCRIPTION)

        stored(created.id).language shouldBe "de"
        stored(created.id).confidence.shouldNotBeNull()
    }

    @Test
    @DisplayName("a created event with an English description stores en")
    fun `create stores english`() {
        val created = create(ENGLISH_DESCRIPTION)

        stored(created.id).language shouldBe "en"
    }

    @Test
    @DisplayName("a created event with a text too short to call stores no language")
    fun `create stores null for a short text`() {
        val created = create(SHORT_DESCRIPTION)

        stored(created.id) shouldBe StoredLanguage(null, null)
    }

    @Test
    @DisplayName("an edit to an English description stores en in place of de")
    fun `update stores the language of the new description`() {
        val created = create(GERMAN_DESCRIPTION)

        put(created.id, ENGLISH_DESCRIPTION)

        stored(created.id).language shouldBe "en"
    }

    @Test
    @DisplayName("an edit to a German description stores de in place of en")
    fun `update stores german`() {
        val created = create(ENGLISH_DESCRIPTION)

        put(created.id, GERMAN_DESCRIPTION)

        stored(created.id).language shouldBe "de"
    }

    @Test
    @DisplayName("an edit to a text too short to call, or a cleared description, stores no language")
    fun `update stores null for a short or cleared text`() {
        val short = create(GERMAN_DESCRIPTION, "short")
        val cleared = create(GERMAN_DESCRIPTION, "cleared")

        put(short.id, SHORT_DESCRIPTION, "short")
        put(cleared.id, null, "cleared")

        stored(short.id) shouldBe StoredLanguage(null, null)
        stored(cleared.id) shouldBe StoredLanguage(null, null)
    }

    @Test
    @DisplayName("an edit that leaves the description as it was keeps its language")
    fun `update keeps the language of an unchanged description`() {
        val created = create(GERMAN_DESCRIPTION)
        val before = stored(created.id)

        put(created.id, GERMAN_DESCRIPTION, title = "Edited title")

        stored(created.id) shouldBe before
    }

    private data class StoredLanguage(
        val language: String?,
        val confidence: BigDecimal?
    )

    // JUnit makes one instance per test, and the base class empties the tables before each.
    private val venue: VenueResponse by lazy {
        webTestClient
            .post()
            .uri("/api/admin/venues")
            .bodyValue(VenueRequestFixtures.astra())
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody<VenueResponse>()
            .returnResult()
            .responseBody!!
    }

    private fun request(
        description: String?,
        name: String,
        title: String = "Description language $name"
    ): EventRequest = EventRequestFixtures.create(venueId = venue.id, title = title, sourceId = "manual:test:$name", description = description)

    private fun create(
        description: String?,
        name: String = "event"
    ): EventResponse =
        webTestClient
            .post()
            .uri("/api/admin/events")
            .bodyValue(request(description, name))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody<EventResponse>()
            .returnResult()
            .responseBody!!

    /** Replaces event [id], created by [create] as [name], with [description] and [title]. */
    private fun put(
        id: Long,
        description: String?,
        name: String = "event",
        title: String = "Description language $name"
    ) {
        webTestClient
            .put()
            .uri("/api/admin/events/$id")
            .bodyValue(request(description, name, title))
            .exchange()
            .expectStatus()
            .isOk
    }

    private fun stored(id: Long): StoredLanguage =
        runBlocking {
            databaseClient
                .sql("SELECT description_language, description_language_confidence FROM events.event WHERE id = :id")
                .bind("id", id)
                .map { row ->
                    StoredLanguage(
                        row.get("description_language", String::class.java),
                        row.get("description_language_confidence", BigDecimal::class.java)
                    )
                }.one()
                .awaitSingle()
        }

    private companion object {
        const val GERMAN_DESCRIPTION =
            "Im UFO treffen Berliner Straßenrap, kompromisslose Beats und jede Menge Energie aufeinander, wenn der " +
                "Rapper in seiner Heimatstadt Halt macht."
        const val ENGLISH_DESCRIPTION =
            "Berlin street rap, uncompromising beats and plenty of energy come together when the rapper stops by in " +
                "his hometown for one night."
        const val SHORT_DESCRIPTION = "Doors 19:30"
    }
}
