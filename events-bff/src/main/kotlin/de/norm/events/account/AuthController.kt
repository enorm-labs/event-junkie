package de.norm.events.account

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.web.server.csrf.CsrfToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/** Signing in by e-mail link (ADR-053). Every POST needs the CSRF header. */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Sign-in", description = "Sign in with a link sent by e-mail")
class AuthController(
    private val links: SignInLinks,
    private val store: AccountStore,
    private val sessions: SessionSecurityContextRepository
) {
    @GetMapping("/csrf")
    @Operation(summary = "Sets the XSRF-TOKEN cookie the sign-in page echoes in X-XSRF-TOKEN. Call it before the first POST")
    suspend fun csrf(exchange: ServerWebExchange): ResponseEntity<Unit> {
        exchange.getAttribute<Mono<CsrfToken>>(CsrfToken::class.java.name)?.awaitSingleOrNull()
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/session")
    @Operation(summary = "The signed-in account, or null, and whether new accounts can be created")
    suspend fun session(
        @AuthenticationPrincipal account: Account?
    ) = SessionResponse(account?.let(AccountResponse::of), store.signUpOpen())

    @PostMapping("/link")
    @Operation(
        summary = "Sends a sign-in link",
        description =
            "202 whether or not the address has an account, also when the address asked too often, " +
                "and also when sign-up is paused and the address has no account. " +
                "429 when this client asked too often, 503 when no mail can be sent."
    )
    suspend fun link(
        @RequestBody request: LinkRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Unit> {
        val email = request.email.trim()
        if (!isAddress(email)) refuse(HttpStatus.BAD_REQUEST, "Not an e-mail address")
        if (request.locale !in LOCALES) refuse(HttpStatus.BAD_REQUEST, "Unknown locale")
        val next = request.next?.takeIf { NEXT.matches(it) }
        return when (links.request(email, request.locale, clientAddress(exchange), next)) {
            LinkOutcome.SENT, LinkOutcome.ADDRESS_THROTTLED, LinkOutcome.SIGN_UP_PAUSED -> {
                ResponseEntity.accepted().build()
            }

            LinkOutcome.IP_THROTTLED -> {
                refuse(HttpStatus.TOO_MANY_REQUESTS, "Too many sign-in links from here. Try again in an hour")
            }

            LinkOutcome.MAIL_UNAVAILABLE, LinkOutcome.MAIL_FAILED -> {
                refuse(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "The sign-in mail could not be sent. Try again later"
                )
            }
        }
    }

    @PostMapping("/sign-in")
    @Operation(summary = "Uses a sign-in link's token, creates the account at its first use, and starts a session")
    suspend fun signIn(
        @RequestBody request: SignInRequest,
        exchange: ServerWebExchange
    ): AccountResponse {
        val account = links.use(request.token) ?: refuse(HttpStatus.UNAUTHORIZED, "The link is invalid, used or expired. Ask for a new one")
        sessions.save(exchange, SecurityContextImpl(AccountAuthentication(account))).awaitSingleOrNull()
        return AccountResponse.of(account)
    }

    private companion object {
        val LOCALES = setOf("de", "en")
        const val MAX_ADDRESS_LENGTH = 254
        val ADDRESS = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

        /** A page of this site, so a link in the mail can never send anyone elsewhere (#3889). */
        val NEXT = Regex("^/(de|en)/[a-z0-9/-]{0,200}$")

        fun isAddress(email: String) = email.length <= MAX_ADDRESS_LENGTH && ADDRESS.matches(email)

        fun refuse(
            status: HttpStatus,
            reason: String
        ): Nothing = throw ResponseStatusException(status, reason)
    }
}
