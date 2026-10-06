package de.norm.events.event

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** [EventContentStamp.hash]: what counts as a change of the event page, and what does not. */
class EventContentStampTest {
    private val event =
        EventEntity(
            id = 1,
            venueId = 2,
            title = "Nachtschicht",
            subtitle = null,
            slug = "2026-10-10-nachtschicht",
            eventDate = LocalDate.of(2026, 10, 10),
            startTime = LocalTime.of(23, 0),
            endDate = LocalDate.of(2026, 10, 11),
            endTime = LocalTime.of(6, 0),
            sourceId = "test:nachtschicht",
            pricePresale = BigDecimal("15.00")
        )
    private val lineup =
        listOf(
            EventArtistEntity(eventId = 1, artistId = 20, role = "DJ", billingOrder = 1),
            EventArtistEntity(eventId = 1, artistId = 10, role = "HEADLINER", billingOrder = 0, setStart = Instant.parse("2026-10-10T22:00:00Z"))
        )

    private fun hash(
        of: EventEntity = event,
        acts: List<EventArtistEntity> = lineup,
        promoterIds: List<Long> = listOf(5, 3)
    ) = EventContentStamp.hash(of, acts, promoterIds)

    @Test
    fun `the same content hashes the same, whatever order the join rows come back in`() {
        hash() shouldBe hash(acts = lineup.reversed(), promoterIds = listOf(3, 5))
    }

    @Test
    fun `a price differs only by its scale, which is no change`() {
        hash(event.copy(pricePresale = BigDecimal("15"))) shouldBe hash()
    }

    @Test
    fun `bookkeeping and the fields the page does not show leave the hash alone`() {
        val bookkeeping =
            event.copy(
                id = 9,
                slug = "other",
                sourceId = "test:other",
                sourceUrl = "https://example.org/a",
                facebookEventUrl = "https://facebook.com/events/1",
                imageUrl = "https://example.org/a.jpg?v=2",
                genre = "Techno",
                descriptionAlt = "A night until morning.",
                pinnedFields = listOf("title"),
                contentHash = "old",
                contentChangedAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH
            )
        hash(bookkeeping) shouldBe hash()
    }

    @Test
    fun `each field the page shows moves the hash`() {
        val changes =
            listOf(
                event.copy(title = "Nachtschicht II"),
                event.copy(subtitle = "Open Air"),
                event.copy(description = "Bis zum Morgen."),
                event.copy(eventType = EventType.FESTIVAL.name),
                event.copy(status = EventStatus.CANCELLED.name),
                event.copy(relocatedTo = "Lido"),
                event.copy(eventDate = event.eventDate.plusDays(1)),
                event.copy(doorsTime = LocalTime.of(22, 0)),
                event.copy(startTime = LocalTime.of(23, 30)),
                event.copy(endDate = event.eventDate.plusDays(2)),
                event.copy(endTime = LocalTime.of(8, 0)),
                event.copy(venueId = 3),
                event.copy(room = "Saal"),
                event.copy(pricePresale = BigDecimal("16.00")),
                event.copy(priceBoxOffice = BigDecimal("20.00")),
                event.copy(priceNote = "Abendkasse"),
                event.copy(soldOut = true),
                event.copy(free = true),
                event.copy(ticketUrl = "https://tickets.example/1")
            )
        changes.forEach { changed -> withClue(changed) { hash(changed) shouldNotBe hash() } }
        hash(acts = lineup.drop(1)) shouldNotBe hash()
        hash(acts = lineup.map { it.copy(stage = "Garten") }) shouldNotBe hash()
        hash(promoterIds = listOf(3)) shouldNotBe hash()
    }

    @Test
    fun `no text passes for a null, and no field for two`() {
        hash(event.copy(subtitle = "-")) shouldNotBe hash()
        hash(event.copy(subtitle = "null")) shouldNotBe hash()
        hash(event.copy(title = "a", subtitle = "1:b")) shouldNotBe hash(event.copy(title = "a1:b", subtitle = null))
    }
}
