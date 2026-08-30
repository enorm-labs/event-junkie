package de.norm.events.venue

import io.kotest.matchers.shouldBe
import jakarta.validation.Validation
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * The plausibility bound #329 asked for, and what it is honestly worth.
 *
 * **None of the 32 wrong coordinates the geo audit found would fail here.** Every one of them was
 * already inside Berlin and wrong by 207 m to 1810 m. Only comparison against an external source
 * finds a plausible-but-wrong coordinate.
 *
 * What this catches is the cheaper class, and the one a bound can catch at all: a swapped pair, a
 * misplaced decimal point, and a zero pair from a field nobody filled in.
 */
class VenueCoordinateBoundsTest {
    private val validator = Validation.buildDefaultValidatorFactory().validator

    private fun rejected(
        lat: String?,
        lon: String?
    ): List<String> {
        val request =
            VenueRequestFixtures.create(
                latitude = lat?.let(::BigDecimal),
                longitude = lon?.let(::BigDecimal)
            )
        return validator.validate(request).map { it.propertyPath.toString() }.sorted()
    }

    @Test
    fun `a Berlin coordinate is accepted`() {
        rejected("52.507242", "13.451803") shouldBe emptyList()
    }

    @Test
    fun `null coordinates are accepted, because a venue may have none yet`() {
        rejected(null, null) shouldBe emptyList()
    }

    @Test
    fun `a latitude and longitude swapped by hand is refused`() {
        rejected("13.451803", "52.507242") shouldBe listOf("latitude", "longitude")
    }

    @Test
    fun `a decimal point in the wrong place is refused`() {
        rejected("5.2507242", "13.451803") shouldBe listOf("latitude")
    }

    @Test
    fun `an unfilled zero pair is refused`() {
        rejected("0", "0") shouldBe listOf("latitude", "longitude")
    }

    @Test
    fun `a coordinate in Hamburg is refused`() {
        rejected("53.5511", "9.9937") shouldBe listOf("latitude", "longitude")
    }

    @Test
    fun `every venue we hold today is inside the bound`() {
        // The bound is padded past the extremes of the real estate on purpose: Zitadelle in the west
        // and Parkbuehne Wuhlheide in the east sit well inside it, and a new venue must not be
        // refused for being at the edge of the city.
        rejected("52.54103", "13.21274") shouldBe emptyList()
        rejected("52.46234", "13.54540") shouldBe emptyList()
    }
}
