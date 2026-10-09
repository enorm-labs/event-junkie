package de.norm.events.event

import de.norm.events.BaseControllerTest
import de.norm.events.ClockConfiguration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.core.await
import java.time.LocalDate

/**
 * `language` repeats, and an event performed in any given language matches (#2524). An event whose
 * language is not known drops out, and a screening's subtitles do not count.
 */
class EventSpokenLanguageFilterTest : BaseControllerTest() {
    private val today = LocalDate.now(ClockConfiguration.BERLIN)

    @BeforeEach
    fun seed(): Unit =
        runBlocking {
            val venueId = insertVenue("Kneipe", "kneipe")
            event(venueId, "english-stand-up", today, "COMEDY", spoken = "{en}")
            event(venueId, "bilingual-open-mic", today.plusDays(1), "COMEDY", spoken = "{de,en}")
            event(venueId, "lesung", today.plusDays(2), "READING", spoken = "{de}")
            event(venueId, "unknown-language", today.plusDays(3), "COMEDY", spoken = null)
            event(venueId, "omu-screening", today.plusDays(4), "SCREENING", spoken = "{fr}", subtitles = "de")
        }

    @Test
    fun `GET events filters by spoken language, any of several, and leaves out the unknown`() {
        mapOf(
            "language=en" to listOf("english-stand-up", "bilingual-open-mic"),
            "language=DE" to listOf("bilingual-open-mic", "lesung"),
            "language=de&language=en" to listOf("english-stand-up", "bilingual-open-mic", "lesung"),
            "language=en&eventType=COMEDY" to listOf("english-stand-up", "bilingual-open-mic"),
            "language=en&eventType=READING" to emptyList(),
            "language=xx" to emptyList(),
            "eventType=COMEDY" to listOf("english-stand-up", "bilingual-open-mic", "unknown-language")
        ).forEach { (query, slugs) ->
            webTestClient
                .get()
                .uri("/events?$query")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(slugs.size)
                .jsonPath("$.content[*].slug")
                .isEqualTo(slugs)
        }
    }

    @Test
    fun `GET events matches a screening by what is spoken, never by its subtitles`() {
        webTestClient
            .get()
            .uri("/events?language=fr")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.content[*].slug")
            .isEqualTo(listOf("omu-screening"))
        webTestClient
            .get()
            .uri("/events?language=de&eventType=SCREENING")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.totalElements")
            .isEqualTo(0)
    }

    @Test
    fun `GET events calendar filters by spoken language`() {
        webTestClient
            .get()
            .uri("/events/calendar?from=$today&to=${today.plusDays(7)}&language=en")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$[*].slug")
            .isEqualTo(listOf("english-stand-up", "bilingual-open-mic"))
    }

    /** Both requests go through the response cache: the second gets its own entry, not the first's. */
    @Test
    fun `two languages are two cache entries on the list and the calendar`() {
        listOf("/events?language=en" to "$.content[*].slug", "/events/calendar?from=$today&to=${today.plusDays(7)}&language=en" to "$[*].slug")
            .forEach { (uri, path) ->
                webTestClient
                    .get()
                    .uri(uri)
                    .exchange()
                    .expectBody()
                    .jsonPath(path)
                    .isEqualTo(listOf("english-stand-up", "bilingual-open-mic"))
                webTestClient
                    .get()
                    .uri(uri.replace("language=en", "language=de"))
                    .exchange()
                    .expectBody()
                    .jsonPath(path)
                    .isEqualTo(listOf("bilingual-open-mic", "lesung"))
            }
    }

    @Test
    fun `an unknown parameter beside the language filter is still a 400`() {
        webTestClient
            .get()
            .uri("/events?language=en&lang=en")
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.unknown[0]")
            .isEqualTo("lang")
        webTestClient
            .get()
            .uri("/events/calendar?from=$today&to=${today.plusDays(7)}&language=en&spokenLanguage=en")
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.unknown[0]")
            .isEqualTo("spokenLanguage")
    }

    private suspend fun event(
        venueId: Long,
        slug: String,
        date: LocalDate,
        type: String,
        spoken: String?,
        subtitles: String? = null
    ) {
        val id = insertEvent(venueId, slug, slug, date, eventType = type)
        databaseClient
            .sql("UPDATE events.event SET spoken_languages = CAST(:spoken AS text[]), subtitle_language = :subtitles WHERE id = :id")
            .bind("id", id)
            .let { spec -> spoken?.let { spec.bind("spoken", it) } ?: spec.bindNull("spoken", String::class.java) }
            .let { spec -> subtitles?.let { spec.bind("subtitles", it) } ?: spec.bindNull("subtitles", String::class.java) }
            .await()
    }
}
