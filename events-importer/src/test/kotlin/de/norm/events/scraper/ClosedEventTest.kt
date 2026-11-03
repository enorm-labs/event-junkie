package de.norm.events.scraper

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ClosedEventTest {
    @Test
    fun `names the nights the venues close to the public`() {
        listOf(
            "Eintritt nur für geladene Gäste",
            "Nur fuer eingeladene Gaeste",
            "Gönnerparty (geschlossene Gesellschaft)",
            "Geschlossene Gesellschaft",
            "Privatveranstaltung",
            "Private Veranstaltung ab 19 Uhr"
        ).map(::isClosedToPublic).distinct() shouldBe listOf(true)
    }

    @Test
    fun `keeps a public night that only mentions guests or a private label`() {
        listOf(
            null,
            "",
            "Jonathan Blues Band & Gäste",
            "Mit geladenem Live-Sound und Gästen",
            "VVK: 39,90 inkl. Gebühren",
            "Privatclub Records Labelnight"
        ).map(::isClosedToPublic).distinct() shouldBe listOf(false)
    }
}
