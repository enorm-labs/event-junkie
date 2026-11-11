package de.norm.events.scraper

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal

// hasFreeEntryPhrase and detectFree from EventFieldMapping — one test per behaviour.
class FreeDetectionTest {
    @Test
    fun `hasFreeEntryPhrase reads every spelling the venues print`() {
        listOf(
            "Eintritt frei!",
            "Eintritt: Frei",
            "Eintritt ist frei!",
            "Freier Eintritt für Alle",
            "bei freiem Eintritt",
            "kostenloser Eintritt",
            "Kostenfreie Tickets: a-masked@example.de",
            "kostenfreier Eintritt",
            "FREE ENTRY",
            "Eintritt frei / Admission free!",
            "free admission",
            "There is no entrance fee.",
            "No entry fee"
        ).forEach { hasFreeEntryPhrase(it) shouldBe true }
    }

    @Test
    fun `hasFreeEntryPhrase rejects a time-limited offer and a donation`() {
        // Kater's and Eschschloraque's own rule caught "free entry till 20:00" but not words between.
        hasFreeEntryPhrase("free entry for ladies until 0 Uhr") shouldBe false
        hasFreeEntryPhrase("Freier Eintritt für Ladies bis 0 Uhr") shouldBe false
        hasFreeEntryPhrase("free entry till 20:00") shouldBe false
        hasFreeEntryPhrase("Eintritt freiwillig, Spende erbeten") shouldBe false
        // Gärten der Welt: the event costs nothing, the park entry it needs does.
        hasFreeEntryPhrase("die Veranstaltung ist kostenfrei (zzgl. Parkeintritt)") shouldBe false
        hasFreeEntryPhrase(null) shouldBe false
    }

    @Test
    fun `hasFreeEntryPhrase rejects pay what you want and entry against a donation`() {
        listOf(
            "Free Entry + Donation based",
            "Free entry with a 2 € donation",
            "Eintritt frei, pay what you want",
            "Eintritt frei, but pay what you can",
            "Eintritt frei auf Spendenbasis",
            "Eintritt frei, Hutkasse"
        ).forEach { hasFreeEntryPhrase(it) shouldBe false }
    }

    @Test
    fun `hasFreeEntryPhrase keeps a free night that only mentions a donation or a Soli`() {
        listOf(
            "Eintritt frei – Quizteilnahme gegen Spende",
            "Soli Mosh Berlin FREE ENTRY",
            "Eintritt frei. Klassik mit Soli und Duos",
            "Eintritt frei"
        ).forEach { hasFreeEntryPhrase(it) shouldBe true }
    }

    @Test
    fun `detectFree is true for an explicit zero presale or box-office price`() {
        detectFree(pricePresale = BigDecimal.ZERO) shouldBe true
        detectFree(pricePresale = BigDecimal("0.00")) shouldBe true
        detectFree(priceBoxOffice = BigDecimal("0.00")) shouldBe true
    }

    @Test
    fun `detectFree is true for free-entry phrases in the price note or title`() {
        detectFree(priceNote = "Eintritt frei") shouldBe true
        detectFree(priceNote = "Freier Eintritt, Spende erwünscht") shouldBe true
        detectFree(priceNote = "Free entry all night") shouldBe true
        detectFree(title = "Sommerfest — Free Admission") shouldBe true
        detectFree(priceNote = "Kostenfreie Tickets") shouldBe true
        detectFree(priceNote = "die Veranstaltung ist kostenfrei (zzgl. Parkeintritt)") shouldBe false
    }

    @Test
    fun `detectFree matches single-word markers only in the price note`() {
        detectFree(priceNote = "Gratis") shouldBe true
        detectFree(priceNote = "kostenlos") shouldBe true
        detectFree(priceNote = "umsonst") shouldBe true
        // A bare token in the title (not the pricing-scoped note) must not trigger.
        detectFree(title = "Gratis Vibes Live") shouldBe false
    }

    @Test
    fun `detectFree does not false-positive on names or word fragments`() {
        detectFree(title = "Freedom Festival") shouldBe false
        detectFree(title = "Freikörperkultur") shouldBe false
        detectFree(priceNote = "freestyle session") shouldBe false
        detectFree(pricePresale = BigDecimal("12.00"), priceBoxOffice = BigDecimal("15.00")) shouldBe false
    }

    @Test
    fun `detectFree does not read a pay-what-you-want note as free`() {
        detectFree(priceNote = "Free Entry + Donation based") shouldBe false
        detectFree(priceNote = "free, pay what you want") shouldBe false
        detectFree(priceNote = "Gratis, Hutkasse") shouldBe false
        detectFree(priceNote = "Eintritt frei – Quizteilnahme gegen Spende") shouldBe true
        detectFree(priceNote = "Eintritt frei") shouldBe true
    }

