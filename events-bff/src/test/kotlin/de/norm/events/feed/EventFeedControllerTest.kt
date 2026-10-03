package de.norm.events.feed

import de.norm.events.BaseControllerTest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.r2dbc.core.await
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.ByteArrayInputStream
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import javax.xml.parsers.DocumentBuilderFactory

class EventFeedControllerTest : BaseControllerTest() {
    /** A Friday far enough ahead that no test run sees it as over, so the date text is fixed. */
    private val friday = LocalDate.of(2099, 6, 12)

    private val cacheLifetime = CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic()

    private fun feedXml(query: String = ""): String =
        webTestClient
            .get()
            .uri("/events/feed$query")
            .exchange()
            .expectStatus()
            .isOk
            .expectHeader()
            .contentTypeCompatibleWith(MediaType.parseMediaType("application/rss+xml"))
            .expectHeader()
            .cacheControl(cacheLifetime)
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!

    /** Parses the feed, which fails the test on a document a reader would reject. */
    private fun parse(xml: String): Document =
        DocumentBuilderFactory
            .newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray()))

    private fun feed(query: String = ""): Document = parse(feedXml(query))

    private fun Document.items(): List<Element> = getElementsByTagName("item").elements()

    private fun Document.channel(): Element = getElementsByTagName("channel").elements().single()

    private fun Document.selfLink(): Element = getElementsByTagNameNS("http://www.w3.org/2005/Atom", "link").elements().single()

    private fun Element.text(name: String): String? = getElementsByTagName(name).elements().firstOrNull()?.textContent

    private fun Element.texts(name: String): List<String> = getElementsByTagName(name).elements().map { it.textContent }

    private fun NodeList.elements(): List<Element> = (0 until length).map { item(it) as Element }

    private suspend fun firstSeen(
        eventId: Long,
        at: Instant
    ) {
        databaseClient
            .sql("UPDATE events.event SET created_at = :at WHERE id = :id")
            .bind("at", at)
            .bind("id", eventId)
            .await()
    }

    @Test
    fun `lists events newest first, with canonical links and stable ids, and leaves out those that are over`(): Unit =
        runBlocking {
            val venueId = insertVenue("Lido", "lido")
            val older = insertEvent(venueId, "Older Find", "older-find", friday)
            val newer = insertEvent(venueId, "Newer Find", "newer-find", friday, startTime = LocalTime.of(20, 0))
            val over = insertEvent(venueId, "Over", "over", LocalDate.now().minusDays(2))
            firstSeen(older, Instant.parse("2099-01-01T10:00:00Z"))
            firstSeen(newer, Instant.parse("2099-01-02T10:00:00Z"))
            firstSeen(over, Instant.parse("2099-01-03T10:00:00Z"))
            linkArtist(newer, insertArtist("Sam Prekop", "sam-prekop"))
            linkGenre(newer, insertGenreTag("Ambient", "ambient"))

            val doc = feed()

            val items = doc.items()
            items.map { it.text("title") } shouldContainExactly listOf("Newer Find", "Older Find")
            val first = items.first()
            first.text("link") shouldBe "https://event-junkie.de/en/events/newer-find"
            first.text("guid") shouldBe "tag:event-junkie.de,2026:event:$newer"
            first
                .getElementsByTagName("guid")
                .elements()
                .single()
                .getAttribute("isPermaLink") shouldBe "false"
            first.text("pubDate") shouldBe "Fri, 2 Jan 2099 10:00:00 GMT"
            first.text("description") shouldBe "Fri 12 Jun 2099 · 20:00 · Lido · Sam Prekop"
            first.texts("category") shouldContainExactly listOf("Ambient")

            val channel = doc.channel()
            channel.text("lastBuildDate") shouldBe "Fri, 2 Jan 2099 10:00:00 GMT"
            channel.text("language") shouldBe "en"
            channel.text("link") shouldBe "https://event-junkie.de/en/events"
            doc.selfLink().getAttribute("href") shouldBe "https://event-junkie.de/feed.xml"
            doc.selfLink().getAttribute("rel") shouldBe "self"
        }

    @Test
    fun `escapes markup and drops characters XML forbids, so one scraped title cannot void the feed`(): Unit =
        runBlocking {
            val venueId = insertVenue("Tom & Jerry's <Bar>", "tom-and-jerrys-bar")
            insertEvent(venueId, "Rock & Roll <Live> \"Night\"\u0007", "rock-and-roll", friday, subtitle = "Ü & Ö")

            val xml = feedXml()

            xml shouldContain "<title>Rock &amp; Roll &lt;Live&gt; \"Night\"</title>"
            val item = parse(xml).items().single()
            item.text("title") shouldBe "Rock & Roll <Live> \"Night\""
            item.text("description") shouldBe "Ü & Ö · Fri 12 Jun 2099 · Tom & Jerry's <Bar>"
        }

    @Test
    fun `takes the list's filters, and names them in its self link`(): Unit =
        runBlocking {
            val lido = insertVenue("Lido", "lido")
            val astra = insertVenue("Astra", "astra")
            val techno = insertGenreTag("Techno", "techno")
            linkGenre(insertEvent(lido, "Lido Techno", "lido-techno", friday), techno)
            insertEvent(lido, "Lido Folk", "lido-folk", friday)
            linkGenre(insertEvent(astra, "Astra Techno", "astra-techno", friday), techno)

            val xml = feedXml("?genre=techno&venue=lido")

            val doc = parse(xml)
            doc.items().map { it.text("title") } shouldContainExactly listOf("Lido Techno")
            doc.selfLink().getAttribute("href") shouldBe "https://event-junkie.de/feed.xml?genre=techno&venue=lido"
            xml shouldContain "feed.xml?genre=techno&amp;venue=lido"
        }

    @Test
    fun `speaks German when asked, in its links and its dates`(): Unit =
        runBlocking {
            val venueId = insertVenue("Lido", "lido")
            insertEvent(venueId, "Abend", "abend", friday, doorsTime = LocalTime.of(19, 0))

            val doc = feed("?locale=de")

            val item = doc.items().single()
            item.text("link") shouldBe "https://event-junkie.de/de/events/abend"
            item.text("description") shouldBe "Fr., 12. Juni 2099 · 19:00 · Lido"
            doc.channel().text("language") shouldBe "de"
            doc.channel().text("title") shouldBe "Event Junkie — neue Veranstaltungen"
        }

    @Test
    fun `caps the feed at the fifty newest`(): Unit =
        runBlocking {
            val venueId = insertVenue("Lido", "lido")
            val ids = (1..51).map { insertEvent(venueId, "Night $it", "night-$it", friday) }
            ids.forEachIndexed { index, id -> firstSeen(id, Instant.parse("2099-01-01T00:00:00Z").plusSeconds(index.toLong())) }

            val titles = feed().items().map { it.text("title") }

            titles shouldHaveSize 50
            titles.first() shouldBe "Night 51"
            titles shouldNotContain "Night 1"
        }

    @Test
    fun `an empty feed is a valid feed without a build date`() {
        val doc = feed()

        doc.items().shouldBeEmpty()
        doc.getElementsByTagName("lastBuildDate").length shouldBe 0
    }

    @Test
    fun `answers a reader that already has this version with 304, keeping the cache lifetime`(): Unit =
        runBlocking {
            insertEvent(insertVenue("Lido", "lido"), "Night", "night", friday)
            val etag =
                webTestClient
                    .get()
                    .uri("/events/feed")
                    .exchange()
                    .expectStatus()
                    .isOk
                    .returnResult(String::class.java)
                    .responseHeaders.eTag!!
            etag shouldStartWith "W/\""

            webTestClient
                .get()
                .uri("/events/feed")
                .header(HttpHeaders.IF_NONE_MATCH, etag)
                .exchange()
                .expectStatus()
                .isNotModified
                .expectHeader()
                .cacheControl(cacheLifetime)
        }

    @Test
    fun `rejects a locale it has no text for, and a parameter the list does not take`() {
        webTestClient
            .get()
            .uri("/events/feed?locale=fr")
            .exchange()
            .expectStatus()
            .isBadRequest
        webTestClient
            .get()
            .uri("/events/feed?from=2099-01-01")
            .exchange()
            .expectStatus()
            .isBadRequest
    }
}
