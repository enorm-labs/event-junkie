package de.norm.events.scraper.pandaplatforma

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

class PandaPlatformaApiScraperTest {
    private val scraper = PandaPlatformaApiScraper()

    private val page by lazy {
        scraper.scrapePage(
            javaClass.classLoader
                .getResourceAsStream("scraper/pandaplatforma/pandaplatforma-events.json")!!
                .bufferedReader()
                .readText()
        )
    }

    private fun event(slug: String) = page.events.single { it.sourceId == "panda_platforma:$slug" }

    @Test
    fun `reads every event except the night at another club`() {
        page.events shouldHaveSize 21
        page.events.none { it.sourceId.contains("pandagoes-frannz") } shouldBe true
        page.nextPageUrl.shouldBeNull()
    }

    @Test
    fun `maps a jazz night with its credited players`() {
        val jazz = event("pandajazz-haffner-reznichenko-mills-breu")

        jazz.title shouldBe "Haffner / Reznichenko / Mills / Breu"
        jazz.eventType shouldBe "CONCERT"
        jazz.typeIsFallback shouldBe false
        jazz.genre shouldBe "Experimental, Jazz"
        jazz.eventDate shouldBe LocalDate.of(2026, 10, 28)
        jazz.startTime shouldBe LocalTime.of(20, 0)
        jazz.endTime shouldBe LocalTime.of(22, 30)
        jazz.pricePresale shouldBe BigDecimal("10")
        jazz.free shouldBe false
        jazz.sourceUrl shouldBe "https://panda-platforma.berlin/event/pandajazz-haffner-reznichenko-mills-breu/"
        jazz.imageUrl shouldBe "https://panda-platforma.berlin/wp-content/uploads/Black-and-White-Design-6-.jpg"
        jazz.artists.map { it.name } shouldContainExactly listOf("Jonathon Haffner", "Olga Reznichenko", "Kellen Mills", "Maximilian Breu")
        jazz.promoters.shouldBeEmpty()
    }

    @Test
    fun `types a night by its series, text before music`() {
        event("tobias-bamborschke-aus-der-ferne-wirkt-die-h-lle-wie-ein-abenteuer").eventType shouldBe "READING"
        event("panda-poetry-slam-halloween").eventType shouldBe "READING"
        event("55th-bday-of-delphinov-theatrical-poetry-performance-44").eventType shouldBe "SHOW"
        event("exhibition-opening-the-eye-of-the-beholder-by-ekaterina-sisfontes").eventType shouldBe "EXHIBITION"
        event("trawy-i-kamienie").genre shouldBe "World"
    }

    @Test
    fun `falls back on the title for an event filed under no series`() {
        val reading = event("28-staged-reading")

        reading.typeIsFallback shouldBe true
        reading.ticketUrl shouldBe "https://www.eventbrite.com/e/28-staged-reading-tickets-2002750668161"
        reading.pricePresale.shouldBeNull()
        reading.free shouldBe false
    }

    @Test
    fun `reads a free event and a price range`() {
        val lecture = event("gasan-gusejnov-russian-profanity-in-a-disintegrating-empire")
        lecture.free shouldBe true
        lecture.pricePresale.shouldBeNull()

        val range = event("foaie-verde-virtuosic-world-music-from-budapest-across-the-balkans-to-the-black-sea")
        range.pricePresale.shouldBeNull()
        range.priceNote shouldBe "€20.00 – €25.00"
    }

    @Test
    fun `credits no players on a night without a credit list`() {
        event("igr-titov").artists.shouldBeEmpty()
    }

    @Test
    fun `decodes the entities WordPress leaves in a title`() {
        page.events.map { it.title }.forEach { it shouldNotContain "&#" }
    }

    @Test
    fun `returns no events for a body that is not JSON`() {
        scraper.scrapePage("<html>maintenance</html>").events.shouldBeEmpty()
    }
}
