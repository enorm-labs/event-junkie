package de.norm.events.scraper

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TradeFairTest {
    @Test
    fun `names the fairs and conferences the venues bill, from the live programme of 2026-10-07`() {
        listOf(
            "STICKS & STONES - LGBTIQ+ Job - und Karrieremesse" to null,
            "Berlin International Tea Conference 2026 – VOICES OF TEA" to null,
            "VOICES OF TEA" to "Internationales B2B-Forum für Herkunft, Qualität und professionelle Vernetzung in der Teebranche",
            "Keine Panik! Resilienz in der Polykrise – FIfF-Konferenz 2026" to null,
            "Zia Kongress" to null
        ).map { (title, subtitle) -> billsTradeFairOrConference(title, subtitle) }.distinct() shouldBe listOf(true)
    }

    @Test
    fun `keeps a band named Messe and a title that merely contains the letters`() {
        listOf(
            "ZOMBIEZ" to "'Messe'",
            "Messer" to null,
            "Konferenzraum Session" to null
        ).map { (title, subtitle) -> billsTradeFairOrConference(title, subtitle) }.distinct() shouldBe listOf(false)
    }
}
