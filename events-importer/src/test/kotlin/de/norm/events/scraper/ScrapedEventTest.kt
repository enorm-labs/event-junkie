package de.norm.events.scraper

import de.norm.events.licence.SourceLicence
import de.norm.events.licence.SourceLicences
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [ScrapedEvent.toEventEntity], focused on the normalization it applies
 * at the persistence boundary. The exhaustive doors/start reordering cases live in
 * [EventFieldMappingTest]; here we only assert the mapping actually wires it in.
 */
class ScrapedEventTest {
    private fun scrapedEvent(
        doorsTime: LocalTime? = null,
        startTime: LocalTime? = null,
        title: String = "Berliner Weisse",
        eventType: String? = null,
        genre: String? = null,
        description: String? = null,
        imageUrl: String? = null
    ) = ScrapedEvent(
        title = title,
        eventType = eventType,
        genre = genre,
        eventDate = LocalDate.of(2026, 12, 30),
        sourceId = "so36:98223",
        sourceUrl = "https://www.so36.com/produkte/98223",
        doorsTime = doorsTime,
        startTime = startTime,
        description = description,
        imageUrl = imageUrl
    )

    private fun ScrapedEvent.toEntity() = toEventEntity(venueId = 1L, venueSlug = "so36", eventSourceId = 1L)

    @Test
    fun `toEventEntity swaps a transposed doors-after-start pair`() {
        // Source listed "Einlass: 19:30, Beginn: 19:00" — impossible, so the times are swapped back.
        val entity = scrapedEvent(doorsTime = LocalTime.of(19, 30), startTime = LocalTime.of(19, 0)).toEntity()

        entity.doorsTime shouldBe LocalTime.of(19, 0)
        entity.startTime shouldBe LocalTime.of(19, 30)
    }

    @Test
    fun `toEventEntity preserves an already-valid doors-start pair`() {
        val entity = scrapedEvent(doorsTime = LocalTime.of(19, 0), startTime = LocalTime.of(20, 0)).toEntity()

        entity.doorsTime shouldBe LocalTime.of(19, 0)
        entity.startTime shouldBe LocalTime.of(20, 0)
    }

    @Test
    fun `toEventEntity promotes an under-classified festival title to FESTIVAL`() {
        // Category-less "… Festival" (defaults to OTHER) and a "Konzert"-labelled festival day.
        scrapedEvent(title = "CANARIAS CALLING FESTIVAL").toEntity().eventType shouldBe "FESTIVAL"
        scrapedEvent(title = "GROSSSTADTWAHNSINN 2026 - FESTIVALTICKET", eventType = "CONCERT")
            .toEntity()
            .eventType shouldBe "FESTIVAL"
    }

    @Test
    fun `toEventEntity does not override an explicit non-festival type or a plain title`() {
        // A source that says PARTY is trusted even with "festival" in the title …
        scrapedEvent(title = "Freedom Festival Party", eventType = "PARTY").toEntity().eventType shouldBe "PARTY"
        // … and a plain concert title keeps its type.
        scrapedEvent(title = "Berliner Weisse", eventType = "CONCERT").toEntity().eventType shouldBe "CONCERT"
        scrapedEvent(title = "Manifest").toEntity().eventType shouldBe "OTHER"
    }

    @Test
    fun `toEventEntity recovers a reading or exhibition from a genre-field cue`() {
        // Festsaal files a book reading under genre "Lesung"; the title is just the author.
        scrapedEvent(title = "Dirk von Lowtzow", eventType = "CONCERT", genre = "Lesung")
            .toEntity()
            .eventType shouldBe "READING"
        // Cassiopeia files an immersive show under genre "Immersive Ausstellung"; the title has no cue.
        scrapedEvent(title = "Rising Spaces - Immersive Club Experience", genre = "Immersive Ausstellung")
            .toEntity()
            .eventType shouldBe "EXHIBITION"
    }

    @Test
    fun `toEventEntity does not let a genre cue override a trusted type or a music genre`() {
        // An explicit PARTY is trusted even if the genre text mentions a reading.
        scrapedEvent(title = "Poetry Slam Afterparty", eventType = "PARTY", genre = "Lesung")
            .toEntity()
            .eventType shouldBe "PARTY"
        // A festival title still wins over the genre field.
        scrapedEvent(title = "CANARIAS CALLING FESTIVAL", genre = "Lesung").toEntity().eventType shouldBe "FESTIVAL"
        // A genuine music genre never reclassifies a concert (no format cue to match).
        scrapedEvent(title = "Berliner Weisse", eventType = "CONCERT", genre = "Spoken Word, Jazz, Fusion")
            .toEntity()
            .eventType shouldBe "CONCERT"
    }

    // #807: PROHIBITED stops the § 16 UrhG reproduction, not only the § 19a communication to the
    // public. This is the point every import passes through, so it is where storage is refused.
    private fun licensed(
        description: SourceLicence?,
        image: SourceLicence?
    ) = SourceLicences(description = description, image = image)

    @Test
    fun `toEventEntity stores no description when the source prohibits it`() {
        val entity =
            scrapedEvent(description = "Ein Abend mit Aussicht", imageUrl = "https://example.test/a.jpg")
                .toEventEntity(
                    venueId = 1L,
                    venueSlug = "so36",
                    eventSourceId = 1L,
                    licences = licensed(SourceLicence.PROHIBITED, SourceLicence.UNCLEAR)
                )

        entity.description shouldBe null
        // Only the prohibited field goes. The other one is a separate answer for a separate right.
        entity.imageUrl shouldBe "https://example.test/a.jpg"
    }

    @Test
    fun `toEventEntity stores no image URL when the source prohibits it`() {
        val entity =
            scrapedEvent(description = "Ein Abend mit Aussicht", imageUrl = "https://example.test/a.jpg")
                .toEventEntity(
                    venueId = 1L,
                    venueSlug = "so36",
                    eventSourceId = 1L,
                    licences = licensed(SourceLicence.UNCLEAR, SourceLicence.PROHIBITED)
                )

        entity.imageUrl shouldBe null
        entity.description shouldBe "Ein Abend mit Aussicht"
    }

    @Test
    fun `toEventEntity stores both fields for every licence that is not PROHIBITED`() {
        // Fail-open, and it is the same rule the read gate applies (#283). UNCLEAR is not a refusal
        // and neither is silence, so a source nobody reviewed keeps its content.
        listOf(
            licensed(SourceLicence.UNCLEAR, SourceLicence.UNCLEAR),
            licensed(SourceLicence.PERMITTED, SourceLicence.PERMITTED),
            licensed(null, null)
        ).forEach { licences ->
            val entity =
                scrapedEvent(description = "Ein Abend mit Aussicht", imageUrl = "https://example.test/a.jpg")
                    .toEventEntity(venueId = 1L, venueSlug = "so36", eventSourceId = 1L, licences = licences)

            entity.description shouldBe "Ein Abend mit Aussicht"
            entity.imageUrl shouldBe "https://example.test/a.jpg"
        }
    }

    @Test
    fun `toEventEntity defaults to storing both fields when no licence is passed`() {
        // The default matters: every existing caller relies on it, and a default that withheld would
        // blank the corpus on the next import.
        val entity = scrapedEvent(description = "Ein Abend", imageUrl = "https://example.test/a.jpg").toEntity()

        entity.description shouldBe "Ein Abend"
        entity.imageUrl shouldBe "https://example.test/a.jpg"
    }
}
