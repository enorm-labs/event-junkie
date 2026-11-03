package de.norm.events.common

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Unit tests for [deshoutWord] around an apostrophe (#2914), and for [deshout] over a whole name. */
class NameCasingTest {
    @Test
    fun `keeps the capital after an Irish O prefix, with either apostrophe`() {
        assertSoftly {
            "O'NEILL".deshoutWord() shouldBe "O'Neill"
            "O’BRIEN".deshoutWord() shouldBe "O’Brien"
            "O'REILLY-SMITH".deshoutWord() shouldBe "O'Reilly-Smith"
            "(O'NEILL)".deshoutWord() shouldBe "(O'Neill)"
        }
    }

    @Test
    fun `lowers the letter after any other apostrophe`() {
        assertSoftly {
            "MURPHY'S".deshoutWord() shouldBe "Murphy's"
            "D'NICE".deshoutWord() shouldBe "D'nice"
            "FLAMIN'".deshoutWord() shouldBe "Flamin'"
            "'TIL".deshoutWord() shouldBe "'Til"
            "ROCK'N'ROLL".deshoutWord() shouldBe "Rock'n'roll"
        }
    }

    @Test
    fun `de-shouts each part a star joins, and keeps a censored word whole`() {
        assertSoftly {
            "BIGA*RANX".deshoutWord() shouldBe "Biga*Ranx"
            "BUTCH*WALK".deshoutWord() shouldBe "Butch*Walk"
            "F*CK".deshoutWord() shouldBe "F*ck"
            "D*B*S".deshoutWord() shouldBe "D*b*s"
            "FLINTA*".deshoutWord() shouldBe "Flinta*"
            "K-POP*STARS".deshoutWord() shouldBe "K-Pop*Stars"
        }
    }

    @Test
    fun `calls a one-word name of up to five capitals without a vowel an initialism`() {
        assertSoftly {
            "CCTV".isVowellessInitialism() shouldBe true
            "TZK".isVowellessInitialism() shouldBe true
            "BTMK".isVowellessInitialism() shouldBe true
            "KMFDM".isVowellessInitialism() shouldBe true
            // A vowel, a sixth letter, a lowercase letter or a digit makes it something else.
            "MUNA".isVowellessInitialism() shouldBe false
            "LYNX".isVowellessInitialism() shouldBe false
            "TÝR".isVowellessInitialism() shouldBe false
            "GŒRNS".isVowellessInitialism() shouldBe false
            "SCHMRZ".isVowellessInitialism() shouldBe false
            "Cctv".isVowellessInitialism() shouldBe false
            "SBP4".isVowellessInitialism() shouldBe false
        }
    }

    @Test
    fun `leaves a mixed-case token as it is`() {
        "O'neill".deshoutWord() shouldBe "O'neill"
    }

    @Test
    fun `de-shouts a name word by word after a trim`() {
        assertSoftly {
            deshout("  TC THE 3RD ") shouldBe "TC The 3RD"
            deshout("DJ KOZE") shouldBe "DJ Koze"
            deshout("Max MUSTERMANN") shouldBe "Max Mustermann"
            deshout("A  B") shouldBe "A  B"
        }
    }

    @Test
    fun `calls a token mixed case only when it has a capital and a lowercase letter`() {
        assertSoftly {
            "Soundsystem".isMixedCase() shouldBe true
            "GreyZone".isMixedCase() shouldBe true
            "Вуиндлия".isMixedCase() shouldBe true
            "HB&P".isMixedCase() shouldBe false
            "b2b".isMixedCase() shouldBe false
            "&".isMixedCase() shouldBe false
            "GROßE".isMixedCase() shouldBe false
        }
    }

    @Test
    fun `calls a token capitals when it has letters and no lowercase one but ß`() {
        assertSoftly {
            "HB&P".isCapitals() shouldBe true
            "MC5".isCapitals() shouldBe true
            "GROßE".isCapitals() shouldBe true
            "Soundsystem".isCapitals() shouldBe false
            "b2b".isCapitals() shouldBe false
            "&".isCapitals() shouldBe false
        }
    }
}