    @Test
    fun `detectFree ignores a free-entry marker that ends at a time`() {
        detectFree(priceNote = "free entry until midnight*") shouldBe false
        detectFree(priceNote = "Women & FLINTA free until 1 AM") shouldBe false
        detectFree(priceNote = "Freier Eintritt für Ladies bis 0 Uhr!") shouldBe false
        detectFree(priceNote = "Eintritt frei bis 23 Uhr") shouldBe false
        detectFree(title = "[FREE ENTRY TILL 00:30] SAVORY") shouldBe false
        // A marker beside the limited one still counts.
        detectFree(priceNote = "Eintritt frei. Free entry until midnight") shouldBe true
    }

    // Der Weiße Hase stored a free night for an offer to birthday guests only (#4884).
    @Test
    fun `detectFree ignores free entry for one group of guests`() {
        listOf(
            "Geburtstagskinder vom 14.11. bis 29.11. erhalten freien Eintritt!",
            "Studierende freier Eintritt",
            "free entry for members",
            "Eintritt frei für Mitglieder",
            "Members free",
            "Birthday kids get in free"
        ).forEach {
            hasFreeEntryPhrase(it) shouldBe false
            detectFree(priceNote = it) shouldBe false
        }
        detectFree(priceNote = "Eintritt frei") shouldBe true
        detectFree(priceNote = "freier Eintritt") shouldBe true
        // An offer that applies to everyone still counts beside a group one.
        detectFree(priceNote = "Eintritt frei! Birthday kids get a free shot") shouldBe true
        detectFree(priceNote = "Studierende 5 €, sonst Eintritt frei") shouldBe true
        detectFree(title = "Birthday Bash – free entry") shouldBe true
    }

    // The operator's answer on #4897: seniors, children and FLINTA* entering free are a group offer too.
    @Test
    fun `detectFree ignores free entry for seniors, children or FLINTA guests`() {
        listOf(
            "Senior*innen freier Eintritt",
            "Seniorinnen und Senioren frei",
            "Rentner frei",
            "Eintritt frei für Rentnerinnen",
            "free entry for seniors",
            "Seniors free",
            "Kinder frei",
            "Kinder bis 12 Jahre frei",
            "Eintritt frei für Kinder",
            "free for kids",
            "Kids get in free",
            "children under 6 free",
            "FLINTA* free",
            "FLINTA freier Eintritt",
            "FLINTAQ* Eintritt frei",
            "free entry for FLINTA*"
        ).forEach {
            hasFreeEntryPhrase(it) shouldBe false
            detectFree(priceNote = it) shouldBe false
        }
        detectFree(title = "Kinderdisco – Eintritt frei") shouldBe true
        detectFree(priceNote = "Eintritt frei. Kinder bis 6 Jahre frei") shouldBe true
    }

    // Ausland's FLINTA* meetup: the night is for FLINTA*, and the offer names nobody.
    @Test
    fun `detectFree keeps a FLINTA night that is free for everyone`() {
        detectFree(title = "music making meetup for FLINTA", priceNote = "Free entry, registration required.") shouldBe true
        detectFree(title = "FLINTA* Rave – Eintritt frei") shouldBe true
        detectFree(title = "FLINTA* Party", priceNote = "Eintritt frei") shouldBe true
        hasFreeEntryPhrase("FLINTA* only. Eintritt frei") shouldBe true
    }

    @Test
    fun `detectFree reads a free group beside a positive price as a concession`() {
        val panke = "Admission is free for transgender women. presale 18€ reduced presale 13€ at the door 23€ supporter 30€"
        detectFree(BigDecimal("18.00"), BigDecimal("23.00"), panke) shouldBe false
        detectFree(priceBoxOffice = BigDecimal("10.00"), priceNote = "Kinder frei") shouldBe false
        detectFree(pricePresale = BigDecimal("8.00"), priceNote = "Eintritt frei für Mitglieder") shouldBe false
        // Without a parsed price the same note still marks the night free.
        detectFree(priceNote = panke) shouldBe true
        // A €0 price or a title phrase stays a signal beside a positive price.
        detectFree(BigDecimal("0.00"), BigDecimal("10.00")) shouldBe true
        detectFree(priceBoxOffice = BigDecimal("5.00"), title = "Hoffest — Eintritt frei") shouldBe true
    }

    @Test
    fun `detectFree is false when nothing is provided`() {
        detectFree() shouldBe false
        detectFree(priceNote = null, title = null) shouldBe false
    }
}
