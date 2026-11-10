package de.norm.events.scraper.zku

import de.norm.events.event.EventType
import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class ZkuCalendarScraperTest {
    private val baseUrl = "https://www.zku-berlin.org/"

    private val events by lazy {
        ZkuCalendarScraper().scrape(fixtureDocument("zku/zku-home.html", baseUrl), baseUrl)
    }

    private fun event(title: String) = events.single { it.title.startsWith(title) }

    @Test
    fun `keeps the stage programme and drops markets, workshops, meetings and conferences`() {
        events.map { it.title } shouldContainExactly
            listOf(
                "The MOAZIN celebrates its Anniversary!",
                "Matthias Einhoff: From Roof to Boat: Collective Practice and the Politics of Scale",
                "Under a Blue Sun",
                "Dorothee Richter: Feminist Perspectives On Curating",
                "Diffusion; a meddling.",
                "FREIPLATZ presents: Doin’ It In The Park",
                "Cornelia Sollfrank: Revisiting Feminist Art – What we can learn from early Feminist Art",
                "UNRUH & friends",
                "Marie Schleef: The Gap as a Source of Inspiration: Toward a Feminist Theater Practice"
            )
    }

    @Test
    fun `reads a club night with its time range past midnight`() {
        val night = event("UNRUH")

        night.eventDate shouldBe LocalDate.of(2026, 10, 24)
        night.startTime shouldBe LocalTime.of(21, 0)
        night.endTime shouldBe LocalTime.of(3, 0)
        night.endDate shouldBe LocalDate.of(2026, 10, 25)
        night.sourceUrl shouldBe "https://www.zku-berlin.org/timeline/unruh-friends/"
        night.sourceId shouldBe "zku:unruh-friends-2026-10-24"
        night.subtitle.shouldBeNull()
        night.imageUrl shouldBe "https://www.zku-berlin.org/fileadmin/_processed_/1/1/csm_UNRUH_friends_Fyler_V5_ae28f1abd6.png"
        night.eventType shouldBe EventType.OTHER.name
        night.typeIsFallback shouldBe true
    }

    @Test
    fun `types a curatorial talk as a reading and bills its speaker`() {
        val talk = event("Dorothee Richter")

        talk.eventType shouldBe EventType.READING.name
        talk.subtitle shouldBe "Talks on Curatorial Practice"
        talk.startTime shouldBe LocalTime.of(17, 0)
        talk.endTime.shouldBeNull()
        talk.artists.map { it.name } shouldContainExactly listOf("Dorothee Richter")
    }

    @Test
    fun `types a FOOD and FOOTAGE evening as a screening from its open-ended start`() {
        val screening = event("FREIPLATZ")

        screening.eventType shouldBe EventType.SCREENING.name
        screening.startTime shouldBe LocalTime.of(18, 0)
        screening.artists.shouldBeEmpty()
    }

    @Test
    fun `names a participation format in the title or the subtitle`() {
        isZkuParticipationFormat("GÜTERMARKT WINTER EDITION 2026", null) shouldBe true
        isZkuParticipationFormat("Design Approaches to Drawing and Sketching", "Workshop") shouldBe true
        isZkuParticipationFormat("OPENHAUS NOVEMBER 2026", "Open studios, performance, video, installation and resident talks") shouldBe true
        isZkuParticipationFormat("On Lingering", "Session 4: Vita Contemplativa (Ch. 12, in full)") shouldBe true
        isZkuParticipationFormat("Berlin Cake Academy", "4th annual edition at ZK/U!") shouldBe true
        isZkuParticipationFormat("Some Talk", "OnCurating Academy Berlin") shouldBe false
        isZkuParticipationFormat("UNRUH & friends", null) shouldBe false
    }

    @Test
    fun `keys a two-day entry once per day`() {
        events.map { it.sourceId }.distinct() shouldHaveSize events.size
        events.map { it.title } shouldNotContain "Trendwechsel Conference 2026"
    }

    @Test
    fun `reads a title the site renders as an h3 styled h2`() {
        // Verbatim from the live homepage on 2026-11-10 (#4812).
        val html =
            """
            <div class="calendar-events" style="display: none;" id="d-2026-11-13">
              <div class="grid-x block list-item list-item--yellow">
                <div class="auto cell list-item__content">
                  <h3 class="h2">
                    Sarah Buser: Participatory Theater in Hybrid Spaces
                  </h3>
                  <p class="date">
                    13 NOVEMBER 2026 / 17:00
                  </p>
                  <p class="list-item__description list-item__description--no-padding">
                    Talks on Curatorial Practice
                  </p>
                </div>
                <a class="list-item__link" href="/timeline/sarah-buser-participatory-theater-in-hybrid-spaces">
                  <span class="show-for-sr">Sarah Buser: Participatory Theater in Hybrid Spaces</span>
                </a>
              </div>
            </div>
            """.trimIndent()

        val talk = ZkuCalendarScraper().scrape(Jsoup.parse(html, baseUrl), baseUrl).single()

        talk.title shouldBe "Sarah Buser: Participatory Theater in Hybrid Spaces"
        talk.eventDate shouldBe LocalDate.of(2026, 11, 13)
        talk.startTime shouldBe LocalTime.of(17, 0)
        talk.subtitle shouldBe "Talks on Curatorial Practice"
        talk.sourceUrl shouldBe "https://www.zku-berlin.org/timeline/sarah-buser-participatory-theater-in-hybrid-spaces"
        talk.eventType shouldBe EventType.READING.name
        talk.artists.map { it.name } shouldContainExactly listOf("Sarah Buser")
    }

    @Test
    fun `an empty page gives no events`() {
        ZkuCalendarScraper().scrape(Jsoup.parse("<html><body></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }
}
