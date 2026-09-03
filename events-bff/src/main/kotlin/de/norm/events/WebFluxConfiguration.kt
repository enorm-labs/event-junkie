package de.norm.events

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.config.CorsRegistry
import org.springframework.web.reactive.config.WebFluxConfigurer
import org.springframework.web.reactive.result.method.annotation.ArgumentResolverConfigurer

/**
 * WebFlux configuration for the BFF.
 *
 * - Registers the reactive [Pageable] argument resolver so controllers can accept
 *   [org.springframework.data.domain.Pageable] parameters resolved from `page`, `size`, and
 *   `sort` query parameters (e.g. `?page=0&size=20&sort=eventDate,asc`).
 *   [StableSortPageableArgumentResolver] is used in place of Spring Data's
 *   `ReactivePageableHandlerMethodArgumentResolver` so that every paged request carries a
 *   unique final sort key and cannot repeat or skip rows across pages, and so that
 *   `app.api.max-page-size` bounds how much one request may ask for.
 * - Configures CORS from the `app.cors.allowed-origins` property. In local development the
 *   Vite proxy makes requests same-origin, so this is empty by default and only needed when
 *   the SPA is served from a different origin than the BFF.
 */
@Configuration
class WebFluxConfiguration(
    @Value("\${app.cors.allowed-origins:}") private val allowedOrigins: List<String>,
    // No fallback value. An absent property is a context failure, not a silent return to Spring
    // Data's 2000-row default, and `src/test/resources/application.yaml` shadows the main file
    // rather than merging with it — so a default here would hide the cap being dropped (#268).
    @Value("\${app.api.max-page-size}") private val maxPageSize: Int
) : WebFluxConfigurer {
    override fun configureArgumentResolvers(configurer: ArgumentResolverConfigurer) {
        configurer.addCustomResolver(StableSortPageableArgumentResolver(maxPageSize))
    }

    @Suppress("SpreadOperator") // CorsRegistration.allowedOrigins is vararg-only, and the list is a handful of origins.
    override fun addCorsMappings(registry: CorsRegistry) {
        if (allowedOrigins.isNotEmpty()) {
            registry
                .addMapping("/**")
                .allowedOrigins(*allowedOrigins.toTypedArray())
                .allowedMethods("GET")
        }
    }
}
