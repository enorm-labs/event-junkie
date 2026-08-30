package de.norm.events.meta

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Public read API exposing the running build, for the frontend footer.
 *
 * Deliberately not sourced from the GitHub Releases API: that reports the latest *published*
 * release rather than what is deployed, and calling it from the browser would send every visitor's
 * IP address to a US third party. See docs/LEGAL.md §4.1.
 */
@RestController
@RequestMapping("/api/meta")
@Tag(name = "Meta", description = "Public endpoint reporting the running build")
class MetaController(
    /**
     * `ObjectProvider` rather than a direct injection: `BuildProperties` only exists when
     * `META-INF/build-info.properties` is on the classpath, which a packaged build produces but
     * `bootRun` and the IDE do not. A hard dependency would fail context startup for every
     * developer; the response falls back to `dev` instead.
     */
    private val buildProperties: ObjectProvider<BuildProperties>
) {
    @GetMapping
    @Operation(summary = "Report the version and commit of the running backend")
    fun meta(): MetaResponse = MetaResponse.from(buildProperties.ifAvailable)
}
