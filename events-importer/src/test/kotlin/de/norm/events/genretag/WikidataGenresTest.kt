package de.norm.events.genretag

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class WikidataGenresTest {
    @Test
    fun `every tag the map gives is one the vocabulary already gives (ADR-060)`() {
        WIKIDATA_GENRES.values
            .toSet()
            .filter { normalizeGenre(it) != listOf(it) }
            .shouldBeEmpty()
    }

    @Test
    fun `every key is a Wikidata item id`() {
        (WIKIDATA_GENRES.keys + WIKIDATA_NO_TAG).filterNot { it.matches(Regex("""Q\d+""")) }.shouldBeEmpty()
    }

    @Test
    fun `an item gives a tag or no tag, never both (#5252)`() {
        WIKIDATA_GENRES.keys.intersect(WIKIDATA_NO_TAG).shouldBeEmpty()
    }

    @Test
    fun `punk rock maps by its id, since it has no English label`() {
        WIKIDATA_GENRES["Q3071"] shouldBe "Punk"
    }
}
