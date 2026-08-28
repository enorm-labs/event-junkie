package de.norm.events.sourcelicence

import de.norm.events.BaseControllerTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.core.awaitSingle
import java.time.LocalDate

/**
 * Proves the gate over HTTP rather than over a data class.
 *
 * [SourceLicencesTest] pins the rule. This pins the wiring: that both read paths apply it, that the
 * two fields are answered independently, and that an event whose source was deleted still displays.
 * The detail endpoint is the one that renders a description in full, so it is the one that matters.
 */
class SourceLicenceGateIntegrationTest : BaseControllerTest() {
    @Test
    @DisplayName("a prohibited description is withheld and everything else survives")
    fun `withholds a prohibited description`(): Unit =
        runBlocking {
            val venueId = insertVenue(name = "Prohibited Venue", slug = "prohibited-venue")
            val sourceId = insertSource(venueId, slug = "prohibited-src", descriptionLicence = "PROHIBITED")
            insertGatedEvent(venueId, sourceId, slug = "gated-event")

            webTestClient
                .get()
                .uri("/events/gated-event")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.description")
                .doesNotExist()
                // The image is a separate column and was not prohibited, so it stays.
                .jsonPath("$.imageUrl")
                .isEqualTo("https://example.com/poster.jpg")
                // Facts are never gated. They are the part SCRAPING_POSITION.md §3.1 calls safe.
                .jsonPath("$.title")
                .isEqualTo("Gated Event")
        }

    @Test
    @DisplayName("a prohibited image is withheld while the description survives")
    fun `withholds a prohibited image`(): Unit =
        runBlocking {
            val venueId = insertVenue(name = "Agency Venue", slug = "agency-venue")
            val sourceId = insertSource(venueId, slug = "agency-src", imageLicence = "PROHIBITED")
            insertGatedEvent(venueId, sourceId, slug = "agency-event")

            webTestClient
                .get()
                .uri("/events/agency-event")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.imageUrl")
                .doesNotExist()
                .jsonPath("$.description")
                .isEqualTo("A description the venue never objected to.")
        }

    @Test
    @DisplayName("an unreviewed source displays, which is the fail-open decision on #283")
    fun `displays everything for an unreviewed source`(): Unit =
        runBlocking {
            val venueId = insertVenue(name = "Unreviewed Venue", slug = "unreviewed-venue")
            val sourceId = insertSource(venueId, slug = "unreviewed-src")
            insertGatedEvent(venueId, sourceId, slug = "unreviewed-event")

            webTestClient
                .get()
                .uri("/events/unreviewed-event")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.description")
                .isEqualTo("A description the venue never objected to.")
                .jsonPath("$.imageUrl")
                .isEqualTo("https://example.com/poster.jpg")
        }

    @Test
    @DisplayName("UNCLEAR displays too, and is not the same state as unreviewed")
    fun `displays everything for an unclear source`(): Unit =
        runBlocking {
            val venueId = insertVenue(name = "Unclear Venue", slug = "unclear-venue")
            val sourceId =
                insertSource(venueId, slug = "unclear-src", descriptionLicence = "UNCLEAR", imageLicence = "UNCLEAR")
            insertGatedEvent(venueId, sourceId, slug = "unclear-event")

            webTestClient
                .get()
                .uri("/events/unclear-event")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.description")
                .isEqualTo("A description the venue never objected to.")
        }

    @Test
    @DisplayName("the list path is gated as well as the detail path")
    fun `withholds on the list endpoint`(): Unit =
        runBlocking {
            val venueId = insertVenue(name = "List Venue", slug = "list-venue")
            val sourceId = insertSource(venueId, slug = "list-src", imageLicence = "PROHIBITED")
            insertGatedEvent(venueId, sourceId, slug = "list-event")

            webTestClient
                .get()
                .uri("/events?size=50")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                // Indexed rather than filtered: a JSONPath filter yields [null] for an absent
                // property, which reads as present. This test has exactly one event.
                .jsonPath("$.content[0].slug")
                .isEqualTo("list-event")
                .jsonPath("$.content[0].imageUrl")
                .doesNotExist()
        }

    @Test
    @DisplayName("an event whose source was deleted still displays")
    fun `displays an event with no source`(): Unit =
        runBlocking {
            val venueId = insertVenue(name = "Orphan Venue", slug = "orphan-venue")
            insertGatedEvent(venueId, sourceId = null, slug = "orphan-event")

            webTestClient
                .get()
                .uri("/events/orphan-event")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.description")
                .isEqualTo("A description the venue never objected to.")
        }

    private suspend fun insertSource(
        venueId: Long,
        slug: String,
        descriptionLicence: String? = null,
        imageLicence: String? = null
    ): Long =
        databaseClient
            .sql(
                "INSERT INTO events.event_source (venue_id, name, slug, url, source_type, description_licence, image_licence) " +
                    "VALUES (:venueId, :name, :slug, :url, 'WEBSITE', :descriptionLicence, :imageLicence) RETURNING id"
            ).bind("venueId", venueId)
            .bind("name", slug)
            .bind("slug", slug)
            .bind("url", "https://example.com/$slug")
            .let { spec -> descriptionLicence?.let { spec.bind("descriptionLicence", it) } ?: spec.bindNull("descriptionLicence", String::class.java) }
            .let { spec -> imageLicence?.let { spec.bind("imageLicence", it) } ?: spec.bindNull("imageLicence", String::class.java) }
            .map { row -> row.get("id", Long::class.javaObjectType)!! }
            .awaitSingle()

    private suspend fun insertGatedEvent(
        venueId: Long,
        sourceId: Long?,
        slug: String
    ): Long =
        databaseClient
            .sql(
                "INSERT INTO events.event (venue_id, event_source_id, title, slug, event_date, source_id, description, image_url) " +
                    "VALUES (:venueId, :sourceId, 'Gated Event', :slug, :eventDate, :sourceKey, " +
                    "'A description the venue never objected to.', 'https://example.com/poster.jpg') RETURNING id"
            ).bind("venueId", venueId)
            .let { spec -> sourceId?.let { spec.bind("sourceId", it) } ?: spec.bindNull("sourceId", Long::class.javaObjectType) }
            .bind("slug", slug)
            .bind("eventDate", LocalDate.now().plusDays(7))
            .bind("sourceKey", "test:$slug")
            .map { row -> row.get("id", Long::class.javaObjectType)!! }
            .awaitSingle()
}
