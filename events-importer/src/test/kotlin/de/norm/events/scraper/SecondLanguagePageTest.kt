package de.norm.events.scraper

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** Unit tests for the second-language page of a venue that translates its event text (#330). */
class SecondLanguagePageTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val germanUrl = "https://venue.example/de/show.html"
    private val englishUrl = "https://venue.example/en/show.html"

    private fun page(
        url: String,
        text: String,
        alternate: String? = null
    ): Document =
        Jsoup.parse(
            "<html><head>${alternate?.let { "<link rel=alternate hreflang=en href=$it>" }.orEmpty()}</head><body><p class=text>$text</p></body></html>",
            url
        )

    private fun night(day: Int) =
        ScrapedEvent(title = "Revue", eventDate = LocalDate.of(2026, 10, day), sourceUrl = germanUrl, sourceId = "venue:2026-10-$day-revue")

    private val read = { document: Document -> document.selectFirst("p.text")?.text() }

    private suspend fun enrich(events: List<ScrapedEvent>) =
        htmlFetcher.enrichFromSharedPages(
            events,
            parse = read,
            apply = { text: String, event: ScrapedEvent -> event.copy(description = text) },
            secondLanguage = SecondLanguagePage("en", read)
        )

    @Test
    fun `reads the alternate URL from a link or a language switch`() {
        page(germanUrl, "x", alternate = englishUrl).alternateLanguageUrl("en") shouldBe englishUrl
        Jsoup.parse("<a hreflang=en href=/en/>EN</a>", germanUrl).alternateLanguageUrl("en") shouldBe "https://venue.example/en/"
        page(germanUrl, "x", alternate = germanUrl).alternateLanguageUrl("en").shouldBeNull()
        page(germanUrl, "x").alternateLanguageUrl("en").shouldBeNull()
    }

    @Test
    fun `fetches each shared page's English version once and gives every night its text`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(germanUrl) } returns page(germanUrl, "Eine Revue über das Kino", alternate = englishUrl)
            coEvery { htmlFetcher.fetchDocument(englishUrl) } returns page(englishUrl, "A revue about the cinema")

            val events = enrich(listOf(night(9), night(10)))

            events.map { it.descriptionAlt } shouldBe listOf("A revue about the cinema", "A revue about the cinema")
            coVerify(exactly = 1) { htmlFetcher.fetchDocument(englishUrl) }
        }

    @Test
    fun `keeps the first language and flags nothing when the English page fails`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(germanUrl) } returns page(germanUrl, "Eine Revue über das Kino", alternate = englishUrl)
            coEvery { htmlFetcher.fetchDocument(englishUrl) } throws HttpFetchException(503, englishUrl)

            val events = enrich(listOf(night(9)))

            events shouldHaveSize 1
            events.single().description shouldBe "Eine Revue über das Kino"
            events.single().descriptionAlt.shouldBeNull()
            events.single().detailUnavailable shouldBe false
        }

    @Test
    fun `sets no second language that repeats the first or replaces the scraper's own`() {
        val described = night(9).copy(description = "Same text")
        described.withDescriptionAlt("Same text").descriptionAlt.shouldBeNull()
        described.withDescriptionAlt(" ").descriptionAlt.shouldBeNull()
        night(9).withDescriptionAlt("No first text").descriptionAlt.shouldBeNull()
        described.copy(descriptionAlt = "Split").withDescriptionAlt("Other").descriptionAlt shouldBe "Split"
    }
}
