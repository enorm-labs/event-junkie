package de.norm.events.scraper

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldBeEmpty
import io.kotest.matchers.string.shouldContain
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

/** Unit tests for the Next.js flight-payload readers. */
class NextFlightPayloadTest {
    private fun page(vararg scripts: String) = Jsoup.parse(scripts.joinToString("") { "<script>$it</script>" }.let { "<html><body>$it</body></html>" })

    @Test
    fun `nextFlightPayload joins the chunks and undoes their escaping`() {
        val document =
            page(
                """self.__next_f.push([1,"{\"events\":[{\"title\":\"A"])""",
                """self.__next_f.push([1,"\"}]}"])"""
            )

        nextFlightPayload(document) shouldBe """{"events":[{"title":"A"}]}"""
    }

    @Test
    fun `nextFlightPayload ignores a page that carries none`() {
        nextFlightPayload(page("""console.log("hello")""")).shouldBeEmpty()
    }

    @Test
    fun `jsonArrayAt cuts the array the key names`() {
        val payload = """{"other":[1,2],"events":[{"id":"a"},{"id":"b"}],"tail":true}"""

        jsonArrayAt(payload, "events") shouldBe """[{"id":"a"},{"id":"b"}]"""
    }

    @Test
    fun `jsonArrayAt is not fooled by a bracket inside a string value`() {
        val payload = """{"events":[{"title":"Techno ] Night"}]}"""

        jsonArrayAt(payload, "events")!! shouldContain "Techno ] Night"
    }

    @Test
    fun `jsonArrayAt answers null for an absent key and an unclosed array`() {
        jsonArrayAt("""{"events":[{"id":"a"}]}""", "dates").shouldBeNull()
        jsonArrayAt("""{"events":[{"id":"a"}""", "events").shouldBeNull()
    }
}
