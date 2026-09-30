package de.norm.events.scraper

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class JsonLdEventsTest {
    private fun names(json: String) = jsonLdEvents(json).map { it.stringOrNull("name") }

    private fun event(json: String) = jsonLdEvents(json).single()

    @Test
    fun `reads the events whatever the block wraps them in`() {
        names("""{"@type": "Event", "name": "Bare"}""") shouldContainExactly listOf("Bare")
        names("""[{"@type": "MusicEvent", "name": "A"}, {"@type": "MusicEvent", "name": "B"}]""") shouldContainExactly listOf("A", "B")
        names("""{"@graph": [{"@type": "WebPage"}, {"@type": "Event", "name": "Graph"}]}""") shouldContainExactly listOf("Graph")
        names("""[{"@graph": [{"@type": "Festival", "name": "Nested"}]}]""") shouldContainExactly listOf("Nested")
    }

    @Test
    fun `keeps only nodes typed as an Event or one of its subtypes`() {
        names(
            """
            [
              {"@type": "Organization", "name": "Venue"},
              {"@type": ["Event", "Thing"], "name": "Listed"},
              {"@type": "schema:TheaterEvent", "name": "Prefixed"},
              {"@type": "https://schema.org/ComedyEvent", "name": "Url"},
              {"name": "Untyped"},
              "not an object"
            ]
            """.trimIndent()
        ) shouldContainExactly listOf("Listed", "Prefixed", "Url")
    }

    @Test
    fun `skips a malformed block and keeps the page's other blocks in order`() {
        val document =
            Jsoup.parse(
                """
                <script type="application/ld+json">{"@type": "Event", "name": "First"}</script>
                <script type="application/ld+json">{"@type": "Event", "name": </script>
                <div><script type="application/ld+json">[{"@type": "MusicEvent", "name": "Second"}]</script></div>
                <script type="application/json">{"@type": "Event", "name": "Not JSON-LD"}</script>
                """.trimIndent()
            )

        document.jsonLdEvents().map { it.stringOrNull("name") } shouldContainExactly listOf("First", "Second")
        jsonLdEvents("not json").shouldBeEmpty()
    }

    @Test
    fun `reads the name with entities decoded`() {
        event("""{"@type": "Event", "name": " Rock &amp; Roll "}""").schemaName() shouldBe "Rock & Roll"
        event("""{"@type": "Event", "name": " "}""").schemaName().shouldBeNull()
    }

    @Test
    fun `reads the date and the wall-clock time of every timestamp shape`() {
        val node =
            event(
                """
                {"@type": "Event", "startDate": "2026-07-31T20:00:00+02:00", "endDate": "2026-08-01T01:30:00+0200",
                 "doorTime": "19:00", "previousStartDate": "2026-07-30T21:15"}
                """.trimIndent()
            )

        node.schemaDate("startDate") shouldBe LocalDate.of(2026, 7, 31)
        node.schemaTime("startDate") shouldBe LocalTime.of(20, 0)
        node.schemaTime("endDate") shouldBe LocalTime.of(1, 30)
        node.schemaTime("doorTime") shouldBe LocalTime.of(19, 0)
        node.schemaTime("previousStartDate") shouldBe LocalTime.of(21, 15)
    }

    @Test
    fun `a date-only or missing value has no time`() {
        val node = event("""{"@type": "Event", "startDate": "2026-12-20"}""")

        node.schemaDate("startDate") shouldBe LocalDate.of(2026, 12, 20)
        node.schemaTime("startDate").shouldBeNull()
        node.schemaTime("doorTime").shouldBeNull()
        node.schemaDate("endDate").shouldBeNull()
    }

    @Test
    fun `maps the event status`() {
        event("""{"@type": "Event", "eventStatus": "https://schema.org/EventCancelled"}""").schemaStatus() shouldBe "CANCELLED"
        event("""{"@type": "Event"}""").schemaStatus() shouldBe "SCHEDULED"
    }

    @Test
    fun `reads the image from a string, an ImageObject or an array of either`() {
        event("""{"@type": "Event", "image": "https://x.de/a.jpg"}""").schemaImageUrl() shouldBe "https://x.de/a.jpg"
        event("""{"@type": "Event", "image": {"@type": "ImageObject", "url": "https://x.de/b.jpg"}}""").schemaImageUrl() shouldBe "https://x.de/b.jpg"
        event("""{"@type": "Event", "image": ["https://x.de/c.jpg", "https://x.de/d.jpg"]}""").schemaImageUrl() shouldBe "https://x.de/c.jpg"
        event("""{"@type": "Event", "image": [{"url": "https://x.de/e.jpg"}]}""").schemaImageUrl() shouldBe "https://x.de/e.jpg"
        event("""{"@type": "Event", "image": "/relative.jpg"}""").schemaImageUrl().shouldBeNull()
        event("""{"@type": "Event"}""").schemaImageUrl().shouldBeNull()
    }

    @Test
    fun `reads one offer or an array of offers`() {
        event("""{"@type": "Event", "offers": {"price": "10"}}""").schemaOffers() shouldHaveSize 1
        event("""{"@type": "Event", "offers": [{"price": "10"}, {"price": "12"}]}""").schemaOffers() shouldHaveSize 2
        event("""{"@type": "Event"}""").schemaOffers().shouldBeEmpty()
    }

    @Test
    fun `is sold out only when every offer is`() {
        event("""{"@type": "Event", "offers": {"availability": "https://schema.org/SoldOut"}}""").schemaSoldOut() shouldBe true
        event("""{"@type": "Event", "offers": [{"availability": "OutOfStock"}, {"availability": "SoldOut"}]}""").schemaSoldOut() shouldBe true
        event("""{"@type": "Event", "offers": [{"availability": "SoldOut"}, {"availability": "InStock"}]}""").schemaSoldOut() shouldBe false
        event("""{"@type": "Event"}""").schemaSoldOut() shouldBe false
    }
}
