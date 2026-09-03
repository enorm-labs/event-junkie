package de.norm.events

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.config.WebFluxConfigurer
import org.springframework.web.reactive.result.method.annotation.ArgumentResolverConfigurer

/**
 * Registers the reactive [Pageable] argument resolver for WebFlux.
 *
 * This enables controllers to accept [org.springframework.data.domain.Pageable] parameters
 * that Spring resolves from `page`, `size`, and `sort` query parameters
 * (e.g. `?page=0&size=20&sort=name,asc`). Without this configuration, WebFlux cannot
 * construct a `Pageable` instance from request parameters.
 *
 * [StableSortPageableArgumentResolver] is used in place of Spring Data's
 * `ReactivePageableHandlerMethodArgumentResolver` so that every paged request carries a
 * unique final sort key and cannot repeat or skip rows across pages.
 */
@Configuration
class WebFluxConfiguration(
    // No fallback value. An absent property is a context failure, not a silent return to Spring
    // Data's 2000-row default, and `src/test/resources/application.yaml` shadows the main file
    // rather than merging with it — so a default here would hide the cap being dropped.
    @Value("\${app.api.max-page-size}") private val maxPageSize: Int
) : WebFluxConfigurer {
    override fun configureArgumentResolvers(configurer: ArgumentResolverConfigurer) {
        configurer.addCustomResolver(StableSortPageableArgumentResolver(maxPageSize))
    }
}
