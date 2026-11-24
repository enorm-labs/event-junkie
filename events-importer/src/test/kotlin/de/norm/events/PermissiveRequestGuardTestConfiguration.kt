package de.norm.events

import de.norm.events.scraper.RedirectTargetGuard
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/**
 * Lets the scraper clients of a `@SpringBootTest` context reach a local test server (#5448).
 *
 * `RedirectFollowingFilter` refuses a first URL on loopback, and `EventSourceControllerTest` imports from a
 * `MockWebServer` on loopback. On [BaseControllerTest], so every subclass keeps one cached context. The real guard
 * is asserted in `ImageUrlValidatorTest` and `RedirectFollowingFilterTest`.
 */
@TestConfiguration(proxyBeanMethods = false)
class PermissiveRequestGuardTestConfiguration {
    @Bean
    @Primary
    fun permissiveRequestGuard(): RedirectTargetGuard = RedirectTargetGuard { null }
}
