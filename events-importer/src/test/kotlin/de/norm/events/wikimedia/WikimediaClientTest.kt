package de.norm.events.wikimedia

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.boot.info.BuildProperties
import org.springframework.web.reactive.function.client.WebClient

/** The MediaWiki and Wikipedia REST reads against a local server, on captured responses. */
class WikimediaClientTest {
    private lateinit var server: MockWebServer

    @BeforeEach
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.close()
    }

    private fun client(): WikimediaClient {
        val properties =
            WikimediaProperties(
                wikidataBaseUrl = server.url("/wikidata/api.php").toString(),
                commonsBaseUrl = server.url("/commons/api.php").toString(),
                wikipediaBaseUrl = server.url("/").toString() + "{lang}wiki",
                politeDelayMillis = 0
            )
        val webClient =
            WikimediaHttpClientConfig().wikimediaWebClient(
                webClientBuilder = WebClient.builder(),
                properties = properties,
                buildProperties = DefaultListableBeanFactory().getBeanProvider(BuildProperties::class.java)
            )
        return WikimediaClient(webClient, properties)
    }

    private fun fixture(name: String) =
        javaClass.classLoader
            .getResourceAsStream("wikimedia/$name.json")!!
            .bufferedReader()
            .readText()

    private fun json(body: String) =
        MockResponse
            .Builder()
            .code(200)
            .addHeader("Content-Type", "application/json")
            .body(body)
            .build()

    @Test
    fun `asks Wikidata for the P18 claim, then Commons for that file, and reads what the credit needs`() =
        runTest {
            server.enqueue(json(fixture("wbgetclaims-Q3374548")))
            server.enqueue(json(fixture("imageinfo-hausswolff")))

            val image = client().imageFor("Q3374548")

            val wikidata = server.takeRequest()
            wikidata.target shouldBe "/wikidata/api.php?action=wbgetclaims&entity=Q3374548&property=P18&format=json"
            wikidata.headers["User-Agent"]!! shouldStartWith "event-junkie/dev"
            server.takeRequest().target shouldBe
                "/commons/api.php?action=query&titles=File%3AAnna%20von%20Hausswolff%2C%202022.jpg&prop=imageinfo" +
                "&iiprop=url%7Cextmetadata%7Csize%7Cmime&iiurlwidth=1600&format=json"

            image shouldBe
                CommonsImage(
                    // The `utm_` campaign Commons appends is dropped; the column holds the image.
                    thumbUrl = "https://upload.wikimedia.org/wikipedia/commons/d/de/Anna_von_Hausswolff%2C_2022.jpg",
                    licenceShortName = "CC BY 2.0",
                    artistHtml =
                        "<a rel=\"nofollow\" class=\"external text\" href=\"https://www.flickr.com/people/64654599@N00\">Paul Hudson</a>" +
                            " from United Kingdom",
                    descriptionUrl = "https://commons.wikimedia.org/wiki/File:Anna_von_Hausswolff,_2022.jpg",
                    size = 142_401,
                    mime = "image/jpeg",
                    // Commons served the original: the file is narrower than the width asked.
                    servedOriginal = true
                )
        }

    @Test
    fun `an item without a P18 is no picture, and Commons is not asked`() =
        runTest {
            server.enqueue(json(fixture("wbgetclaims-Q816535")))

            client().imageFor("Q816535").shouldBeNull()
            server.requestCount shouldBe 1
        }

    @Test
    fun `an unknown item answers 200 with an error object, which is no picture too`() =
        runTest {
            server.enqueue(json("""{"error":{"code":"no-such-entity","info":"Could not find an entity with the ID \"Q0\"."}}"""))

            client().imageFor("Q0").shouldBeNull()
            server.requestCount shouldBe 1
        }

    @Test
    fun `a file Commons no longer has is no picture`() =
        runTest {
            server.enqueue(json(fixture("wbgetclaims-Q3374548")))
            server.enqueue(json("""{"query":{"pages":{"-1":{"title":"File:Anna von Hausswolff, 2022.jpg","missing":""}}}}"""))

            client().imageFor("Q3374548").shouldBeNull()
        }

    @Test
    fun `an error status is unavailable, never a parsed body`() =
        runTest {
            server.enqueue(MockResponse.Builder().code(503).build())

            shouldThrow<WikimediaUnavailableException> { client().imageFor("Q3374548") }.message shouldContain "503"
        }

    @Test
    fun `reads the item's sitelinks, then the preferred wiki's summary, and keeps the article URL for the credit`() =
        runTest {
            server.enqueue(json(fixture("wbgetentities-Q27897897")))
            server.enqueue(json(fixture("summary-de-giant-rooks")))

            val extract = client().extractFor("Q27897897", listOf("de", "en"))

            server.takeRequest().target shouldBe
                "/wikidata/api.php?action=wbgetentities&ids=Q27897897&props=sitelinks&sitefilter=dewiki%7Cenwiki&format=json"
            server.takeRequest().target shouldBe "/dewiki/api/rest_v1/page/summary/Giant_Rooks"
            extract shouldBe
                WikipediaExtract(
                    language = "de",
                    text = "Giant Rooks ist eine deutsche Indie-Pop-Band aus Hamm, die 2014 gegründet wurde.",
                    pageUrl = "https://de.wikipedia.org/wiki/Giant_Rooks"
                )
        }

    @Test
    fun `falls back to the other wiki when the preferred one has no article, and encodes the title as one segment`() =
        runTest {
            server.enqueue(json(fixture("wbgetentities-Q66734658")))
            server.enqueue(json(fixture("summary-en-war-on-women")))

            val extract = client().extractFor("Q66734658", listOf("de", "en"))

            server.takeRequest()
            server.takeRequest().target shouldBe "/enwiki/api/rest_v1/page/summary/War_on_Women_%28band%29"
            extract?.language shouldBe "en"
            extract?.pageUrl shouldBe "https://en.wikipedia.org/wiki/War_on_Women_(band)"
        }

    @Test
    fun `an unknown item, a disambiguation page and a vanished article are no extract`() =
        runTest {
            server.enqueue(json(fixture("wbgetentities-missing")))
            client().extractFor("Q999999999999", listOf("de", "en")).shouldBeNull()

            server.enqueue(json(fixture("wbgetentities-Q27897897")))
            server.enqueue(json(fixture("summary-en-disambiguation")))
            client().extractFor("Q27897897", listOf("en", "de")).shouldBeNull()

            server.enqueue(json(fixture("wbgetentities-Q27897897")))
            server.enqueue(MockResponse.Builder().code(404).build())
            client().extractFor("Q27897897", listOf("de", "en")).shouldBeNull()

            server.requestCount shouldBe 5
        }

    @Test
    fun `a server error on the summary is unavailable, so the row is retried`() =
        runTest {
            server.enqueue(json(fixture("wbgetentities-Q27897897")))
            server.enqueue(MockResponse.Builder().code(503).build())

            shouldThrow<WikimediaUnavailableException> { client().extractFor("Q27897897", listOf("de", "en")) }.message shouldContain "503"
        }
}
