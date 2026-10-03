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
    fun `a term with no letter or digit binds the escaped term, wrapped for a literal substring match`() {
        TextSearch.params("%_", bySimilarity = false) shouldBe mapOf("q" to "%_", "qPattern" to "%\\%\\_%")
    }

    @Test
    fun `a term that folds binds no pattern, because the folded match builds its own`() {
        TextSearch.params("50%", bySimilarity = false) shouldBe mapOf("q" to "50%")
    }

    @Test
    fun `the folded match carries no CASE, so a generic plan can still use the trigram index`() {
        TextSearch.matches("e.title", "techno") shouldBe
            "(replace(e.title_search, ' ', '') LIKE '%' || NULLIF(replace(events.search_norm(:q), ' ', ''), '') || '%')"
        TextSearch.matches("e.title", "!!!") shouldBe "(e.title ILIKE :qPattern AND events.search_norm(:q) = '')"
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
