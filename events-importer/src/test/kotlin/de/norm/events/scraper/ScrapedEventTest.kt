package de.norm.events.scraper

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import de.norm.events.event.EventEntity
import de.norm.events.licence.SourceLicence
import de.norm.events.licence.SourceLicences
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.math.BigDecimal
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
    // --- withGapsFrom ---

    private val listingRow =
        ScrapedEvent(
            title = "Listing title",
            subtitle = "Tour 2026",
            description = "Listing blurb",
            eventType = "CONCERT",
            eventDate = LocalDate.of(2026, 10, 3),
            doorsTime = LocalTime.of(19, 0),
            startTime = LocalTime.of(20, 0),
            endDate = LocalDate.of(2026, 10, 4),
            endTime = LocalTime.of(2, 0),
            room = "Saal",
            imageUrl = "https://example.org/listing.jpg",
            sourceUrl = "https://example.org/listing",
            sourceId = "listing-1",
            ticketUrl = "https://example.org/tickets",
            genre = "Rock",
            pricePresale = BigDecimal("20.00"),
            priceBoxOffice = BigDecimal("25.00"),
            priceNote = "VVK 20 / AK 25",
            soldOut = true,
            free = false,
            status = "CANCELLED",
            statusNote = "Abgesagt",
            artists = listOf(ScrapedArtist(name = "Listing Act", role = "HEADLINER")),
            promoters = listOf("Listing Promoter"),
            promoterWebsites = mapOf("Listing Promoter" to "https://promoter.example.org")
        )

    @Test
    fun `withGapsFrom fills every gap of a bare detail page from the listing`() {
        val detail =
            ScrapedEvent(
                title = "Detail title",
                eventDate = UNRESOLVED_EVENT_DATE,
                sourceUrl = "https://example.org/detail",
                sourceId = "detail-1"
            )

        detail.withGapsFrom(listingRow) shouldBe
            listingRow.copy(title = "Detail title", sourceUrl = "https://example.org/detail", sourceId = "detail-1")
    }

    @Test
    fun `withGapsFrom never replaces a value the detail page set`() {
        val detail =
            listingRow.copy(
                title = "Detail title",
                subtitle = "Detail subtitle",
                description = "Detail blurb",
                eventDate = LocalDate.of(2026, 10, 2),
                endTime = LocalTime.of(3, 0),
                room = "Studio",
                pricePresale = BigDecimal("18.00"),
                soldOut = false,
                free = true,
                status = "POSTPONED",
                artists = listOf(ScrapedArtist(name = "Detail Act", role = "HEADLINER"))
            )

        val merged = detail.withGapsFrom(listingRow.copy(soldOut = false, free = false))

        merged shouldBe detail
    }

    @Test
    fun `withGapsFrom holds a sold-out or free flag and a changed status from either side`() {
        val detail = listingRow.copy(soldOut = false, free = false, status = "SCHEDULED")

        val merged = detail.withGapsFrom(listingRow.copy(soldOut = true, free = true, status = "RELOCATED"))

        merged.soldOut shouldBe true
        merged.free shouldBe true
        merged.status shouldBe "RELOCATED"
    }

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
        status: String = "SCHEDULED",
        statusNote: String? = null,
        subtitle: String? = null,
        pricePresale: BigDecimal? = null,
        priceBoxOffice: BigDecimal? = null,
        soldOut: Boolean = false
    ) = ScrapedEvent(
        title = title,
        soldOut = soldOut,
        subtitle = subtitle,
        pricePresale = pricePresale,
        priceBoxOffice = priceBoxOffice,
        status = status,
        statusNote = statusNote,
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
    fun `a genre that repeats the title is not stored, whatever its case and spacing`() {
        val event = scrapedEvent(title = "Berliner  Weisse", genre = " berliner weisse ")

        event.genreRepeatsTitle() shouldBe true
        event.toEntity().genre.shouldBeNull()
    }

    @Test
    fun `a genre that differs from the title is stored as read`() {
        val event = scrapedEvent(title = "Berliner Weisse", genre = "Berliner Weisse, Punk")

        event.genreRepeatsTitle() shouldBe false
        event.toEntity().genre shouldBe "Berliner Weisse, Punk"
        scrapedEvent(genre = null).genreRepeatsTitle() shouldBe false
    }

    @Test
    fun `a genre that repeats the title still types the event`() {
        // The format cue is real even where the genre adds nothing to the title.
        val entity = scrapedEvent(title = "Lesung", genre = "Lesung", eventType = "CONCERT").toEntity()

        entity.genre.shouldBeNull()
        entity.eventType shouldBe "READING"
    }

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
    fun `toEventEntity cleans every title at the boundary and keeps the scraper's sourceId`() {
        val raw = scrapedEvent(title = "\u200BOlga\u00A0 Myko  –").copy(sourceId = "so36:olga-myko-raw")

        val entity = raw.toEntity()

        entity.title shouldBe "Olga Myko"
        entity.slug shouldBe "2026-12-30-so36-olga-myko"
        entity.sourceId shouldBe "so36:olga-myko-raw"
    }

    @Test
    fun `toEventEntity cleans the title before it strips a status marker`() {
        val entity = scrapedEvent(title = "Olga  Myko -  Abgesagt").toEntity()

        entity.status shouldBe "CANCELLED"
        entity.title shouldBe "Olga Myko"
    }

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
    fun `toEventEntity stores a cancelled show as not sold out, whichever cue said sold out`() {
        val colosseum = scrapedEvent(title = "Annika Sala – Interaktive Lesung", status = "CANCELLED", soldOut = true).toEntity()
        colosseum.status shouldBe "CANCELLED"
        colosseum.soldOut shouldBe false
        val marked = scrapedEvent(title = "Reimanns (ausverkauft) - Abgesagt").toEntity()
        marked.status shouldBe "CANCELLED"
        marked.soldOut shouldBe false
    }

    @Test
    fun `toEventEntity keeps sold out on a show that still takes place`() {
        scrapedEvent(soldOut = true).toEntity().soldOut shouldBe true
        scrapedEvent(status = "POSTPONED", soldOut = true).toEntity().soldOut shouldBe true
        scrapedEvent(subtitle = "Tour 2026 (ausverkauft)").toEntity().soldOut shouldBe true
    }

    // A "verlegt" badge sits on both ends of a move; the venue decides which end this row is (#1551).
    @Test
    fun `toEventEntity keeps RELOCATED with the destination on the row at the house the show left`() {
        val note = "Achtung: Die Show wird vom SO36 ins Hole44 verlegt"
        val entity = scrapedEvent(title = "KATE RYAN", status = "RELOCATED", statusNote = note).toEntity()

        entity.status shouldBe "RELOCATED"
        entity.relocatedTo shouldBe "Hole44"
    }

    @Test
    fun `toEventEntity schedules the row at the house the show moved to`() {
        val note = "Achtung: Die Show wird vom Huxleys ins SO36 verlegt"
        val arrived = scrapedEvent(title = "KATE RYAN", status = "RELOCATED", statusNote = note).toEntity()
        arrived.status shouldBe "SCHEDULED"
        arrived.relocatedTo.shouldBeNull()

        // Only the origin named, and it is another house.
        scrapedEvent(status = "RELOCATED", description = "Mad Tsai *live* verlegt vom Frannz").toEntity().status shouldBe "SCHEDULED"
    }

    // A "verschoben" note sits on both dates of a move; the row it moved to takes place (#2206).
    @Test
    fun `toEventEntity schedules the row whose own date the note names as the new date`() {
        val note = "Hinweis: Das Konzert wurde vom 06.03.2026 auf den 01.10.2026 verschoben!"
        val replacement = scrapedEvent(title = "Sampagne", status = "POSTPONED", statusNote = note, eventDate = LocalDate.of(2026, 10, 1))
        replacement.toEntity().status shouldBe "SCHEDULED"

        // Masego at Huxleys: a new date and a new house in one note, read on the new date's row.
        val masego = "Achtung: Die Show wird vom 30.09.2026 auf den 17.02.2027 verschoben und aus der Uber Eats Music Hall in das Huxleys verlegt!"
        scrapedEvent(status = "POSTPONED", statusNote = masego, eventDate = LocalDate.of(2027, 2, 17)).toEntity().status shouldBe "SCHEDULED"

        // A two-digit year, and "auf" without its article.
        scrapedEvent(status = "POSTPONED", statusNote = "VERSCHOBEN AUF DEN 08.05.27", eventDate = LocalDate.of(2027, 5, 8)).toEntity().status shouldBe
            "SCHEDULED"
        scrapedEvent(status = "POSTPONED", description = "verschoben auf 04.11.2026", eventDate = LocalDate.of(2026, 11, 4)).toEntity().status shouldBe
            "SCHEDULED"
    }

    @Test
    fun `toEventEntity keeps POSTPONED on the date the show left`() {
        val note = "Das Konzert wurde auf den 27.02.2027 verschoben"
        scrapedEvent(status = "POSTPONED", statusNote = note, eventDate = LocalDate.of(2026, 10, 7)).toEntity().status shouldBe "POSTPONED"
        // A badge that names no new date stays postponed.
        scrapedEvent(status = "POSTPONED", statusNote = "VERSCHOBEN (FRÜHLING 2027)", eventDate = LocalDate.of(2026, 10, 2)).toEntity().status shouldBe
            "POSTPONED"
        // Only POSTPONED is read: a cancelled row naming its own date stays cancelled.
        scrapedEvent(status = "CANCELLED", statusNote = "auf den 30.12.2026 verschoben, dann abgesagt").toEntity().status shouldBe "CANCELLED"
    }

    // A new date in another house is a move on the date the show left (#2708).
    @Test
    fun `toEventEntity relocates a postponement into another house on the date the show left, and schedules the new date`() {
        val note = "Achtung: Die Show muss vom 06.10.26 im Hole44 auf den 18.03.27 im Säälchen verschoben werden"
        val origin = scrapedEvent(status = "POSTPONED", statusNote = note, eventDate = LocalDate.of(2026, 10, 6)).toEntity()
        origin.status shouldBe "RELOCATED"
        origin.relocatedTo shouldBe "Säälchen"
        val replacement = scrapedEvent(status = "POSTPONED", statusNote = note, eventDate = LocalDate.of(2027, 3, 18)).toEntity()
        replacement.status shouldBe "SCHEDULED"
        replacement.relocatedTo.shouldBeNull()
    }

    @Test
    fun `toEventEntity reads the destination off the title, the subtitle or the description when there is no note`() {
        scrapedEvent(title = "Zoh Amba - Verlegt ins Bi Nuu", status = "RELOCATED").toEntity().relocatedTo shouldBe "Bi Nuu"
        scrapedEvent(status = "RELOCATED", subtitle = "Das Konzert wurde vom SO36 in Cassiopeia verlegt").toEntity().relocatedTo shouldBe "Cassiopeia"
        val moved = scrapedEvent(status = "RELOCATED", description = "Hinweis: Das Konzert wurde vom SO36 in den Privatclub verlegt!").toEntity()
        moved.relocatedTo shouldBe "Privatclub"
        // A badge that names nothing stays a bare RELOCATED, as before.
        val bare = scrapedEvent(title = "Turbopaolo", status = "RELOCATED", statusNote = "Verlegt / Relocated").toEntity()
        bare.status shouldBe "RELOCATED"
        bare.relocatedTo.shouldBeNull()
        // Any other status ignores a relocation sentence in the prose.
        scrapedEvent(status = "CANCELLED", description = "vom SO36 ins Hole44 verlegt, dann abgesagt").toEntity().relocatedTo.shouldBeNull()
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
    fun `toEventEntity keeps a fallback type a fallback only while no festival or genre cue changed it`() {
        scrapedEvent(eventType = "CONCERT").copy(typeIsFallback = true).toEntity().typeIsFallback shouldBe true
        scrapedEvent(title = "Lunatic Festival", eventType = "CONCERT").copy(typeIsFallback = true).toEntity().typeIsFallback shouldBe false
        scrapedEvent(eventType = "CONCERT", genre = "Lesung").copy(typeIsFallback = true).toEntity().typeIsFallback shouldBe false
        scrapedEvent(eventType = "CONCERT").toEntity().typeIsFallback shouldBe false
    }

    @Test
    fun `withGapsFrom takes the fallback flag with the type it fills`() {
        val listing = listingRow.copy(typeIsFallback = true)
        ScrapedEvent(title = "Detail", eventDate = listing.eventDate, sourceUrl = "u", sourceId = "d")
            .withGapsFrom(listing)
            .typeIsFallback shouldBe true
        ScrapedEvent(title = "Detail", eventType = "COMEDY", eventDate = listing.eventDate, sourceUrl = "u", sourceId = "d")
            .withGapsFrom(listing)
            .typeIsFallback shouldBe false
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
        // The fact that one was left out is kept, so the page can say so (#2130).
        entity.descriptionWithheld shouldBe true
        // Only the prohibited field goes. The other one is a separate answer for a separate right.
        entity.imageUrl shouldBe "https://example.test/a.jpg"
        entity.imageWithheld shouldBe false
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
        entity.imageWithheld shouldBe true
        entity.description shouldBe "Ein Abend mit Aussicht"
        entity.descriptionWithheld shouldBe false
    }

    @Test
    fun `toEventEntity records nothing withheld where the venue published nothing`() {
        val entity =
            scrapedEvent(description = null, imageUrl = null)
                .toEventEntity(
                    venueId = 1L,
                    venueSlug = "so36",
                    eventSourceId = 1L,
                    licences = licensed(SourceLicence.PROHIBITED, SourceLicence.PROHIBITED)
                )

        // A prohibition over an empty field took nothing away (#811).
        entity.descriptionWithheld shouldBe false
        entity.imageWithheld shouldBe false
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

    // A refusal is derived after the import too. Rebuilt as null, the next pass bought it again (#2714).
    @Test
    fun `toEventEntity keeps a recorded refusal while the description is unchanged, and drops it when rewritten`() {
        val refused = scrapedEvent(description = GERMAN_DESCRIPTION).toEntity().copy(id = 7L, descriptionAltRefusedHash = "hash-of-refused")

        scrapedEvent(description = GERMAN_DESCRIPTION).toEntity(existing = refused).descriptionAltRefusedHash shouldBe "hash-of-refused"
        scrapedEvent(description = "$GERMAN_DESCRIPTION Neu.").toEntity(existing = refused).descriptionAltRefusedHash shouldBe null
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

    // The venue's own second language is stored on the same footing as the first, and never as a translation (#330).
    @Test
    fun `toEventEntity stores the venue's second language as the publisher's alt text`() {
        val entity = scrapedEvent(description = GERMAN_DESCRIPTION).copy(descriptionAlt = ENGLISH_DESCRIPTION).toEntity()

        entity.description shouldBe GERMAN_DESCRIPTION
        entity.descriptionLanguage shouldBe "de"
        entity.descriptionAlt shouldBe ENGLISH_DESCRIPTION
        entity.descriptionAltLanguage shouldBe "en"
        entity.descriptionAltOrigin shouldBe PUBLISHER_ORIGIN
        entity.descriptionAltEngine shouldBe null
        entity.descriptionAltSourceHash shouldBe null
    }

    @Test
    fun `toEventEntity puts the venue's second language in place of a machine translation`() {
        val entity =
            scrapedEvent(description = GERMAN_DESCRIPTION)
                .copy(descriptionAlt = ENGLISH_DESCRIPTION)
                .toEntity(existing = translated(GERMAN_DESCRIPTION))

        entity.descriptionAlt shouldBe ENGLISH_DESCRIPTION
        entity.descriptionAltOrigin shouldBe PUBLISHER_ORIGIN
        entity.descriptionAltEngine shouldBe null
    }

    // One language twice, or a text no detection can call, would give the page nothing to choose between.
    @Test
    fun `toEventEntity stores no alt text in the description's own language or in none`() {
        val sameLanguage = scrapedEvent(description = GERMAN_DESCRIPTION).copy(descriptionAlt = "$GERMAN_DESCRIPTION Noch einmal.").toEntity()
        val unknown = scrapedEvent(description = GERMAN_DESCRIPTION).copy(descriptionAlt = "Lex Ludlow\nKaldera").toEntity()

        sameLanguage.descriptionAlt shouldBe null
        sameLanguage.descriptionAltOrigin shouldBe null
        unknown.descriptionAlt shouldBe null
    }

    @Test
    fun `toEventEntity drops a stored publisher alt text the venue no longer publishes`() {
        val stored = scrapedEvent(description = GERMAN_DESCRIPTION).copy(descriptionAlt = ENGLISH_DESCRIPTION).toEntity().copy(id = 7L)

        val entity = scrapedEvent(description = GERMAN_DESCRIPTION).toEntity(existing = stored)

        entity.descriptionAlt shouldBe null
        entity.descriptionAltOrigin shouldBe null
    }

    @Test
    fun `toEventEntity stores no alt text for a description the licence withholds`() {
        val entity =
            scrapedEvent(description = GERMAN_DESCRIPTION)
                .copy(descriptionAlt = ENGLISH_DESCRIPTION)
                .toEventEntity(
                    venueId = 1L,
                    venueSlug = "so36",
                    eventSourceId = 1L,
                    licences = licensed(SourceLicence.PROHIBITED, SourceLicence.UNCLEAR)
                )

        entity.descriptionAlt shouldBe null
        entity.descriptionAltOrigin shouldBe null
    }

    @Test
    fun `withGapsFrom takes the alt text with the description it fills, and keeps its own beside its own`() {
        val listing = listingRow.copy(descriptionAlt = ENGLISH_DESCRIPTION)

        scrapedEvent().withGapsFrom(listing).descriptionAlt shouldBe ENGLISH_DESCRIPTION
        scrapedEvent(description = GERMAN_DESCRIPTION).withGapsFrom(listing).descriptionAlt shouldBe null
    }

    // A failed detail page keeps the stored description, and the second language belongs to it.
    @Test
    fun `withGapsFromStored keeps the stored publisher alt text beside the stored description`() {
        val stored = scrapedEvent(description = GERMAN_DESCRIPTION).copy(descriptionAlt = ENGLISH_DESCRIPTION).toEntity().copy(id = 7L)

        val kept = scrapedEvent().copy(detailUnavailable = true).withGapsFromStored(stored)

        kept.description shouldBe GERMAN_DESCRIPTION
        kept.descriptionAlt shouldBe ENGLISH_DESCRIPTION
        kept.toEntity(existing = stored).descriptionAltOrigin shouldBe PUBLISHER_ORIGIN
    }

    @Test
    fun `withGapsFromStored keeps no machine translation as the venue's text`() {
        val kept = scrapedEvent().copy(detailUnavailable = true).withGapsFromStored(translated(GERMAN_DESCRIPTION))

        kept.descriptionAlt shouldBe null
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
        const val ENGLISH_DESCRIPTION =
            "The Bolshevik Spa Band was founded in East Berlin in 1986 as part of the political underground, " +
                "a few years before the fall of the Berlin Wall."
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

    // Presale dearer than the door is a misread, reported at the one boundary every source crosses (#1583).
    @Test
    fun `toEventEntity warns with the source id when presale is dearer than the door, and stores the row as read`() {
        val entity = withWarnings { scrapedEvent(pricePresale = BigDecimal("15.43"), priceBoxOffice = BigDecimal("15")).toEntity() }

        entity.first.pricePresale shouldBe BigDecimal("15.43")
        entity.first.priceBoxOffice shouldBe BigDecimal("15.00")
        val warning = entity.second.single()
        warning.formattedMessage shouldContain "15.43 above box office 15"
        warning.keyValuePairs.single { it.key == LogFields.EVENT_SOURCE_ID }.value shouldBe "so36:98223"
    }

    @Test
    fun `toEventEntity stays quiet when the door is dearer, equal, or one price is missing`() {
        val (_, warnings) =
            withWarnings {
                scrapedEvent(pricePresale = BigDecimal("15"), priceBoxOffice = BigDecimal("18")).toEntity()
                scrapedEvent(pricePresale = BigDecimal("15"), priceBoxOffice = BigDecimal("15")).toEntity()
                scrapedEvent(pricePresale = BigDecimal("15"), priceBoxOffice = null).toEntity()
            }

        warnings.shouldBeEmpty()
    }

    /** Runs [block] with a list appender on the root logger and returns its result beside the WARN lines it logged. */
    private fun <T> withWarnings(block: () -> T): Pair<T, List<ILoggingEvent>> {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        return try {
            block() to appender.list.filter { it.level == ch.qos.logback.classic.Level.WARN }
        } finally {
            root.detachAppender(appender)
            appender.stop()
        }
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

    // #2103: every venue's title or subtitle marker counts, whether or not its scraper reads one.
    @Test
    fun `toEventEntity marks a row sold out when its title or subtitle carries a marker`() {
        scrapedEvent(title = "SukOne [AUSVERKAUFT!]").toEntity().soldOut shouldBe true
        scrapedEvent(title = "KAYLA SHYX", subtitle = "DAS KONZERT IST RESTLOS AUSVERKAUFT UND ES WIRD KEINE TICKETS AN DER ABENDKASSE GEBEN!")
            .toEntity()
            .soldOut shouldBe true
        // The title is stored as the scraper wrote it; only the flag is added.
        scrapedEvent(title = "SukOne [AUSVERKAUFT!]").toEntity().title shouldBe "SukOne [AUSVERKAUFT!]"
        scrapedEvent(title = "The Act (Sold Out Tour)").toEntity().soldOut shouldBe false
    }
}
