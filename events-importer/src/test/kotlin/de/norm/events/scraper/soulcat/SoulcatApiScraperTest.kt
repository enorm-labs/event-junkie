package de.norm.events.scraper.soulcat

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class SoulcatApiScraperTest {
    private val events =
        SoulcatApiScraper()
            .scrapePage(
                javaClass.classLoader
                    .getResourceAsStream("scraper/soulcat/soulcat-events.json")!!
                    .bufferedReader()
                    .readText()
            ).events

    private fun event(slug: String) = events.single { it.sourceId == "soulcat:$slug" }

    @Test
    fun `skips the football screenings`() {
        events shouldHaveSize 21
        events.map { it.title } shouldNotContain "BL: BVB – Werder"
    }

    @Test
    fun `reads the DJs before the dash and the genre after it`() {
        val night = event("dj-krawallisch-60s-rnb-soul-blues")

        night.title shouldBe "DJ Krawallisch – 60s RnB, Soul & Blues"
        night.eventType shouldBe EventType.PARTY.name
        night.eventDate shouldBe LocalDate.of(2026, 10, 13)
        night.startTime shouldBe LocalTime.of(18, 30)
        night.endDate shouldBe LocalDate.of(2026, 10, 14)
        night.endTime shouldBe LocalTime.of(1, 0)
        night.genre shouldBe "60s RnB, Soul & Blues"
        night.artists shouldContainExactly listOf(ScrapedArtist(name = "Krawallisch", role = "DJ"))
        night.sourceUrl shouldBe "https://soulcat-berlin.com/events/dj-krawallisch-60s-rnb-soul-blues/"
    }

    @Test
    fun `splits two DJs and cuts the tail markers, marking a free night`() {
        val night = event("djs-lobotomy-mr-bacon-in-the-pan-soul-funk-60s-rnb-free-entry-dancers-welcome-vinyl-only")

        night.title shouldBe "DJs Lobotomy & Mr.Bacon in the Pan – Soul, Funk & 60s RnB"
        night.artists.map { it.name } shouldContainExactly listOf("Lobotomy", "Mr.Bacon in the Pan")
        night.free shouldBe true
    }

    @Test
    fun `cuts markers that follow an ellipsis`() {
        val night = event("dj-amok77-soul-rocknroll")

        night.title shouldBe "DJs DC Reverend & DCO – Working Class Dub – Roots & Dub"
        night.genre shouldBe "Working Class Dub, Roots & Dub"
        night.artists.map { it.name } shouldContainExactly listOf("DC Reverend", "DCO")
        night.free shouldBe true
    }

    @Test
    fun `reads a house night's head as its genre and names no act`() {
        val night = event("60s-rnb-soul-blues-rocknroll-bartenders-choice-vinyl-only-6")

        night.title shouldBe "60s RnB & Soul & Blues & RocknRoll – Bartenders Choice"
        night.genre shouldBe "60s RnB & Soul & Blues & RocknRoll"
        night.artists.shouldBeEmpty()
        night.free shouldBe false
    }

    @Test
    fun `leaves the genre to the house genre when the head is the whole title`() {
        val night = event("60s-rnb-soul-vinyl-only")

        night.title shouldBe "60s RnB & Soul"
        night.genre.shouldBeNull()
    }

    @Test
    fun `keeps a descriptive genre segment for the normalizer`() {
        val night = event("dj-stulle-scruffy-indie-anthems-some-underground-gems-vinyl-only")

        night.title shouldBe "DJ Stulle – Scruffy Indie Anthems & some Underground Gems"
        night.genre shouldBe "Scruffy Indie Anthems & some Underground Gems"
        night.artists.map { it.name } shouldContainExactly listOf("Stulle")
    }

    @Test
    fun `leaves no marker in any title`() {
        events.forEach { it.title.lowercase() shouldNotContain "vinyl only" }
    }
}
