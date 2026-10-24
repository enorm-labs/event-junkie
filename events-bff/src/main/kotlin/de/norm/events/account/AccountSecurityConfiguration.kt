package de.norm.events.account

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository
import org.springframework.security.web.server.csrf.ServerCsrfTokenRequestAttributeHandler
import org.springframework.security.web.server.firewall.ServerWebExchangeFirewall
import org.springframework.security.web.server.firewall.StrictServerWebExchangeFirewall
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers.pathMatchers
import reactor.core.publisher.Mono

/**
 * Spring Security for the account paths only (ADR-053). The chain matches the paths under `/api/auth/` and
 * `/api/me`, so the public read API runs exactly as before: no session lookup, no cookie, no
 * header Spring Security would add.
 *
 * CSRF is the cookie-to-header pattern: the SPA reads `XSRF-TOKEN` and echoes it in `X-XSRF-TOKEN`.
 * The plain request handler, not the XOR one, because the SPA sends the cookie's value as it is. The
 * cookie appears only once the sign-in page asks `/api/auth/csrf`, so a visitor who never signs in
 * stores nothing (§ 25 TDDDG).
 */
@Configuration
class AccountSecurityConfiguration {
    @Bean
    fun accountSecurity(
        http: ServerHttpSecurity,
        sessions: SessionSecurityContextRepository
    ): SecurityWebFilterChain {
        val csrfTokens =
            CookieServerCsrfTokenRepository.withHttpOnlyFalse().apply {
                setCookieCustomizer {
                    it
                        .path("/")
                        .secure(true)
                        .sameSite("Strict")
                        .maxAge(SessionSecurityContextRepository.LIFETIME)
                }
            }
        return http
            .securityMatcher(pathMatchers("/api/auth/**", "/api/me", "/api/me/**"))
            .securityContextRepository(sessions)
            .csrf { it.csrfTokenRepository(csrfTokens).csrfTokenRequestHandler(ServerCsrfTokenRequestAttributeHandler()) }
            .authorizeExchange {
                it
                    .pathMatchers("/api/auth/**")
                    .permitAll()
                    .anyExchange()
                    .authenticated()
            }.exceptionHandling { it.authenticationEntryPoint(HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)) }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .requestCache { it.disable() }
            // CacheControlFilter answers for caching, and Traefik's middleware sets the security headers.
            .headers { it.disable() }
            .build()
    }

    /**
     * Spring Security's firewall runs in front of every route, not only the chain above. Unscoped, it
     * answered a traversal probe with a 400 instead of the 404 `StaticResourceHandlerTest` pins, and a
     * NUL byte with an empty 400 before `NulByteFilter` could write its problem. So it checks the account
     * paths, and the rest of the API answers exactly as it did before Spring Security arrived.
     */
    @Bean
    fun accountFirewall(): ServerWebExchangeFirewall {
        val strict = StrictServerWebExchangeFirewall()
        return ServerWebExchangeFirewall { exchange ->
            if (isAccountPath(exchange.request.path.value())) strict.getFirewalledExchange(exchange) else Mono.just(exchange)
        }
    }

    private companion object {
        fun isAccountPath(path: String) = path.startsWith("/api/auth/") || path == "/api/me" || path.startsWith("/api/me/")
    }
}
