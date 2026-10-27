package de.norm.events.account

import org.springframework.security.web.server.util.matcher.OrServerWebExchangeMatcher
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher

private val ACCOUNT_PATH_PATTERNS = listOf("/api/auth/**", "/api/me", "/api/me/**")

/** The account routes as the security chain matches them (ADR-053), the matcher `pathMatchers` would build. */
fun accountPathMatcher(): ServerWebExchangeMatcher = OrServerWebExchangeMatcher(ACCOUNT_PATH_PATTERNS.map { PathPatternParserServerWebExchangeMatcher(it) })

/**
 * Whether [path] is an account route: the firewall checks it, and `CacheControlFilter` marks its answer `private, no-store`.
 * `AccountPathTest` holds it to [accountPathMatcher] for every route the BFF maps.
 */
fun isAccountPath(path: String) = path.startsWith("/api/auth/") || path == "/api/me" || path.startsWith("/api/me/")
