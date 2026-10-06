package de.norm.events.sitemap

import de.norm.events.BaseControllerTest
import de.norm.events.ClockConfiguration
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.r2dbc.core.await
import java.time.Duration
import java.time.LocalDate

class SitemapControllerTest : BaseControllerTest() {
    private val today = LocalDate.now(ClockConfiguration.BERLIN)

    private fun sitemap(kind: String): String =
        webTestClient
            .get()
            .uri("/sitemaps/$kind.xml")
            .exchange()
            .expectStatus()
            .isOk
            .expectHeader()
            .contentTypeCompatibleWith(MediaType.APPLICATION_XML)
            .expectHeader()
            .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!

    /** The page paths a sitemap lists, in order, without the origin. */
    private fun locs(xml: String): List<String> = Regex("<loc>https://event-junkie\\.de(/[^<]*)</loc>").findAll(xml).map { it.groupValues[1] }.toList()

    @Test
    fun `lists an event until it is over, in both locales, and drops it after`(): Unit =
        runBlocking {
            val venueId = insertVenue("Lido", "lido")
            insertEvent(venueId, "Tonight", "tonight", today)
            insertEvent(venueId, "Running", "running", today.minusDays(3), endDate = today.plusDays(1))
            insertEvent(venueId, "Gone", "gone", today.minusDays(1))

            assertThat(locs(sitemap("events"))).containsExactly(
                "/en/events/running",
                "/de/events/running",
                "/en/events/tonight",
                "/de/events/tonight"
            )
        }

    @Test
    fun `stamps each event url with when its content changed, and a venue url with nothing`(): Unit =
        runBlocking {
            val venueId = insertVenue("Lido", "lido")
            insertEvent(venueId, "Changed", "changed", today.plusDays(1))
            insertEvent(venueId, "Unstamped", "unstamped", today.plusDays(2))
            databaseClient
                .sql("UPDATE events.event SET content_changed_at = TIMESTAMPTZ '2026-10-06 22:31:40.123456+02' WHERE slug = 'changed'")
                .await()

            val xml = sitemap("events")

            val urls = xml.split("</url>").filter { "<loc>" in it }
            assertThat(urls).hasSize(4)
            assertThat(urls.filter { "/events/changed<" in it }).allSatisfy {
                assertThat(it).containsPattern("</loc>\\s*<lastmod>2026-10-06T20:31:40Z</lastmod>")
            }
            assertThat(urls.filter { "/events/unstamped<" in it }).noneMatch { "<lastmod>" in it }
            assertThat(sitemap("venues")).doesNotContain("<lastmod>")
        }

    @Test
    fun `gives every url the full hreflang set, x-default included, so the annotation is two-way`(): Unit =
        runBlocking {
            insertVenue("Lido", "lido")

            val xml = sitemap("venues")

            val german = xml.substringAfter("<loc>https://event-junkie.de/de/venues/lido</loc>").substringBefore("</url>")
            assertThat(german)
                .contains("""<xhtml:link href="https://event-junkie.de/en/venues/lido" hreflang="en" rel="alternate"/>""")
                .contains("""<xhtml:link href="https://event-junkie.de/de/venues/lido" hreflang="de" rel="alternate"/>""")
                .contains("""<xhtml:link href="https://event-junkie.de/en/venues/lido" hreflang="x-default" rel="alternate"/>""")
            assertThat(xml).startsWith("""<?xml version="1.0" encoding="UTF-8"?>""")
        }

    @Test
    fun `lists every venue, with or without events`(): Unit =
        runBlocking {
            insertVenue("Lido", "lido")
            insertVenue("Astra", "astra")

            assertThat(locs(sitemap("venues"))).containsExactly(
                "/en/venues/astra",
                "/de/venues/astra",
                "/en/venues/lido",
                "/de/venues/lido"
            )
        }

    @Test
    fun `lists an artist or promoter only while they have an event that is not over`(): Unit =
        runBlocking {
            val venueId = insertVenue("Lido", "lido")
            val upcoming = insertEvent(venueId, "Soon", "soon", today.plusDays(5))
            val past = insertEvent(venueId, "Gone", "gone", today.minusDays(5))
            linkArtist(upcoming, insertArtist("Active Act", "active-act"))
            linkArtist(past, insertArtist("Former Act", "former-act"))
            insertArtist("Idle Act", "idle-act")
            linkPromoter(upcoming, insertPromoter("Active Promoter", "active-promoter"))
            linkPromoter(past, insertPromoter("Former Promoter", "former-promoter"))

            assertThat(locs(sitemap("artists"))).containsExactly("/en/artists/active-act", "/de/artists/active-act")
            assertThat(locs(sitemap("promoters")))
                .containsExactly("/en/promoters/active-promoter", "/de/promoters/active-promoter")
        }

    @Test
    fun `an empty kind is a valid empty sitemap, not an error`(): Unit =
        runBlocking {
            val xml = sitemap("artists")

            assertThat(locs(xml)).isEmpty()
            assertThat(xml).contains("<urlset").contains("</urlset>")
        }

    @Test
    fun `answers 404 for a kind that has no detail pages`() {
        webTestClient
            .get()
            .uri("/sitemaps/genres.xml")
            .exchange()
            .expectStatus()
            .isNotFound
    }
}
