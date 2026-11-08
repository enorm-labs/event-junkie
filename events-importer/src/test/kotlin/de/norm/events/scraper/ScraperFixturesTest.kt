package de.norm.events.scraper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.nio.charset.Charset

class ScraperFixturesTest {
    private val path = "aeden/aeden-overview.html"
    private val url = "https://aedenberlin.com/events/"

    @Test
    fun `reads a fixture under scraper and names the path of a missing one`() {
        fixtureText(path) shouldStartWith "<!doctype html>"
        shouldThrow<IllegalStateException> { fixtureText("aeden/missing.html") }.message shouldContain "scraper/aeden/missing.html"
    }

    @Test
    fun `parses a fixture with its base URI`() {
        val document = fixtureDocument(path, url)

        document.location() shouldBe url
        document.title() shouldBe "Events – aedenberlin.com"
    }

    @Test
    fun `detects the charset from the page when none is given`() {
        val latin1 = "kitkatclub/kitkatclub-programme.html"

        fun description(charset: Charset?) = fixtureDocument(latin1, charset = charset).select("meta[name=description]").attr("content")

        description(charset = null) shouldContain "informiert über"
        description(Charsets.UTF_8) shouldNotContain "informiert über"
    }

    @Test
    fun `answers each stubbed URL with its fixture on every fetch method`() =
        runTest {
            val fetcher = stubFetcher(url to path)

            val result = fetcher.fetch(url).shouldBeInstanceOf<FetchResult.Success>()
            result.document.location() shouldBe url
            result.etag.shouldBeNull()
            fetcher.fetchDocument(url).title() shouldBe "Events – aedenberlin.com"
            fetcher.fetchHtml(url) shouldBe fixtureText(path)
        }
}
