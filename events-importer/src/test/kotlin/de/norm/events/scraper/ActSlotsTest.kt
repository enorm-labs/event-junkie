package de.norm.events.scraper

import de.norm.events.common.NameArbs
import de.norm.events.event.ArtistRole
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.property.Exhaustive
import io.kotest.property.checkAll
import io.kotest.property.exhaustive.enum
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ActSlotsTest {
    private fun acts(
        slot: String,
        joins: SlotJoins = SlotJoins.B2B_AND_CONJUNCTIONS
    ): List<Pair<String, ArtistRole>> = parseActSlot(slot, joins, clean = { stripArtistSuffix(it) }).map { it.name to it.role }

    @ParameterizedTest
    @ValueSource(
        strings = ["SERA (Live)", "SERA [LIVE]", "SERA live", "SERA LIVE Hybrid", "SERA (hybrid live)", "SERA live set", "SERA [live band]"]
    )
    fun `reads a closing live format`(billing: String) {
        hasLiveMark(billing) shouldBe true
    }

    @ParameterizedTest
    @ValueSource(strings = ["SERA", "Live", "(Live)", "SERA Live Visuals", "Olive", "Live Wire", "V. (Opera live)", "SERA [Livestream]"])
    fun `reads no live format in a name or another note`(billing: String) {
        hasLiveMark(billing) shouldBe false
    }

    @Test
    fun `a mark that ends the slot covers all its acts and one inside it only its own`() {
        acts("Anna b2b Berta live", SlotJoins.B2B) shouldContainExactly listOf("Anna" to ArtistRole.LIVE, "Berta" to ArtistRole.LIVE)
        acts("Anna live b2b Berta", SlotJoins.B2B) shouldContainExactly listOf("Anna" to ArtistRole.LIVE, "Berta" to ArtistRole.DJ)
    }

    @Test
    fun `splits only at the venue's joins`() {
        acts("Skulder & Mully", SlotJoins.B2B_AND_CROSS).map { it.first } shouldContainExactly listOf("Skulder & Mully")
        acts("Upzet x Soniq", SlotJoins.B2B_AND_CROSS).map { it.first } shouldContainExactly listOf("Upzet", "Soniq")
        acts("Upzet x Soniq", SlotJoins.NONE).map { it.first } shouldContainExactly listOf("Upzet x Soniq")
    }

    @Test
    fun `splits a featured guest and keeps Feat as part of a name`() {
        acts("Anna feat. Berta, Cleo & Dora [LIVE]") shouldContainExactly
            listOf("Anna" to ArtistRole.LIVE, "Berta" to ArtistRole.LIVE, "Cleo" to ArtistRole.LIVE, "Dora" to ArtistRole.LIVE)
        acts("Little Feat b2b Anna").map { it.first } shouldContainExactly listOf("Little Feat", "Anna")
        acts("Anna feat. Berta", SlotJoins.B2B).map { it.first } shouldContainExactly listOf("Anna feat. Berta")
    }

    @Test
    fun `bills no placeholder`() {
        acts("Special Guest [LIVE]").shouldBeEmpty()
    }

    @Test
    fun `bills no blank or refused act for any billing`() =
        runTest {
            checkAll(NameArbs.anyName, Exhaustive.enum<SlotJoins>()) { slot, joins ->
                parseActSlot(slot, joins) { it }.forEach { act ->
                    (act.name.isBlank() || isNonArtistName(act.name)) shouldBe false
                }
            }
        }
}
