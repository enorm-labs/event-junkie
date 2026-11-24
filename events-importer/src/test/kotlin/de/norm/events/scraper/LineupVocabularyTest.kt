package de.norm.events.scraper

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LineupVocabularyTest {
    @Test
    fun `reads every spelling of the line-up word`() {
        val label = Regex("""$LINE_UP_WORD:""", RegexOption.IGNORE_CASE)
        listOf("Lineup:", "Line-up:", "LINE UP:", "Line - up:").all(label::matches) shouldBe true
        label.matches("Line dance up:") shouldBe false
    }

    @Test
    fun `splits a DJ list at each join`() {
        splitDjList("Bain, Berry T, Kite & Domske") shouldContainExactly listOf("Bain", "Berry T", "Kite", "Domske")
        splitDjList("Anna und Berta and Cleo + Dora") shouldContainExactly listOf("Anna", "Berta", "Cleo", "Dora")
        splitDjList("Anna &Berta") shouldContainExactly listOf("Anna", "Berta")
    }

    @Test
    fun `keeps an unspaced join inside its name`() {
        splitDjList("R&B Ralf, Sandy") shouldContainExactly listOf("R&B Ralf", "Sandy")
    }

    @Test
    fun `a sentence ends in a mark and has the words of one`() {
        endsAsSentence("It is the last night.", minWords = 4) shouldBe true
        endsAsSentence("arkadiusz.", minWords = 4) shouldBe false
        endsAsSentence("It is the last night", minWords = 4) shouldBe false
    }
}
