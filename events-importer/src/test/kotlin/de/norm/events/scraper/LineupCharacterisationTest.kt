package de.norm.events.scraper

import de.norm.events.common.BERLIN
import de.norm.events.scraper.kater.KaterOverviewPageScraper
import de.norm.events.scraper.minimalbar.MinimalBarOverviewPageScraper
import de.norm.events.scraper.renate.RenateOverviewPageScraper
import de.norm.events.scraper.tresor.TresorDetailPageScraper
import de.norm.events.scraper.tresor.TresorOverviewPageScraper
import de.norm.events.scraper.voidclub.VoidClubOverviewPageScraper
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Clock
import java.time.Instant

/**
 * Every act the venues on [parseActSlot] bill from their saved pages. A change to the shared slot rules shows here for each
 * venue, also where no test of the venue names the act. After a wanted change, copy the actual file over the expected one.
 */
class LineupCharacterisationTest {
    @Test
    fun `the saved pages bill the same acts`() {
        val actual = LINEUPS.entries.joinToString("\n") { (fixture, scrape) -> "# $fixture\n${render(scrape(fixture))}" }
        File("build/lineups.txt").apply { parentFile.mkdirs() }.writeText(actual)

        actual shouldBe File("src/test/resources/lineup/lineups.txt").readText()
    }

    private fun render(events: List<ScrapedEvent>): String =
        events.joinToString("") { event ->
            event.artists.joinToString("") { "${event.sourceId} | ${it.stage.orEmpty()} | ${it.name} | ${it.role}\n" }
        }

    private companion object {
        fun clockAt(day: String): Clock = Clock.fixed(Instant.parse("${day}T10:00:00Z"), BERLIN)

        fun tresorPage(fixture: String): List<ScrapedEvent> {
            val url = "https://tresorberlin.com/event/20260801-tresor-klubnacht/"
            return listOfNotNull(TresorDetailPageScraper().scrape(fixtureDocument(fixture, url), url))
        }

        val LINEUPS: Map<String, (String) -> List<ScrapedEvent>> =
            mapOf(
                "kater/kater-overview.html" to { KaterOverviewPageScraper(clockAt("2026-08-01")).scrape(fixtureDocument(it), "https://www.katerclub.de/") },
                "renate/renate-overview.html" to { RenateOverviewPageScraper(clockAt("2026-08-01")).scrape(fixtureDocument(it), "https://www.renate.cc/") },
                "renate/renate-overview-2026-11-10.html" to
                    { RenateOverviewPageScraper(clockAt("2026-11-10")).scrape(fixtureDocument(it), "https://www.renate.cc/") },
                "tresor/tresor-events.html" to { TresorOverviewPageScraper().scrape(fixtureDocument(it), "https://tresorberlin.com/club/events/") },
                "tresor/tresor-detail-klubnacht.html" to ::tresorPage,
                "tresor/tresor-detail-globus-session.html" to ::tresorPage,
                "tresor/tresor-detail-no-times.html" to ::tresorPage,
                "voidclub/voidclub-overview.html" to
                    { VoidClubOverviewPageScraper(clockAt("2026-08-04")).scrape(fixtureDocument(it), "https://www.void-club.de/") },
                "minimalbar/minimalbar-overview.html" to { MinimalBarOverviewPageScraper().scrape(fixtureDocument(it), "https://minimalbar.de/dates/") }
            )
    }
}
