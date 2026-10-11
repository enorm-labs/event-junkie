package de.norm.events.genretag

import de.norm.events.slug.SlugGenerator
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Tests for [genreFamily] — the tag-to-family map behind the two-level genre filter (#363).
 */
class GenreFamiliesTest {
    @Test
    fun `every canonical name the synonym map produces has a family`() {
        // A synonym target without a family would be a tag the normalizer deliberately creates
        // and the filter then hides — the map is the place that must know it.
        GENRE_SYNONYMS.values
            .distinct()
            .filter { genreFamily(SlugGenerator.slugify(it)) == null }
            .shouldBeEmpty()
    }

    @Test
    fun `a tag resolves to its family by slug`() {
        genreFamily("techno") shouldBe GenreFamily.ELECTRONIC
        genreFamily("hip-hop") shouldBe GenreFamily.HIP_HOP
        genreFamily("singer-songwriter") shouldBe GenreFamily.FOLK
        genreFamily("top40") shouldBe GenreFamily.CHARTS
        genreFamily("new-age") shouldBe GenreFamily.ELECTRONIC
    }

    @Test
    fun `the styles staging once held without a family now have one`() {
        genreFamily("tech-house") shouldBe GenreFamily.ELECTRONIC
        genreFamily("trip-hop") shouldBe GenreFamily.ELECTRONIC
        genreFamily("oi-punk") shouldBe GenreFamily.PUNK
        genreFamily("post-metal") shouldBe GenreFamily.METAL
        genreFamily("prog") shouldBe GenreFamily.ROCK
        genreFamily("country-trash") shouldBe GenreFamily.FOLK
        genreFamily("swana") shouldBe GenreFamily.LATIN_WORLD
    }

    @Test
    fun `a slug the map does not name has no family`() {
        // The formats that reach genre_tag by the looksLikeGenre fall-through stay out of the filter.
        genreFamily("ping-pong").shouldBeNull()
        genreFamily("tattoo").shouldBeNull()
    }
}
