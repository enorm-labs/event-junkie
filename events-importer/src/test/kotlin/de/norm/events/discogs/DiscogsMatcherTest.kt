package de.norm.events.discogs

import de.norm.events.artist.DiscogsMatch
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import tools.jackson.module.kotlin.readValue

/** The rule the spike on #1549 measured, on the candidates Discogs returned and on the shapes it stands for. */
class DiscogsMatcherTest {
    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()

    private fun fixture(name: String): List<DiscogsCandidate> =
        mapper.readValue<DiscogsSearchResponse>(javaClass.getResource("/discogs/$name")!!.readText()).results

    private fun artist(
        id: Long,
        title: String
    ) = DiscogsCandidate(id = id, type = "artist", title = title, uri = "/artist/$id-${title.replace(' ', '-')}")

    @Test
    fun `one title equal to the name is EXACT, with its id and page`() {
        DiscogsMatcher.decide("Okkyung Lee", fixture("search-okkyung-lee.json")) shouldBe
            DiscogsVerdict(DiscogsMatch.EXACT, 130715, "https://www.discogs.com/artist/130715-Okkyung-Lee")
    }

    @Test
    fun `the search's fuzzy neighbours decide nothing`() {
        // Hanzel returns Hanzel Und Gretyl, Mike Hanzel and 20 more; only the one plain Hanzel counts.
        DiscogsMatcher.decide("Hanzel", fixture("search-hanzel.json")).match shouldBe DiscogsMatch.EXACT
    }

    @Test
    fun `no equal title is NONE`() {
        DiscogsMatcher.decide("Sonic Morgue", fixture("search-sonic-morgue.json")) shouldBe DiscogsVerdict.NONE
        DiscogsMatcher.decide("Boris Brejcha", listOf(artist(1, "Luke Mandala"))) shouldBe DiscogsVerdict.NONE
    }

    @Test
    fun `homonym suffixes are stripped, so two homonyms are AMBIGUOUS`() {
        DiscogsMatcher.decide("Nails", listOf(artist(1, "Nails"), artist(2, "Nails (2)"))) shouldBe DiscogsVerdict.AMBIGUOUS
    }

    @Test
    fun `a suffixed homonym alone is AMBIGUOUS, because the suffix proves there are others`() {
        // Found on the first local run: `Kevin` matched `Kevin (27)` and `Victor` matched `Victor (10)`.
        DiscogsMatcher.decide("Kevin", listOf(artist(1007151, "Kevin (27)"))) shouldBe DiscogsVerdict.AMBIGUOUS
        DiscogsMatcher.decide("Victor", listOf(artist(642486, "Victor (10)"), artist(3, "Victor Rice"))) shouldBe DiscogsVerdict.AMBIGUOUS
    }

    @Test
    fun `names fold as MusicBrainz folds them`() {
        DiscogsMatcher.decide("gebruder teichmann", listOf(artist(3, "Gebrüder Teichmann"))).match shouldBe DiscogsMatch.EXACT
        DiscogsMatcher.decide("Simon and Garfunkel", listOf(artist(4, "Simon & Garfunkel"))).match shouldBe DiscogsMatch.EXACT
    }

    @Test
    fun `a result that is not an artist never counts`() {
        val label = DiscogsCandidate(id = 5, type = "label", title = "Tresor")

        DiscogsMatcher.decide("Tresor", listOf(label)) shouldBe DiscogsVerdict.NONE
    }

    @Test
    fun `a page path that is not an artist page falls back to the id`() {
        DiscogsMatcher.decide("Barker", listOf(DiscogsCandidate(id = 9, type = "artist", title = "Barker", uri = null))).discogsUrl shouldBe
            "https://www.discogs.com/artist/9"
    }
}
