package de.norm.events.event

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class SpokenLanguageTest {
    @Test
    fun `the codes are the closed list the event checks accept, German and English first`() {
        // V095's `event_spoken_languages_valid` and `event_subtitle_language_valid` carry the same list.
        SpokenLanguage.entries.map { it.code } shouldBe listOf("de", "en", "fr", "es", "it", "tr", "ru", "pl")
    }
}
