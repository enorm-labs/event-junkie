package de.norm.events.scraper.acudmachtneu

import de.norm.events.event.EventType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate

class AcudMachtNeuOverviewPageScraperTest {
    private val scraper = AcudMachtNeuOverviewPageScraper()
    private val url = "https://acudmachtneu.de/programm/"

    private val events = scraper.scrape(document("acudmachtneu-overview.html"), url)

    private fun event(id: Int) = events.single { it.sourceId == "acud_macht_neu:$id" }

    @Test
    fun `reads every event of the month and each running exhibition once`() {
        events shouldHaveSize 15
        events.map { it.sourceId }.distinct() shouldHaveSize 15
    }

    @Test
    fun `reads a concert with its date, picture and the act from its title`() {
        val event = event(2390)

        event.title shouldBe "Burre w/ Heavenphetamine"
        event.eventDate shouldBe LocalDate.of(2026, 10, 16)
        event.endDate.shouldBeNull()
        event.eventType shouldBe EventType.CONCERT.name
        event.typeIsFallback shouldBe false
        event.sourceUrl shouldBe "https://acudmachtneu.de/events/2390/burre-w-heavenphetamine"
        event.imageUrl shouldBe "https://acudmachtneu.de/resources/uploads/2026/10/blurred-frame-3-640x360.jpg"
        event.artists.map { it.name } shouldContainExactly listOf("Burre", "Heavenphetamine")
    }

    @Test
    fun `drops the format from a concert title before billing the act`() {
        event(2383).artists.map { it.name } shouldContainExactly listOf("LEYA")
    }

    @Test
    fun `types a club night as a party, even when it is also filed as a concert, and bills nobody`() {
        val event = event(2381)

        event.eventType shouldBe EventType.PARTY.name
        event.artists.shouldBeEmpty()
    }

    @Test
    fun `reads an exhibition's run across months, and one that began before the year's page`() {
        event(2379).eventType shouldBe EventType.EXHIBITION.name
        event(2379).endDate shouldBe LocalDate.of(2026, 12, 6)
        event(2331).eventDate shouldBe LocalDate.of(2026, 4, 30)
        event(2331).endDate shouldBe LocalDate.of(2026, 12, 6)
    }

    @Test
    fun `falls back to the title when the venue files an event under no category`() {
        val event = event(2388)

        event.typeIsFallback shouldBe true
        event.eventDate shouldBe LocalDate.of(2026, 10, 11)
    }

    @Test
    fun `reads only the running exhibitions from a month the venue has not filled yet`() {
        val month = scraper.scrape(document("acudmachtneu-month-empty.html"), "https://acudmachtneu.de/programm/2026/11/")

        month.map { it.sourceId } shouldContainExactly listOf("acud_macht_neu:2331", "acud_macht_neu:2379")
    }

    private fun document(name: String) =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/acudmachtneu/$name")!!
                .bufferedReader()
                .readText(),
            url
        )
}
