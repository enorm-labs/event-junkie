package de.norm.events.sourcelicence

import de.norm.events.licence.SourceLicence
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Pins every branch of the display rule decided on #283.
 *
 * **This test is the record of a decision, not coverage of a one-line method.** The rule is
 * fail-open: only `PROHIBITED` withholds, so an unreviewed source and one reviewed as `UNCLEAR`
 * both display. `docs/SCRAPING_POSITION.md` §3.1 records what that accepts.
 *
 * A change that makes `UNCLEAR` or `null` withhold would blank descriptions on every source that
 * has not been reviewed yet. That may one day be right. It must not happen as a tidy-up, and
 * failing here is what makes it a decision.
 */
class SourceLicencesTest {
    @ParameterizedTest(name = "description {0} -> withheld={1}")
    @CsvSource(
        value = [
            "PERMITTED,false",
            "UNCLEAR,false",
            "PROHIBITED,true"
        ]
    )
    fun `withholds a description only when the source prohibits it`(
        licence: SourceLicence,
        expected: Boolean
    ) {
        assertThat(SourceLicences(description = licence, image = null).withholdsDescription()).isEqualTo(expected)
    }

    @ParameterizedTest(name = "image {0} -> withheld={1}")
    @CsvSource(
        value = [
            "PERMITTED,false",
            "UNCLEAR,false",
            "PROHIBITED,true"
        ]
    )
    fun `withholds an image only when the source prohibits it`(
        licence: SourceLicence,
        expected: Boolean
    ) {
        assertThat(SourceLicences(description = null, image = licence).withholdsImage()).isEqualTo(expected)
    }

    @Test
    @DisplayName("an unreviewed source displays, because silence is not a refusal")
    fun `null withholds nothing`() {
        val unreviewed = SourceLicences(description = null, image = null)
        assertThat(unreviewed.withholdsDescription()).isFalse()
        assertThat(unreviewed.withholdsImage()).isFalse()
    }

    @Test
    @DisplayName("an event whose source was deleted displays")
    fun `unknown source withholds nothing`() {
        assertThat(SourceLicences.UNKNOWN_SOURCE.withholdsDescription()).isFalse()
        assertThat(SourceLicences.UNKNOWN_SOURCE.withholdsImage()).isFalse()
    }

    @Test
    fun `the two fields are answered independently`() {
        val imagesOnly = SourceLicences(description = SourceLicence.PERMITTED, image = SourceLicence.PROHIBITED)
        assertThat(imagesOnly.withholdsDescription()).isFalse()
        assertThat(imagesOnly.withholdsImage()).isTrue()
    }
}
