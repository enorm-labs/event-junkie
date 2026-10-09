package de.norm.events.venue

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import jakarta.validation.Validation
import org.junit.jupiter.api.Test
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import tools.jackson.module.kotlin.treeToValue

/**
 * Every seed venue file's `venue` is a request the admin API accepts. `seed-sources.py` stops at the first venue
 * the API refuses, so one bad body, YAAM's `"capacity": 0`, kept every new source of a release off both clusters.
 */
class SeedVenueRequestsTest {
    private val validator = Validation.buildDefaultValidatorFactory().validator
    private val mapper =
        JsonMapper
            .builder()
            .addModule(kotlinModule())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build()

    @Test
    fun `every seeded venue passes the admin API's request validation`() {
        val venues = seedVenues()

        val refused =
            venues.flatMap { seed ->
                val request = mapper.treeToValue<VenueRequest>(seed.venue)
                validator.validate(request).map { "${seed.file.name}: ${it.propertyPath} ${it.message}" }
            }

        venues.size shouldBeGreaterThan 100
        refused.shouldBeEmpty()
    }
}
