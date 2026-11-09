package de.norm.events

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ClientKindTest {
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36 | browser",
            "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html) | Googlebot",
            "Mozilla/5.0 (Linux; Android 6.0.1; Nexus 5X Build/MMB29P) Chrome/141.0 Mobile (compatible; Googlebot/2.1) | Googlebot",
            "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko; compatible; ElevenlabsCrawler/1.0; +https://elevenlabs.io) | ElevenlabsCrawler",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 HeadlessChrome/141.0.0.0 Safari/537.36 | HeadlessChrome",
            "curl/8.7.1 | curl",
            "python-requests/2.32.3 | python-requests",
            "Go-http-client/1.1 | Go-http-client"
        ]
    )
    fun `names the agent, or calls it a browser`(
        userAgent: String,
        kind: String
    ) {
        ClientKind.of(userAgent) shouldBe kind
    }

    @Test
    fun `a missing or blank header is none`() {
        ClientKind.of(null) shouldBe ClientKind.NONE
        ClientKind.of("  ") shouldBe ClientKind.NONE
        ClientKind.of("()") shouldBe ClientKind.NONE
    }

    @Test
    fun `cuts a long name, so the column keeps few values`() {
        ClientKind.of("Mozilla/5.0 (compatible; ${"x".repeat(60)}bot/1.0)") shouldBe "x".repeat(40)
    }
}
