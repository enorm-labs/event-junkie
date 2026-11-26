package de.norm.events.scraper

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StatusWordTest {
    @Test
    fun `a name that is only the status vocabulary is a status word`() {
        listOf(
            "WIRD",
            "Wird verschoben",
            "Wird verschoben:",
            "VERSCHOBEN!",
            "verlegt",
            "Abgesagt",
            "ABSAGE",
            "Cancelled",
            "postponed",
            "Ausverkauft",
            "SOLD OUT",
            "sold-out",
            "Fällt leider aus",
            "*Abgesagt*"
        ).forEach { name -> isStatusWord(name) shouldBe true }
    }

    @Test
    fun `a name with any other word is an act`() {
        listOf(
            "Wire",
            "Wirdz",
            "Wird verschoben: Deep Purple",
            "Abgesagte Lesung",
            "Sold Out Tour",
            "Hochverlegt",
            "Cancel Culture",
            "Moved",
            "!!!",
            ""
        ).forEach { name -> isStatusWord(name) shouldBe false }
    }
}
