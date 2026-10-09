package de.norm.events.importing

import de.norm.events.event.EventEntity
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/** ADR-043 rule 5 on its own: date, start within the hour, and the title when several fit. */
class EnrichmentMatchingTest {
    private val date = LocalDate.of(2026, 11, 6)

    private fun main(
        id: Long,
        title: String,
        start: LocalTime?,
        on: LocalDate = date
    ) = EventEntity(id = id, venueId = 1, title = title, slug = "e-$id", eventDate = on, startTime = start, sourceId = "lido:$id")

    private fun offer(
        title: String,
        start: LocalTime?,
        on: LocalDate = date
    ) = ScrapedEvent(title = title, eventDate = on, startTime = start, sourceUrl = "https://puschen.example/", sourceId = "puschen:x")

    @Test
    fun `one candidate on the date within the hour matches, whatever its title`() {
        val alpha = main(1, "Alpha Band", LocalTime.of(20, 0))
        matchEnrichment(offer("Something else", LocalTime.of(21, 0)), listOf(alpha)) shouldBe EnrichmentMatch.Matched(alpha)
    }

    @Test
    fun `a start more than an hour away, or another date, matches nothing`() {
        val alpha = main(1, "Alpha Band", LocalTime.of(20, 0))
        matchEnrichment(offer("Alpha Band", LocalTime.of(21, 1)), listOf(alpha)) shouldBe EnrichmentMatch.Unmatched
        matchEnrichment(offer("Alpha Band", LocalTime.of(20, 0), date.plusDays(1)), listOf(alpha)) shouldBe EnrichmentMatch.Unmatched
    }

    @Test
    fun `a missing start time on either side cannot rule a candidate out`() {
        val alpha = main(1, "Alpha Band", null)
        matchEnrichment(offer("Alpha Band", LocalTime.of(23, 0)), listOf(alpha)) shouldBe EnrichmentMatch.Matched(alpha)
        val beta = main(2, "Beta", LocalTime.of(14, 0))
        matchEnrichment(offer("Beta", null), listOf(beta)) shouldBe EnrichmentMatch.Matched(beta)
    }

    @Test
    fun `when several fit, the title with the most shared words decides, and a tie or no shared word decides nothing`() {
        val alpha = main(1, "Alpha Band", LocalTime.of(20, 0))
        val gamma = main(2, "Gamma Quartet", LocalTime.of(20, 30))
        val both = listOf(alpha, gamma)
        matchEnrichment(offer("Puschen presents: GAMMA Quartet (live)", LocalTime.of(20, 15)), both) shouldBe EnrichmentMatch.Matched(gamma)
        matchEnrichment(offer("Delta", LocalTime.of(20, 15)), both) shouldBe EnrichmentMatch.Ambiguous
        matchEnrichment(offer("Alpha Gamma", LocalTime.of(20, 15)), both) shouldBe EnrichmentMatch.Ambiguous
    }

    @Test
    fun `a very long title still matches by its words`() {
        val long = "The Extraordinarily Long Farewell Tour Of A Band Whose Name Goes On And On ".repeat(5).trim()
        val target = main(1, long, LocalTime.of(19, 0))
        val other = main(2, "Short", LocalTime.of(19, 30))
        matchEnrichment(offer("Puschen: $long", LocalTime.of(19, 0)), listOf(target, other)) shouldBe EnrichmentMatch.Matched(target)
    }
}
