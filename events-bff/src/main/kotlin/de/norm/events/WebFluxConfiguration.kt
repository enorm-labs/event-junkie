package de.norm.events

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.web.WebProperties
import org.springframework.boot.webflux.error.ErrorAttributes
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.codec.ServerCodecConfigurer
import org.springframework.web.reactive.config.CorsRegistry
import org.springframework.web.reactive.config.WebFluxConfigurer
import org.springframework.web.reactive.result.method.annotation.ArgumentResolverConfigurer

/**
 * WebFlux configuration: [StableSortPageableArgumentResolver] in place of Spring Data's
 * `ReactivePageableHandlerMethodArgumentResolver`, so every paged request carries a unique final
 * sort key and `app.api.max-page-size` bounds it; CORS from `app.cors.allowed-origins`, empty by
 * default since the Vite proxy makes local requests same-origin; and [ProblemDetailErrorHandler]
 * wired the way `ErrorWebFluxAutoConfiguration` wires the default.
 */
@Configuration
class WebFluxConfiguration(
    @Value("\${app.cors.allowed-origins:}") private val allowedOrigins: List<String>,
    // No fallback: an absent property is a context failure, not a silent return to the 2000-row
    // default, and the test `application.yaml` shadows the main one (#268).
    @Value("\${app.api.max-page-size}") private val maxPageSize: Int
) : WebFluxConfigurer {
    override fun configureArgumentResolvers(configurer: ArgumentResolverConfigurer) {
        configurer.addCustomResolver(StableSortPageableArgumentResolver(maxPageSize))
    }

    @Bean
    @Order(-1)
    fun errorWebExceptionHandler(
        errorAttributes: ErrorAttributes,
        webProperties: WebProperties,
        codecs: ServerCodecConfigurer,
        applicationContext: ApplicationContext
    ): ErrorWebExceptionHandler =
        ProblemDetailErrorHandler(errorAttributes, webProperties.resources, webProperties.error, applicationContext).apply {
            setMessageWriters(codecs.writers)
            setMessageReaders(codecs.readers)
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
