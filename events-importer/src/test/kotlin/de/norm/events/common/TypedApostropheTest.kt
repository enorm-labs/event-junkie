package de.norm.events.common

import de.norm.events.slug.SlugGenerator
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Unit tests for [foldTypedApostrophes], on the names production held on 2026-09-30 (#2174). */
class TypedApostropheTest {
    private val production =
        mapOf(
            "Dingo`s Dream" to "Dingo's Dream",
            "Kelli O`Neil" to "Kelli O'Neil",
            "pris´n break" to "pris'n break",
            "Romp`n`Stomp" to "Romp'n'Stomp",
            "Punk & Rock`n`Roll Flohmarkt ab 14 Uhr" to "Punk & Rock'n'Roll Flohmarkt ab 14 Uhr",
            "Barney Millah´s legendary Escobar" to "Barney Millah's legendary Escobar",
            "PANSY´S HALLOWEEN" to "PANSY'S HALLOWEEN",
            "Luv`n Musiq" to "Luv'n Musiq"
        )

    @Test
    fun `folds a backtick or an acute accent between two letters`() {
        assertSoftly {
            production.forEach { (typed, folded) -> typed.foldTypedApostrophes() shouldBe folded }
        }
    }

    @Test
    fun `leaves the mark alone where no letter sits on both sides`() {
        assertSoftly {
            "´s Wirtshaus".foldTypedApostrophes() shouldBe "´s Wirtshaus"
            "the `code` span".foldTypedApostrophes() shouldBe "the `code` span"
            "80´".foldTypedApostrophes() shouldBe "80´"
            "Arm's Length".foldTypedApostrophes() shouldBe "Arm's Length"
            "Yes, I’m Very Tired Now".foldTypedApostrophes() shouldBe "Yes, I’m Very Tired Now"
        }
    }

    @Test
    fun `keeps every slug, so the import reuses the row the migration renamed`() {
        assertSoftly {
            production.forEach { (typed, folded) -> SlugGenerator.slugify(folded) shouldBe SlugGenerator.slugify(typed) }
        }
    }
}
