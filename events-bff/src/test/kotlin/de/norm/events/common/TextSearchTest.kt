package de.norm.events.common

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TextSearchTest {
    @Test
    fun `the three metacharacters are escaped and nothing else is touched`() {
        "100% live_set".escapeLike() shouldBe "100\\% live\\_set"
        "Møbius Trio".escapeLike() shouldBe "Møbius Trio"
    }

    @Test
    fun `a backslash is escaped first, so an escaped percent is not escaped twice`() {
        "\\%".escapeLike() shouldBe "\\\\\\%"
    }

    @Test
    fun `a term is trimmed and its inner whitespace collapsed`() {
        TextSearch.term("  kit \t kat  ") shouldBe "kit kat"
    }

    @Test
    fun `a blank or missing term is no search`() {
        TextSearch.term(" \n ").shouldBeNull()
        TextSearch.term(null).shouldBeNull()
    }

    @Test
    fun `the pattern is the escaped term, wrapped for a substring match`() {
        TextSearch.params("50%", bySimilarity = false) shouldBe mapOf("q" to "50%", "qPattern" to "%50\\%%")
    }

    @Test
    fun `the similarity pass binds no pattern`() {
        TextSearch.params("50%", bySimilarity = true) shouldBe mapOf("q" to "50%")
    }

    @Test
    fun `a similarity pass needs four letters or digits`() {
        TextSearch.allowsSimilar("lio").shouldBeFalse()
        TextSearch.allowsSimilar("l-i-d-o").shouldBeTrue()
        TextSearch.allowsSimilar(null).shouldBeFalse()
    }
}
