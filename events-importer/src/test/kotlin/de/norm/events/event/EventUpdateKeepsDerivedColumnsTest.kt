package de.norm.events.event

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * Unit tests for [keepingDerivedFrom] and [toEventArtistEntity], which keep an admin update from
 * resetting what the importer derived on the event (#2249) and on its lineup rows (#3026).
 */
class EventUpdateKeepsDerivedColumnsTest {
    private val stored =
        EventEntity(
            id = 7,
            venueId = 1,
            room = "Saal",
            eventSourceId = 3,
            title = "Stored title",
            description = "Stored blurb",
            descriptionAlt = "Stored blurb, translated",
            descriptionAltLanguage = "en",
            descriptionAltOrigin = "MACHINE",
            descriptionAltEngine = "deepl",
            descriptionAltSourceHash = "abc123",
            descriptionAltRefusedHash = "def456",
            descriptionWithheld = true,
            spokenLanguages = listOf("de", "en"),
            subtitleLanguage = "de",
            status = EventStatus.RELOCATED.name,
            relocatedTo = "Hole44",
            slug = "2026-10-03-stored",
            eventDate = LocalDate.of(2026, 10, 3),
            endDate = LocalDate.of(2026, 10, 5),
            endTime = LocalTime.of(6, 0),
            imageWithheld = true,
            lineupSourceUrl = "https://timetable.example/stored",
            sourceId = "test:stored",
            pinnedFields = listOf("title"),
            contentHash = "c0ffee",
            contentChangedAt = Instant.parse("2026-09-15T10:00:00Z"),
            createdAt = Instant.parse("2026-09-01T10:00:00Z")
        )

    /** What [EventRequest.toEventEntity] builds: only the request's columns, everything else at its default. */
    private val fromRequest =
        EventEntity(
            venueId = 1,
            title = "Edited title",
            description = "Stored blurb",
            status = EventStatus.RELOCATED.name,
            slug = "2026-10-03-edited",
            eventDate = LocalDate.of(2026, 10, 3),
            sourceId = "test:stored",
            pricePresale = BigDecimal("12.00")
        )

    @Test
    fun `an edit that leaves the description and the date alone keeps every derived column`() {
        val updated = fromRequest.keepingDerivedFrom(stored)

        KEPT.forEach { name -> valueOf(updated, name) shouldBe valueOf(stored, name) }
        updated.title shouldBe "Edited title"
        updated.pricePresale shouldBe BigDecimal("12.00")
    }

    @Test
    fun `a changed description drops the translation and keeps the rest`() {
        val updated = fromRequest.copy(description = "Edited blurb").keepingDerivedFrom(stored)

        updated.descriptionAlt.shouldBeNull()
        updated.descriptionAltLanguage.shouldBeNull()
        updated.descriptionAltOrigin.shouldBeNull()
        updated.descriptionAltEngine.shouldBeNull()
        updated.descriptionAltSourceHash.shouldBeNull()
        updated.descriptionAltRefusedHash.shouldBeNull()
        updated.room shouldBe "Saal"
        updated.endDate shouldBe stored.endDate
    }

    @Test
    fun `a date moved past the end drops the end, and one inside it keeps the end`() {
        val moved = fromRequest.copy(eventDate = LocalDate.of(2026, 10, 6)).keepingDerivedFrom(stored)
        moved.endDate.shouldBeNull()
        moved.endTime.shouldBeNull()

        val inside = fromRequest.copy(eventDate = LocalDate.of(2026, 10, 5)).keepingDerivedFrom(stored)
        inside.endDate shouldBe LocalDate.of(2026, 10, 5)
        inside.endTime shouldBe LocalTime.of(6, 0)
    }

    @Test
    fun `every event column is either the request's, kept from the stored row, or audit`() {
        val columns =
            EventEntity::class
                .primaryConstructor!!
                .parameters
                .map { it.name!! }
                .toSet()

        val unclassified = columns - REQUEST_OWNED - KEPT - AUDIT
        val stale = (REQUEST_OWNED + KEPT + AUDIT) - columns
        unclassified shouldBe emptySet()
        stale shouldBe emptySet()
    }

    @Test
    fun `the stored row sets every kept column, so the keep test proves something`() {
        val unset = KEPT.filter { valueOf(stored, it) == valueOf(fromRequest, it) }

        unset shouldBe emptyList()
    }

    @Test
    fun `a lineup row keeps the stored row's importer-owned columns, matched on the artist`() {
        val stored =
            EventArtistEntity(id = 1, eventId = 7, artistId = 11, role = "HEADLINER", titleDerived = true, setStart = SET, setEnd = SET.plusSeconds(3600))

        val row = EventArtistRequest(artistId = 11, role = ArtistRole.SUPPORT, billingOrder = 2).toEventArtistEntity(7, listOf(stored))

        row shouldBe
            EventArtistEntity(
                eventId = 7,
                artistId = 11,
                role = "SUPPORT",
                billingOrder = 2,
                titleDerived = true,
                setStart = SET,
                setEnd = SET.plusSeconds(3600)
            )
    }

    @Test
    fun `an artist stored twice is matched on its role, and a new artist gets the defaults`() {
        val headliner = EventArtistEntity(eventId = 7, artistId = 11, role = "HEADLINER", setStart = SET)
        val support = EventArtistEntity(eventId = 7, artistId = 11, role = "SUPPORT", titleDerived = true, setStart = SET.plusSeconds(60))
        val previous = listOf(headliner, support)

        EventArtistRequest(artistId = 11, role = ArtistRole.SUPPORT).toEventArtistEntity(7, previous).setStart shouldBe SET.plusSeconds(60)
        EventArtistRequest(artistId = 11, role = ArtistRole.HEADLINER).toEventArtistEntity(7, previous).setStart shouldBe SET

        val newcomer = EventArtistRequest(artistId = 12).toEventArtistEntity(7, previous)
        newcomer.titleDerived shouldBe false
        newcomer.setStart.shouldBeNull()
        newcomer.setEnd.shouldBeNull()
    }

    private fun valueOf(
        entity: EventEntity,
        name: String
    ): Any? = EventEntity::class.memberProperties.single { it.name == name }.get(entity)

    private companion object {
        val SET: Instant = Instant.parse("2026-10-03T22:00:00Z")

        /** Mapped by [EventRequest.toEventEntity]; the operator owns them. */
        val REQUEST_OWNED =
            setOf(
                "venueId",
                "title",
                "subtitle",
                "description",
                "descriptionLanguage",
                "descriptionLanguageConfidence",
                "eventType",
                // A type a person sets is no scraper default, so an edit clears the flag (ADR-039).
                "typeIsFallback",
                "status",
                "slug",
                "eventDate",
                "doorsTime",
                "startTime",
                "imageUrl",
                "sourceUrl",
                "sourceId",
                "ticketUrl",
                "facebookEventUrl",
                "genre",
                "pricePresale",
                "priceBoxOffice",
                "priceCurrency",
                "priceNote",
                "soldOut",
                "free",
                // The operator's pick (#1262). A PUT that leaves the field out keeps it (EventService.update).
                "featuredUntil"
            )

        /** Taken from the stored row by [keepingDerivedFrom]. */
        val KEPT =
            listOf(
                "id",
                "eventSourceId",
                "createdAt",
                "contentHash",
                "contentChangedAt",
                "pinnedFields",
                "room",
                "relocatedTo",
                "lineupSourceUrl",
                "descriptionWithheld",
                "imageWithheld",
                "spokenLanguages",
                "subtitleLanguage",
                "descriptionAlt",
                "descriptionAltLanguage",
                "descriptionAltOrigin",
                "descriptionAltEngine",
                "descriptionAltSourceHash",
                "descriptionAltRefusedHash",
                "endDate",
                "endTime"
            )

        /** Set by Spring Data on save. */
        val AUDIT = setOf("updatedAt")
    }
}
