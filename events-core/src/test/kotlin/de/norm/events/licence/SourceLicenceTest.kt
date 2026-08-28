package de.norm.events.licence

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * Covers the vocabulary itself. The display rule that consumes it is pinned separately, in the
 * module that owns the rule.
 */
class SourceLicenceTest {
    @ParameterizedTest
    @EnumSource(SourceLicence::class)
    fun `every member round-trips, case-insensitively and trimmed`(licence: SourceLicence) {
        assertThat(SourceLicence.parseOrProhibited(licence.name)).isEqualTo(licence)
        assertThat(SourceLicence.parseOrProhibited(" ${licence.name.lowercase()} ")).isEqualTo(licence)
    }

    @Test
    @DisplayName("an unrecognised value parses to PROHIBITED, which the display rule does not do")
    fun `parsing fails closed`() {
        // Deliberately the opposite direction from the fail-open display rule, and the KDoc on
        // parseOrProhibited says why: silence from a venue is not a prohibition, but a value that is
        // neither null nor a member of this enum is corrupted data rather than silence.
        assertThat(SourceLicence.parseOrProhibited("permited")).isEqualTo(SourceLicence.PROHIBITED)
        assertThat(SourceLicence.parseOrProhibited("")).isEqualTo(SourceLicence.PROHIBITED)
        assertThat(SourceLicence.parseOrProhibited("yes")).isEqualTo(SourceLicence.PROHIBITED)
    }

    @Test
    @DisplayName("the vocabulary is exactly three members")
    fun `the vocabulary does not grow by accident`() {
        // The CHECK constraint in V006 lists these by name and cannot see this enum. A fourth member
        // added here without the migration would be rejected by the database at write time.
        assertThat(SourceLicence.entries)
            .containsExactly(SourceLicence.PERMITTED, SourceLicence.PROHIBITED, SourceLicence.UNCLEAR)
    }
}
