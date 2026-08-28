package de.norm.events.image

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * The server-side request forgery guard (ADR-019 §4).
 *
 * **These are the assertions that make the control real.** The URL under test comes out of a venue's
 * HTML, so every case here is something a page we do not control could name. The importer runs on
 * the same private network as PostgreSQL and the k3s API.
 */
class ImageUrlValidatorTest {
    private val validator = ImageUrlValidator()

    @ParameterizedTest
    @ValueSource(
        strings = [
            "http://127.0.0.1/a.jpg",
            "http://localhost/a.jpg",
            // The address staging's PostgreSQL actually listens on.
            "http://10.1.1.10:5432/a.jpg",
            "http://192.168.1.1/a.jpg",
            "http://172.16.0.1/a.jpg",
            // Cloud instance metadata, the reason link-local is blocked rather than merely odd.
            "http://169.254.169.254/latest/meta-data/",
            "http://[::1]/a.jpg",
            "http://0.0.0.0/a.jpg"
        ]
    )
    fun `refuses an address inside our own network`(url: String) {
        validator.reject(url) shouldNotBe null
    }

    @ParameterizedTest
    @ValueSource(strings = ["file:///etc/passwd", "gopher://example.test/", "ftp://example.test/a.jpg", "data:image/png;base64,AAAA"])
    fun `refuses a scheme that is not http or https`(url: String) {
        validator.reject(url) shouldNotBe null
    }

    @Test
    fun `refuses a host that does not resolve`() {
        validator.reject("https://no-such-host.invalid/a.jpg") shouldNotBe null
    }

    @Test
    fun `refuses a malformed URL`() {
        validator.reject("http://[not-an-address/a.jpg") shouldNotBe null
    }

    @Test
    fun `allows an ordinary public image URL`() {
        // example.com is IANA-reserved and resolves publicly, so this asserts the guard permits
        // rather than merely that it refuses everything.
        validator.reject("https://example.com/poster.jpg").shouldBeNull()
    }
}
