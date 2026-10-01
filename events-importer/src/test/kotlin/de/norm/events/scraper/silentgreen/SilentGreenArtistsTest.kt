package de.norm.events.scraper.silentgreen

import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test

class SilentGreenArtistsTest {
    private fun actsOf(title: String) = silentGreenArtists(title, "CONCERT").map { it.name }

    @Test
    fun `drops a series set off by an en dash in front of several acts`() {
        // 23 October 2026.
        actsOf("HALF LIGHT – Abul Mogard, Marja de Sanctis, Rafael Anton Irisarri, Concepción Huerta") shouldContainExactly
            listOf("Abul Mogard", "Marja de Sanctis", "Rafael Anton Irisarri", "Concepción Huerta")
    }

    @Test
    fun `drops the house series named after the act`() {
        // 26 and 27 September 2026.
        actsOf("Current 93 + Reg Meuross – Sonic Morgue") shouldContainExactly listOf("Current 93", "Reg Meuross")
    }
}
