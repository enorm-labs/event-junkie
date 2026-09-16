package de.norm.events.scraper

import de.norm.events.event.EventEntity
import de.norm.events.licence.SourceLicence
import de.norm.events.licence.SourceLicences
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

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
        imageUrl: String? = null,
        eventDate: LocalDate = LocalDate.of(2026, 12, 30),
        endDate: LocalDate? = null,
        endTime: LocalTime? = null,
        status: String = "SCHEDULED"
    ) = ScrapedEvent(
        title = title,
        status = status,
        eventType = eventType,
        genre = genre,
        eventDate = eventDate,
        sourceId = "so36:98223",
        sourceUrl = "https://www.so36.com/produkte/98223",
        doorsTime = doorsTime,
        startTime = startTime,
        endDate = endDate,
        endTime = endTime,
        description = description,
        imageUrl = imageUrl
    )

    private fun ScrapedEvent.toEntity(existing: EventEntity? = null) = toEventEntity(venueId = 1L, venueSlug = "so36", eventSourceId = 1L, existing = existing)

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

    // A venue with no badge writes the cancellation into the title (#1493).
    @Test
    fun `toEventEntity reads a cancellation from the title when the scraper found no badge`() {
        val entity = scrapedEvent(title = "Olga Myko - Abgesagt").toEntity()

        entity.status shouldBe "CANCELLED"
        entity.title shouldBe "Olga Myko"
        entity.slug shouldBe "2026-12-30-so36-olga-myko"
    }

    @Test
    fun `toEventEntity keeps a scraper's own status over the title, and a plain title scheduled`() {
        scrapedEvent(title = "The Act (verschoben)", status = "RELOCATED").toEntity().status shouldBe "RELOCATED"
        scrapedEvent(title = "Berliner Weisse").toEntity().status shouldBe "SCHEDULED"
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

    // The language travels with the text, so a page can mark it and a crawler can believe it
    // (ADR-026). The classifier's own cases live in DescriptionLanguageTest; this asserts the wiring.
    @Test
    fun `toEventEntity classifies the language of the stored description`() {
        val entity = scrapedEvent(description = GERMAN_DESCRIPTION).toEntity()

        entity.descriptionLanguage shouldBe "de"
        entity.descriptionLanguageConfidence?.signum() shouldBe 1
    }

    // Nothing to classify, so nothing is claimed. A prohibited source keeps no trace of the text.
    @Test
    fun `toEventEntity claims no language for a description it does not store`() {
        val entity =
            scrapedEvent(description = GERMAN_DESCRIPTION)
                .toEventEntity(
                    venueId = 1L,
                    venueSlug = "so36",
                    eventSourceId = 1L,
                    licences = licensed(SourceLicence.PROHIBITED, SourceLicence.UNCLEAR)
                )

        entity.description shouldBe null
        entity.descriptionLanguage shouldBe null
        entity.descriptionLanguageConfidence shouldBe null
    }

    // The translation pass writes the second text after the import; the mapper only carries it. Blanking
    // it here re-bought the whole catalogue every night (#1301).
    @Test
    fun `toEventEntity keeps the stored translation while the description is unchanged`() {
        val entity = scrapedEvent(description = GERMAN_DESCRIPTION).toEntity(existing = translated(GERMAN_DESCRIPTION))

        entity.descriptionAlt shouldBe "An evening with a view"
        entity.descriptionAltLanguage shouldBe "en"
        entity.descriptionAltOrigin shouldBe "MACHINE"
        entity.descriptionAltEngine shouldBe "anthropic:test"
        entity.descriptionAltSourceHash shouldBe "hash-of-old"
    }

    @Test
    fun `toEventEntity drops the stored translation when the venue rewrote the description`() {
        val entity = scrapedEvent(description = "$GERMAN_DESCRIPTION Neu.").toEntity(existing = translated(GERMAN_DESCRIPTION))

        entity.descriptionAlt shouldBe null
        entity.descriptionAltLanguage shouldBe null
        entity.descriptionAltOrigin shouldBe null
        entity.descriptionAltEngine shouldBe null
        entity.descriptionAltSourceHash shouldBe null
    }

    // A prohibited source keeps no trace of the text, and a translation is a trace of it.
    @Test
    fun `toEventEntity drops the stored translation with a description it no longer stores`() {
        val entity =
            scrapedEvent(description = GERMAN_DESCRIPTION)
                .toEventEntity(
                    venueId = 1L,
                    venueSlug = "so36",
                    eventSourceId = 1L,
                    existing = translated(GERMAN_DESCRIPTION),
                    licences = licensed(SourceLicence.PROHIBITED, SourceLicence.UNCLEAR)
                )

        entity.description shouldBe null
        entity.descriptionAlt shouldBe null
        entity.descriptionAltOrigin shouldBe null
    }

    @Test
    fun `toEventEntity writes no translation for a new event`() {
        val entity = scrapedEvent(description = GERMAN_DESCRIPTION).toEntity()

        entity.descriptionAlt shouldBe null
        entity.descriptionAltOrigin shouldBe null
    }

    /** A stored row that the translation pass has already filled in, as the upsert reads it back. */
    private fun translated(description: String) =
        scrapedEvent(description = description).toEntity().copy(
            id = 7L,
            descriptionAlt = "An evening with a view",
            descriptionAltLanguage = "en",
            descriptionAltOrigin = "MACHINE",
            descriptionAltEngine = "anthropic:test",
            descriptionAltSourceHash = "hash-of-old"
        )

    private companion object {
        const val GERMAN_DESCRIPTION =
            "Die Bolschewistische Kurkapelle wurde 1986 in Ost-Berlin als Teil der politischen Untergrundszene " +
                "gegründet, wenige Jahre vor dem Fall der Berliner Mauer."
    }

    // --- the end (ADR-029) ---

    @Test
    fun `toEventEntity passes a stated end through and leaves it empty otherwise`() {
        val weekender = scrapedEvent(startTime = LocalTime.of(22, 0), endDate = LocalDate.of(2027, 1, 2), endTime = LocalTime.of(10, 0)).toEntity()
        weekender.endDate shouldBe LocalDate.of(2027, 1, 2)
        weekender.endTime shouldBe LocalTime.of(10, 0)

        val night = scrapedEvent(startTime = LocalTime.of(22, 0)).toEntity()
        night.endDate shouldBe null
        night.endTime shouldBe null
    }

    @Test
    fun `toEventEntity refuses an end time without an end date`() {
        // The column constraint would refuse it too, halfway through a bulk save; this fails at the row.
        shouldThrow<IllegalArgumentException> { scrapedEvent(endTime = LocalTime.of(6, 0)).toEntity() }
    }

    @Test
    fun `dropPastEvents keeps a weekender through its last day`() {
        val friday = LocalDate.of(2026, 9, 11)
        val weekender = scrapedEvent(eventDate = friday, endDate = friday.plusDays(3))
        val night = scrapedEvent(eventDate = friday)

        // Noon, so the late-night grace (#299) stays out of a test about the end date.
        fun on(day: LocalDate) = Clock.fixed(day.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

        listOf(weekender, night).dropPastEvents(on(friday.plusDays(1))) {} shouldBe listOf(weekender)
        listOf(weekender, night).dropPastEvents(on(friday.plusDays(3))) {} shouldBe listOf(weekender)
        listOf(weekender, night).dropPastEvents(on(friday.plusDays(4))) {} shouldBe emptyList()
    }

    @Test
    fun `dropPastEvents keeps last night until six in the morning, unless the venue said when it ends`() {
        val friday = LocalDate.of(2026, 9, 11)
        val club = scrapedEvent(eventDate = friday, startTime = LocalTime.of(23, 0))
        val timeless = scrapedEvent(eventDate = friday)
        val gig = scrapedEvent(eventDate = friday, startTime = LocalTime.of(20, 0))
        val ended = scrapedEvent(eventDate = friday, startTime = LocalTime.of(23, 0), endDate = friday, endTime = LocalTime.of(23, 59))
        val all = listOf(club, timeless, gig, ended)

        fun saturdayAt(hour: Int) = Clock.fixed(friday.plusDays(1).atTime(hour, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

        all.dropPastEvents(saturdayAt(3)) {} shouldBe listOf(club, timeless)
        all.dropPastEvents(saturdayAt(7)) {} shouldBe emptyList()
    }

    // --- collapseExhibitionRuns (ADR-029, #337) ---

    private fun day(
        date: LocalDate,
        type: String = "EXHIBITION",
        page: String = "show",
        endDate: LocalDate? = null
    ) = scrapedEvent(eventType = type, eventDate = date, endDate = endDate).copy(sourceId = "so36:$date-$page", sourceUrl = "https://so36.com/$page")

    @Test
    fun `collapseExhibitionRuns folds the listed days of one page into a run and leaves the rest alone`() {
        val d = LocalDate.of(2026, 8, 14)
        val rows = listOf(day(d), day(d, type = "CONCERT", page = "gig"), day(d.plusDays(1)), day(d.plusDays(2)), day(d, page = "other"))

        val folded = rows.collapseExhibitionRuns { "so36:${it.sourceUrl.substringAfterLast('/')}" }

        folded.map { it.sourceId } shouldBe listOf("so36:show", "so36:$d-gig", "so36:other")
        folded.first().eventDate shouldBe d
        folded.first().endDate shouldBe d.plusDays(2)
        // One listed day is a day, not a run.
        folded.last().endDate shouldBe null
    }

    @Test
    fun `collapseExhibitionRuns widens to a span a day already carries, and skips rows without a key`() {
        val d = LocalDate.of(2026, 8, 14)
        val fromPage = day(LocalDate.of(2026, 7, 17), endDate = LocalDate.of(2026, 8, 23))
        val rows = listOf(day(d), fromPage, day(d.plusDays(1)))

        val run = rows.collapseExhibitionRuns { "so36:show" }.single()
        run.eventDate shouldBe LocalDate.of(2026, 7, 17)
        run.endDate shouldBe LocalDate.of(2026, 8, 23)

        rows.collapseExhibitionRuns { null } shouldBe rows
    }
}
