package de.norm.events.common

import de.norm.events.BaseControllerTest
import de.norm.events.ClockConfiguration
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** The `q` rule of [TextSearch] against the real `search_norm` (V090), through each list endpoint. */
class TextSearchQueryTest : BaseControllerTest() {
    @Test
    fun `a name list folds accents, umlaut spellings, spaces and case, and forgives a typo`(): Unit =
        runBlocking {
            insertVenue("ÆDEN", "aeden")
            insertVenue("Säälchen", "saalchen")
            insertVenue("Berghain", "berghain")
            insertVenue("KitKatClub", "kitkatclub")
            insertVenue("Lido", "lido")
            insertVenue("Кино Club", "kino-club")
            insertVenue("Heimathafen Neukölln", "heimathafen-neukolln")
            insertVenue("Queen Bar", "queen-bar")
            insertVenue("Schokoladen", "schokoladen")
            insertVenue("Gärten der Welt", "garten-der-welt")

            mapOf(
                "aeden" to listOf("aeden"),
                "ÆDEN" to listOf("aeden"),
                "saalchen" to listOf("saalchen"),
                "saelchen" to listOf("saalchen"),
                "berghian" to listOf("berghain"),
                "kit kat" to listOf("kitkatclub"),
                "  LIDO  " to listOf("lido"),
                "кино" to listOf("kino-club"),
                "neukoelln" to listOf("heimathafen-neukolln"),
                "neukolln" to listOf("heimathafen-neukolln"),
                "gaerten" to listOf("garten-der-welt"),
                "garten" to listOf("garten-der-welt"),
                "queen" to listOf("queen-bar"),
                "xyzzy" to emptyList()
            ).forEach { (q, expected) ->
                withClue("q=$q") { slugs("/venues", q) shouldContainExactlyInAnyOrder expected }
            }
        }

    @Test
    fun `a short term matches by substring only, never by similarity`(): Unit =
        runBlocking {
            insertVenue("Lido", "lido")
            insertVenue("Loge", "loge")

            slugs("/venues", "lid") shouldContainExactly listOf("lido")
            // `lio` scores 0.5 against `lido`, which would pass the threshold at four letters.
            slugs("/venues", "lio") shouldContainExactly emptyList()
        }

    @Test
    fun `a strict match keeps the similar names out`(): Unit =
        runBlocking {
            insertVenue("Berghain", "berghain")
            insertVenue("Berghian Bar", "berghian-bar")

            slugs("/venues", "berghain") shouldContainExactly listOf("berghain")
            slugs("/venues", "berghian") shouldContainExactly listOf("berghian-bar")
        }

    @Test
    fun `a term that folds to nothing matches literally`(): Unit =
        runBlocking {
            insertArtist("!!!", "chk-chk-chk")
            insertArtist("Chk", "chk")

            slugs("/artists", "!!!") shouldContainExactly listOf("chk-chk-chk")
            slugs("/artists", "%") shouldContainExactly emptyList()
        }

    @Test
    fun `a name search sorted by name puts the closest match first`(): Unit =
        runBlocking {
            insertPromoter("Atom Berlin", "atom-berlin")
            insertPromoter("Berlin Atonal", "berlin-atonal")
            insertPromoter("Berlin", "berlin")

            slugs("/promoters", "berlin") shouldContainExactly listOf("berlin", "berlin-atonal", "atom-berlin")
        }

    @Test
    fun `the artist list ranks the same way`(): Unit =
        runBlocking {
            insertArtist("Mobius Trio", "mobius-trio")
            insertArtist("Møbius", "mobius")

            slugs("/artists", "mobius") shouldContainExactly listOf("mobius", "mobius-trio")
        }

    @Test
    fun `the event search reads the title, the subtitle, the venue and the lineup`(): Unit =
        runBlocking {
            val aeden = insertVenue("ÆDEN", "aeden")
            val astra = insertVenue("Astra", "astra")
            val day = LocalDate.now(ClockConfiguration.BERLIN).plusDays(1)
            insertEvent(aeden, "Open Air", "open-air", day)
            insertEvent(astra, "Straßenfest", "strassenfest", day)
            insertEvent(astra, "Tour", "tour", day, subtitle = "with Kit Kat Band")
            val gig = insertEvent(astra, "Gig", "gig", day)
            linkArtist(gig, insertArtist("Ørlög", "orlog"))
            insertEvent(astra, "Other", "other", day)

            mapOf(
                "aeden" to listOf("open-air"),
                "strasse" to listOf("strassenfest"),
                "kitkat" to listOf("tour"),
                "orlog" to listOf("gig"),
                "astra" to listOf("strassenfest", "tour", "gig", "other"),
                "aedenn" to listOf("open-air")
            ).forEach { (q, expected) ->
                withClue("q=$q") { slugs("/events", q) shouldContainExactlyInAnyOrder expected }
            }
        }

    private fun slugs(
        path: String,
        q: String
    ): List<Any?> {
        val body =
            webTestClient
                .get()
                .uri { it.path(path).queryParam("q", "{q}").build(q) }
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(Map::class.java)
                .returnResult()
                .responseBody

        @Suppress("UNCHECKED_CAST")
        val content = body?.get("content") as List<Map<String, Any?>>
        return content.map { it["slug"] }
    }
}
