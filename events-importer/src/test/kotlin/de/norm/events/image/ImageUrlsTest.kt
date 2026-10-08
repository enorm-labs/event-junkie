package de.norm.events.image

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.URI

class ImageUrlsTest {
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
