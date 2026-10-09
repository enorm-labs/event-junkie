package de.norm.events.event

import de.norm.events.BaseControllerTest
import de.norm.events.ClockConfiguration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.core.awaitOne
import org.springframework.r2dbc.core.awaitRowsUpdated
import java.time.LocalDate

/** The event page credits every source that filled a field of the event, with the fields it filled (ADR-043, #2593). */
class EventEnrichmentCreditTest : BaseControllerTest() {
    private suspend fun insertEnrichmentSource(
        venueId: Long,
        slug: String
    ): Long =
        databaseClient
            .sql(
                "INSERT INTO events.event_source (venue_id, name, slug, url, source_type, role) " +
                    "VALUES (:venueId, :slug, :slug, 'https://example.org/', 'PUSCHEN', 'ENRICHMENT') RETURNING id"
            ).bind("venueId", venueId)
            .bind("slug", slug)
            .map { row -> row.get("id", Long::class.javaObjectType)!! }
            .awaitOne()

    @Test
    fun `GET event by slug lists each enrichment source with the fields it filled, and none on a plain event`(): Unit =
        runBlocking {
            val venueId = insertVenue("Lido", "lido")
            val today = LocalDate.now(ClockConfiguration.BERLIN)
            val eventId = insertEvent(venueId, "Alpha Band", "alpha-band", today)
            insertEvent(venueId, "Plain", "plain", today)
            val first = insertEnrichmentSource(venueId, "puschen")
            val second = insertEnrichmentSource(venueId, "fan-site")
            listOf(
                Triple(first, "https://puschen.example/alpha-band", arrayOf("lineup", "genre")),
                Triple(second, "https://fans.example/alpha", arrayOf("doorsTime"))
            ).forEach { (sourceId, url, fields) ->
                databaseClient
                    .sql("INSERT INTO events.event_enrichment (event_id, event_source_id, source_url, fields) VALUES (:e, :s, :u, :f)")
                    .bind("e", eventId)
                    .bind("s", sourceId)
                    .bind("u", url)
                    .bind("f", fields)
                    .fetch()
                    .awaitRowsUpdated()
            }

            webTestClient
                .get()
                .uri("/events/alpha-band")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.enrichmentSources.length()")
                .isEqualTo(2)
                .jsonPath("$.enrichmentSources[0].sourceUrl")
                .isEqualTo("https://puschen.example/alpha-band")
                .jsonPath("$.enrichmentSources[0].fields")
                .isEqualTo(listOf("genre", "lineup"))
                .jsonPath("$.enrichmentSources[1].fields[0]")
                .isEqualTo("doorsTime")

            webTestClient
                .get()
                .uri("/events/plain")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.enrichmentSources.length()")
                .isEqualTo(0)
        }
}
