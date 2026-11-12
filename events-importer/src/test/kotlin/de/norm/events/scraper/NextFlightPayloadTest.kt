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

    @Test
    fun `flightText reads a referenced text row up to its byte length, though the next row follows with no break`() {
        // `ü` and `ß` take two bytes each, so 0xc bytes are 10 characters.
        val payload = "0:{\"description\":\"\$1a\"}\n1a:Tc,Grüße\nmehr17:[\"\$\",\"div\"]"

        flightText(payload, "\$1a") shouldBe "Grüße\nmehr"
    }

    @Test
    fun `flightText counts a character outside the BMP as four bytes`() {
        flightText("2b:T7,🎉 okrest", "\$2b") shouldBe "🎉 ok"
    }

    @Test
    fun `flightText keeps a plain value and answers null for a reference to an absent row`() {
        flightText("", "5 years LIBIDOH") shouldBe "5 years LIBIDOH"
        flightText("1a:T3,abc", "\$2c").shouldBeNull()
    }

    @Test
    fun `balancedJsonAt cuts a JavaScript literal that no quoted key leads to`() {
        val script = """calendar({ eventSources: [[{"title":"Swing ]"}]], editable: false })"""

        balancedJsonAt(script, script.indexOf("[[")) shouldBe """[[{"title":"Swing ]"}]]"""
    }
}
