package de.norm.events.venue

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import jakarta.validation.Validation
import org.junit.jupiter.api.Test
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import tools.jackson.module.kotlin.readValue
import java.io.File

/**
 * Every venue block in `dev-seed.http` is a request the admin API accepts. `seed-sources.py` stops at the first venue
 * the API refuses, so one bad block, YAAM's `"capacity": 0`, kept every new source of a release off both clusters.
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
        val blocks = VENUE_BLOCK.findAll(File("../http/importer/dev-seed.http").readText()).map { it.groupValues[1] }.toList()

        val refused =
            blocks.flatMap { json ->
                val request = mapper.readValue<VenueRequest>(json)
                validator.validate(request).map { "${request.name}: ${it.propertyPath} ${it.message}" }
            }

        blocks.size shouldBeGreaterThan 100
        refused.shouldBeEmpty()
    }

    private companion object {
        val VENUE_BLOCK = Regex("""POST \{\{importer-host}}/api/admin/venues\nContent-Type: application/json\n\n(\{\n.*?\n})\n""", RegexOption.DOT_MATCHES_ALL)
    }
}
