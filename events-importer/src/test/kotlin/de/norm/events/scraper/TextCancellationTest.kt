package de.norm.events.scraper

import de.norm.events.event.EventStatus
import de.norm.events.licence.SourceLicence
import de.norm.events.licence.SourceLicences
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDate

class TextCancellationTest {
    private val night = LocalDate.of(2026, 11, 10)

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Wir müssen euch leider mitteilen, dass die Show nicht stattfinden wird.",
            "Das Konzert wird leider nicht stattfinden.",
            "Die Lesung findet heute leider nicht statt.",
            "Wir bedauern, dass der Abend nicht stattfinden kann.",
            "Die Show musste krankheitsbedingt abgesagt werden.",
            "Das Konzert ist leider abgesagt.",
            "Das Konzert wurde abgesagt!",
            "Die Party fällt leider aus.",
            "The show has been cancelled.",
            "Tonight's gig is canceled.",
            "The concert will not take place.",
            "The night has been called off."
        ]
    )
    fun `a strong phrase cancels the night`(text: String) {
        parseTextCancellation(text, night) shouldBe EventStatus.CANCELLED.name
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Nachdem die Band ihre Tour abgesagt hatte, kehrt sie nun zurück.",
            "Absage? Nicht mit uns.",
            "Cancel culture, a comedy hour.",
            "Ein Abend, der nicht oft stattfindet."
        ]
    )
    fun `a bare cancellation word in prose is no notice`(text: String) {
        parseTextCancellation(text, night).shouldBeNull()
    }

    @Test
    fun `a notice that names another date leaves this night alone`() {
        val bleachers =
            "Wir müssen euch leider mitteilen, dass die 2. Show am 10.11.2026 nicht stattfinden wird. Ihr werdet für die 1. Show am 09.11. 2026 umgebucht."

        parseTextCancellation(bleachers, night) shouldBe EventStatus.CANCELLED.name
        parseTextCancellation(bleachers, night.minusDays(1)).shouldBeNull()
    }

    @Test
    fun `a notice about an earlier year is a biography`() {
        parseTextCancellation("Die Tour 2020 musste wegen der Pandemie abgesagt werden.", night).shouldBeNull()
    }

    @Test
    fun `a notice with a new date is a postponement`() {
        parseTextCancellation("Die Lesung fällt leider aus wegen akuter Bronchitis, wird nachgeholt.", night) shouldBe EventStatus.POSTPONED.name
    }

    @Test
    fun `no text is no notice`() {
        parseTextCancellation(null, night).shouldBeNull()
        parseTextCancellation("  ", night).shouldBeNull()
    }

    private fun row(
        status: String = EventStatus.SCHEDULED.name,
        title: String = "Bleachers",
        description: String? = "Das Konzert wurde leider abgesagt."
    ) = ScrapedEvent(
        title = title,
        description = description,
        eventType = "CONCERT",
        eventDate = night,
        sourceUrl = "https://example.de/bleachers",
        sourceId = "columbiahalle:9740",
        status = status
    )

    @Test
    fun `a scheduled row whose description carries the notice is stored cancelled`() {
        row().toEventEntity(venueId = 1L, venueSlug = "columbiahalle", eventSourceId = 1L).status shouldBe EventStatus.CANCELLED.name
    }

    @Test
    fun `a status the title states wins over the description`() {
        row(title = "VERSCHOBEN: Bleachers").toEventEntity(venueId = 1L, venueSlug = "columbiahalle", eventSourceId = 1L).status shouldBe
            EventStatus.POSTPONED.name
    }

    @ParameterizedTest
    @EnumSource(EventStatus::class, names = ["SCHEDULED"], mode = EnumSource.Mode.EXCLUDE)
    fun `a badge that is not scheduled is never overridden by the description`(badge: EventStatus) {
        val notice = "Die Show fällt leider aus, wird nachgeholt. Das Konzert wurde leider abgesagt."

        row(status = badge.name, description = notice).toEventEntity(venueId = 1L, venueSlug = "columbiahalle", eventSourceId = 1L).status shouldBe
            badge.name
    }

    @Test
    fun `a source that withholds its text still has its cancellation read`() {
        val stored =
            row().toEventEntity(
                venueId = 1L,
                venueSlug = "columbiahalle",
                eventSourceId = 1L,
                licences = SourceLicences(description = SourceLicence.PROHIBITED, image = SourceLicence.UNCLEAR)
            )

        stored.description.shouldBeNull()
        stored.status shouldBe EventStatus.CANCELLED.name
    }
}
