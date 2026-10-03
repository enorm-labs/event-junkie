package de.norm.events.scraper.comedycafe

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

class ComedyCafeApiScraperTest {
    private val scraper = ComedyCafeApiScraper()

    private fun readFixture(name: String) =
        javaClass.classLoader
            .getResourceAsStream("scraper/comedycafe/$name")!!
            .bufferedReader()
            .readText()

    private val pageOne by lazy { scraper.scrapePage(readFixture("comedycafe-events-page1.json")) }

    @Test
    fun `reads the fifty events of the first page except the one at the training studio`() {
        pageOne.events shouldHaveSize 49
        pageOne.events.map { it.title }.none { it.contains("CCB Studios") } shouldBe true
    }

    @Test
    fun `hands back the API's cursor, and none on the last page`() {
        pageOne.nextPageUrl.shouldNotBeNull() shouldContain "page=2"
        scraper.scrapePage(readFixture("comedycafe-events-page2.json")).nextPageUrl.shouldBeNull()
    }

    @Test
    fun `maps a priced show with every field the API carries`() {
        val show = pageOne.events.first { it.sourceId == "comedy_cafe:house-show-224" }

        show.title shouldBe "House Show"
        show.eventType shouldBe "COMEDY"
        show.eventDate shouldBe LocalDate.of(2026, 10, 2)
        show.startTime shouldBe LocalTime.of(20, 0)
        show.doorsTime shouldBe LocalTime.of(19, 45)
        show.endDate shouldBe LocalDate.of(2026, 10, 2)
        show.endTime shouldBe LocalTime.of(21, 0)
        show.pricePresale shouldBe BigDecimal("12.50")
        show.free shouldBe false
        show.sourceUrl shouldBe "https://www.comedycafeberlin.com/event/house-show-224/"
        show.imageUrl shouldBe "https://www.comedycafeberlin.com/wp-content/uploads/2025/07/House-Show.jpg"
        show.description.shouldNotBeNull() shouldStartWith "CCB House Teams perform longform improv comedy!"
        show.artists.shouldBeEmpty()
        show.promoters.shouldBeEmpty()
    }

    @Test
    fun `marks a free show as free without a price`() {
        val free = pageOne.events.filter { it.free }

        free.isNotEmpty() shouldBe true
        free.all { it.pricePresale == null } shouldBe true
    }

    @Test
    fun `decodes the entities WordPress leaves in a title`() {
        pageOne.events.map { it.title }.forEach { it shouldNotContain "&#" }
    }

    @Test
    fun `drops the 23-59 placeholder end of a late show`() {
        val late = pageOne.events.first { it.title == "Forms Night" }

        late.startTime shouldBe LocalTime.of(23, 0)
        late.endTime.shouldBeNull()
        late.endDate.shouldBeNull()
    }

    @Test
    fun `degrades a body that is not JSON to an empty last page`() {
        val broken = scraper.scrapePage("not json at all")

        broken.events.shouldBeEmpty()
        broken.nextPageUrl.shouldBeNull()
    }
}
