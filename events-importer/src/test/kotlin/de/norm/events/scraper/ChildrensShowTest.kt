package de.norm.events.scraper

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ChildrensShowTest {
    @Test
    fun `names the children's shows the venues bill, from the live programme of 2026-10-07`() {
        listOf(
            "KINDERPHILHARMONIE - Klassik für Kleine (0-18 Monate)" to "Großes Bohai im Herbst!",
            "Kinder-Halloween im PETER EDEL" to null,
            "Klavierfest Berlin-Weißensee 2027 - Familienkonzert" to null,
            "Linus Faber – Eine magische Elfenmission" to "Die große Familien-Zaubershow für Kinder ab 4 Jahre",
            "ROLLER KIDZ" to "Rollschuhdisko für Kinder"
        ).map { (title, subtitle) -> billsChildrensShow(title, subtitle) }.distinct() shouldBe listOf(true)
    }

    @Test
    fun `keeps a band named after children and a show for teenagers`() {
        listOf(
            "Muttis Kinder" to null,
            "Kinderzimmer Productions" to null,
            "KINDER DER NACHT & DEXIT MANNHEIM – HALLOWEEN RAVE" to null,
            "Lichterkinder" to null,
            "Deine Freunde" to null,
            "Coming-of-Age-Theater" to "ab 13 Jahre"
        ).map { (title, subtitle) -> billsChildrensShow(title, subtitle) }.distinct() shouldBe listOf(false)
    }
}
