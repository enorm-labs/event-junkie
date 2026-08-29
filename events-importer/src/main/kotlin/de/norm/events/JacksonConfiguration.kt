package de.norm.events

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.DeserializationFeature

/**
 * Makes an unknown field in a request body a `400` rather than a silently dropped one (#814).
 *
 * Spring Boot disables `FAIL_ON_UNKNOWN_PROPERTIES`, so `PATCH /api/admin/event-sources/{slug}`
 * accepted any JSON, kept what it recognised, and answered `200` with the unchanged row. Every
 * field of [de.norm.events.scraper.EventSourceUpdateRequest] is nullable for correct PATCH
 * semantics, so a body with no recognised field was indistinguishable from one asking for nothing.
 * `scripts/apply-licence-review.py` reported "Wrote 85 of 85" against a server whose columns did
 * not exist yet. A write that reports success and does nothing is the worst available failure,
 * because no caller can detect it.
 *
 * **A bean rather than `spring.jackson.deserialization.fail-on-unknown-properties`, deliberately.**
 * `src/test/resources/application.yaml` *shadows* the main file rather than merging with it — its
 * own header says so, and that is why `MetricsExposureConfigTest` has to assert the main file
 * directly. A property set only in the main file would therefore be absent from every test, so the
 * suite would prove the opposite of what production does. Configuration that must not diverge
 * between the two belongs in code, which no resource file can shadow.
 *
 * The blast radius is this module's own request bodies. No scraper deserialises through the Spring
 * mapper — each builds its own [tools.jackson.databind.json.JsonMapper], precisely because a
 * venue's JSON carries fields we neither know nor want — and nothing here decodes a typed body from
 * `WebClient`.
 */
@Configuration
class JacksonConfiguration {
    @Bean
    fun failOnUnknownRequestFields(): JsonMapperBuilderCustomizer =
        JsonMapperBuilderCustomizer { builder -> builder.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES) }
}
