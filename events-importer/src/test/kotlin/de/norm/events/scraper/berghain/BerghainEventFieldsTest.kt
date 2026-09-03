package de.norm.events.scraper.berghain

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [floorsToGenre] — the curated floor → genre default used to give
 * Berghain events a filterable style tag, since the site exposes no genre field.
 */
class BerghainEventFieldsTest {
    @Test
    fun `maps each known floor to its signature genre`() {
        floorsToGenre(listOf("Berghain")) shouldBe "Techno"
        floorsToGenre(listOf("Panorama Bar")) shouldBe "House"
        floorsToGenre(listOf("Säule")) shouldBe "Experimental"
    }

    @Test
    fun `the Kantine concert hall yields no genre despite containing 'Berghain'`() {
        // "Kantine am Berghain" must not be mis-mapped to Techno by the "Berghain" substring.
        floorsToGenre(listOf("Kantine am Berghain")).shouldBeNull()
    }

    @Test
    fun `joins distinct genres for a multi-floor night in listing order`() {
        floorsToGenre(listOf("Berghain", "Panorama Bar")) shouldBe "Techno, House"
        floorsToGenre(listOf("Panorama Bar", "Berghain")) shouldBe "House, Techno"
    }

    @Test
    fun `collapses repeated floors and ignores unmapped ones`() {
        floorsToGenre(listOf("Berghain", "Berghain")) shouldBe "Techno"
        // The Halle event hall and a stray non-floor heading ("Tickets") map to nothing.
        floorsToGenre(listOf("Halle", "Berghain", "Tickets")) shouldBe "Techno"
    }

    @Test
    fun `returns null when no floor maps to a genre`() {
        floorsToGenre(emptyList()).shouldBeNull()
        floorsToGenre(listOf("Halle")).shouldBeNull()
    }
}
