package de.norm.events.common

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.text.Normalizer

class MarksTest {
    @Test
    fun `an accented letter loses its accent`() {
        "Maël Motörhead Ćevapi".withoutMarks() shouldBe "Mael Motorhead Cevapi"
    }

    @Test
    fun `only NFKD folds a ligature`() {
        "ﬁre".withoutMarks() shouldBe "ﬁre"
        "ﬁre".withoutMarks(Normalizer.Form.NFKD) shouldBe "fire"
    }

    @Test
    fun `a spacing mark goes too, not only a non-spacing one`() {
        "का".withoutMarks() shouldBe "क"
    }
}
