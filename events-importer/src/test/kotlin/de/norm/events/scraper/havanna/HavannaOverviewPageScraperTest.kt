package de.norm.events.scraper.havanna

import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.DayOfWeek

/**
 * Unit tests for [HavannaOverviewPageScraper].
 *
 * The overview page carries no event data at all — it is a static three-column teaser — so these
 * tests cover the one job it has: discovering the three weekly night pages and their posters.
 */
class HavannaOverviewPageScraperTest {
    private val baseUrl = "https://www.havanna-berlin.de/events"
    private val scraper = HavannaOverviewPageScraper()

    private val links by lazy { scraper.scrape(fixtureDocument("havanna/havanna-overview.html", baseUrl), baseUrl) }

    @Test
    fun `discovers the three weekly night pages in page order`() {
        links shouldHaveSize 3
        links.map { it.url } shouldContainExactly
            listOf(
                "https://www.havanna-berlin.de/wednesday",
                "https://www.havanna-berlin.de/friday",
                "https://www.havanna-berlin.de/saturday"
            )
    }

    @Test
    fun `derives each night's weekday from its page path`() {
        links.map { it.dayOfWeek } shouldContainExactly
            listOf(DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)
    }

    @Test
    fun `captures the teaser poster shown for each night`() {
        links.first().imageUrl shouldBe
            "https://images.squarespace-cdn.com/content/v1/568d0402a128e6a4d712eadf/1460626986965-I2KUU0X82WE6NOBF9U9Z/image-asset.jpeg"
        links.forEach { it.imageUrl!!.startsWith("https://") shouldBe true }
    }

    @Test
    fun `ignores buttons whose path names no weekday`() {
        // The footer's "Subscribe" button (/newsletter) sits outside the main content block, and a
        // night page's "‹ Back to Events" button (/events) names no weekday — neither is a night.
        links.map { it.url }.none { it.endsWith("/newsletter") || it.endsWith("/events") } shouldBe true
    }

    @Test
    fun `returns an empty list for a page without teaser columns`() {
        val emptyDoc = Jsoup.parse("<html><body><p>No events</p></body></html>", baseUrl)
        scraper.scrape(emptyDoc, baseUrl).shouldBeEmpty()
    }
}
