package de.norm.events.scraper

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.URI

class UriEncodingTest {
    @Test
    fun `encodes square brackets in the path, so URI accepts the Delphi poster`() {
        val delphi = "https://theater-im-delphi.de/wp-content/uploads/programm/bild/Kunstlieder_[no_text_3_2_1920x1280_300dpi]_(c)R.jpg"

        val encoded = delphi.encodeForUri()

        encoded shouldBe "https://theater-im-delphi.de/wp-content/uploads/programm/bild/Kunstlieder_%5Bno_text_3_2_1920x1280_300dpi%5D_(c)R.jpg"
        URI(encoded).host shouldBe "theater-im-delphi.de"
    }

    @Test
    fun `encodes every character URI refuses, in the path and the query`() {
        "https://example.test/a b|c{d}e^f`g\\h\"i<j>.jpg?x=[1]".encodeForUri() shouldBe
            "https://example.test/a%20b%7Cc%7Bd%7De%5Ef%60g%5Ch%22i%3Cj%3E.jpg?x=%5B1%5D"
    }

    @Test
    fun `encodes a non-ASCII path as UTF-8, which the Privatclub host needs`() {
        val privatclub = "https://privatclub-berlin.de/wp-content/uploads/2026/08/пост_берлин-1960x2450.png"

        val encoded = privatclub.encodeForUri()

        encoded shouldBe "https://privatclub-berlin.de/wp-content/uploads/2026/08/%D0%BF%D0%BE%D1%81%D1%82_%D0%B1%D0%B5%D1%80%D0%BB%D0%B8%D0%BD-1960x2450.png"
        URI(encoded).toASCIIString() shouldBe encoded
        encoded.encodeForUri() shouldBe encoded
    }

    @Test
    fun `encodes an umlaut and a character outside the BMP by their UTF-8 bytes`() {
        "https://example.test/Bühne 🎸.jpg".encodeForUri() shouldBe "https://example.test/B%C3%BChne%20%F0%9F%8E%B8.jpg"
    }

    @Test
    fun `leaves an escaped URL and a URL URI already accepts unchanged`() {
        val escaped = "https://example.test/resize?url=https%3A%2F%2Fa.test%2F%5Bx%5D.jpg"

        escaped.encodeForUri() shouldBe escaped
        "https://example.test/poster.jpg".encodeForUri() shouldBe "https://example.test/poster.jpg"
    }

    @Test
    fun `keeps the brackets of an IPv6 host`() {
        "http://[::1]:8080/a[1].jpg".encodeForUri() shouldBe "http://[::1]:8080/a%5B1%5D.jpg"
        "http://[::1]".encodeForUri() shouldBe "http://[::1]"
    }
}
