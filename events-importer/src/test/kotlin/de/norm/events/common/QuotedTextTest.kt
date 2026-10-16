package de.norm.events.common

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class QuotedTextTest {
    @Test
    fun `a quote that leaves out the emoji between two lines still occurs`() {
        QuotedText.occursIn("SATURDAY, 31.10.2026 CAFÉ CRALLE", CRALLE) shouldBe true
        QuotedText.occursIn("Saturday, 31.10.2026 / Café Cralle", CRALLE) shouldBe false
    }

    @Test
    fun `joined emoji, skin tones and variation selectors are dropped too`() {
        QuotedText.normalise("👩🏽‍🎤 Live 🕯️ tonight") shouldBe "live tonight"
    }

    @Test
    fun `case, accents, line breaks and quote shapes do not matter, and a changed word does`() {
        QuotedText.occursIn("SÁBADO, 31.10.2026", "📅 sabado,\n31.10.2026") shouldBe true
        QuotedText.occursIn("it’s „on“", "It's \"on\"") shouldBe true
        QuotedText.occursIn("SATURDAY, 30.10.2026", CRALLE) shouldBe false
    }

    @Test
    fun `a quote of two lines with the lines between them left out matches line by line, and a changed line does not`() {
        val quote = "SATURDAY, 31.10.2026\n🕖 FROM 7 PM"

        QuotedText.occursIn(quote, CRALLE) shouldBe false
        QuotedText.eachLineOccursIn(quote, CRALLE) shouldBe true
        QuotedText.eachLineOccursIn("SATURDAY, 31.10.2026\nFROM 8 PM", CRALLE) shouldBe false
        QuotedText.eachLineOccursIn("\n  \n", CRALLE) shouldBe false
    }

    private companion object {
        const val CRALLE = "🌼💀 DAY OF THE DEAD 💀🌼\n📅 SATURDAY, 31.10.2026\n📍 CAFÉ CRALLE\nHochstädter Str. 10A, 13347 Berlin\n🕖 FROM 7 PM"
    }
}
