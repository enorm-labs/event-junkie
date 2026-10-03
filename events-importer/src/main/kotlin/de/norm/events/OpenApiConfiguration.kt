package de.norm.events

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * OpenAPI metadata for the importer's generated spec and Swagger UI.
 *
 * Without it SpringDoc shows a generic "OpenAPI definition" title and version "1.0". The version is
 * the API's, as in the BFF, not the release: no file carries the release number (ADR-032).
 */
@Configuration
class OpenApiConfiguration {
    @Bean
    fun eventsImporterOpenApi(): OpenAPI =
        OpenAPI()
            .info(
                Info()
                    .title("Events Importer")
                    .version("v1")
                    .description(
                        "Admin API of the importer: event sources and imports, the catalogue's venues, events, artists " +
                            "and promoters, data quality and cached images. Not routed by the Ingress."
                    )
            )
}
