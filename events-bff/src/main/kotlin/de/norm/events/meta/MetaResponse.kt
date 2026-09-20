package de.norm.events.meta

import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.boot.info.BuildProperties

/**
 * Which build is answering this request. Curated rather than `/actuator/info`, which is
 * framework-shaped, leaks the Maven coordinates, is outside the OpenAPI spec the frontend
 * generates its client from, and would need the actuator routed through the ingress
 * (docs/LEGAL.md §4.4).
 */
@Schema(description = "Version and commit of the running backend, for the frontend footer")
data class MetaResponse(
    @Schema(
        description = "Application version. `dev` when the build did not stamp one — i.e. running from the IDE or `bootRun`.",
        example = "0.1.0"
    )
    val version: String,
    @Schema(
        description = "Full commit SHA the running artifact was built from; null when unavailable.",
        example = "9f1a2b3c4d5e6f708192a3b4c5d6e7f809a1b2c3",
        nullable = true
    )
    val commit: String?,
    @Schema(
        description = "First seven characters of `commit`, for display; null when the commit is unavailable.",
        example = "9f1a2b3",
        nullable = true
    )
    val commitShort: String?,
    @Schema(
        description = "When the running artifact was built (ISO-8601); null when the build did not stamp one.",
        example = "2026-08-07T12:49:19Z",
        nullable = true
    )
    val buildTime: String?
) {
    companion object {
        /** Shown when the build did not stamp a version, so the footer never renders an empty gap. */
        const val DEV_VERSION = "dev"

        private const val SHORT_SHA_LENGTH = 7

        /** Placeholder the build stamps when git metadata is unavailable (see the root build). */
        private const val UNKNOWN_COMMIT = "unknown"

        /**
         * [buildProperties] is null when `META-INF/build-info.properties` is absent, the normal case in
         * the IDE and under `bootRun`.
         */
        fun from(buildProperties: BuildProperties?): MetaResponse {
            val commit = buildProperties?.get("commit")?.takeUnless { it.isBlank() || it == UNKNOWN_COMMIT }
            return MetaResponse(
                version = buildProperties?.version ?: DEV_VERSION,
                commit = commit,
                commitShort = commit?.take(SHORT_SHA_LENGTH),
                buildTime = buildProperties?.time?.toString()
            )
        }
    }
}
