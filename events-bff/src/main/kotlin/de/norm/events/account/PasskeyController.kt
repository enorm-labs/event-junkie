package de.norm.events.account

import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Passkeys (#3888): add, rename and delete them on the account page, and sign in with one. The e-mail link stays the
 * way in for an account without one, and for a lost device. Every POST needs the CSRF header, as the link's do.
 */
@RestController
@Tag(name = "Passkeys", description = "Sign in with a passkey, and the account's passkeys")
class PasskeyController(
    private val passkeys: Passkeys,
    private val store: PasskeyStore,
    private val metrics: AccountMetrics,
    private val sessions: SessionSecurityContextRepository
) {
    @GetMapping("/api/me/passkeys")
    @Operation(summary = "The account's passkeys: name, created and last used. Never the key")
    suspend fun list(
        @AuthenticationPrincipal account: Account
    ): List<PasskeyInfo> = store.list(account.id)

    @PostMapping("/api/me/passkeys/options")
    @Operation(summary = "The options for navigator.credentials.create(). Each one holds a challenge that lives five minutes")
    suspend fun creationOptions(
        @AuthenticationPrincipal account: Account
    ): CreationOptions = passkeys.creationOptions(account)

    @PostMapping("/api/me/passkeys")
    @Operation(
        summary = "Stores a passkey from the browser's answer to the options",
        description = "400 for an answer that does not verify, 409 when the account has ${Passkeys.MAX_PASSKEYS} passkeys already."
    )
    suspend fun register(
        @AuthenticationPrincipal account: Account,
        @RequestBody request: PasskeyRegistration
    ): ResponseEntity<Unit> =
        when (passkeys.register(account, passkeyName(request.name), request.credential)) {
            RegisterOutcome.REGISTERED -> ResponseEntity.noContent().build()
            RegisterOutcome.INVALID -> refuse(HttpStatus.BAD_REQUEST, "The passkey could not be verified. Try again")
            RegisterOutcome.LIMIT -> refuse(HttpStatus.CONFLICT, "This account has ${Passkeys.MAX_PASSKEYS} passkeys already. Delete one first")
        }

    @PatchMapping("/api/me/passkeys/{id}")
    @Operation(summary = "Renames a passkey")
    suspend fun rename(
        @AuthenticationPrincipal account: Account,
        @PathVariable id: UUID,
        @RequestBody request: PasskeyRename
    ): ResponseEntity<Unit> {
        if (!store.rename(account.id, id, passkeyName(request.name))) refuse(HttpStatus.NOT_FOUND, "No such passkey")
        record(PasskeyOutcome.RENAMED)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/api/me/passkeys/{id}")
    @Operation(summary = "Deletes a passkey. It can no longer sign in. Deleting one that is gone changes nothing")
    suspend fun delete(
        @AuthenticationPrincipal account: Account,
        @PathVariable id: UUID
    ): ResponseEntity<Unit> {
        if (store.delete(account.id, id)) record(PasskeyOutcome.DELETED)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/api/auth/passkey/options")
    @Operation(summary = "The options for navigator.credentials.get(). 429 when this client asked too often")
    suspend fun requestOptions(exchange: ServerWebExchange): RequestOptions =
        passkeys.requestOptions(clientAddress(exchange))
            ?: refuse(HttpStatus.TOO_MANY_REQUESTS, "Too many passkey sign-ins from here. Try again in an hour")

    @PostMapping("/api/auth/passkey/sign-in")
    @Operation(summary = "Signs in with the browser's answer to the options, and starts a session. 401 for every refusal")
    suspend fun signIn(
        @RequestBody request: PasskeySignIn,
        exchange: ServerWebExchange
    ): AccountResponse {
        val account = passkeys.signIn(request.credential) ?: refuse(HttpStatus.UNAUTHORIZED, "The passkey did not sign in. Use the e-mail link")
        sessions.save(exchange, SecurityContextImpl(AccountAuthentication(account))).awaitSingleOrNull()
        return AccountResponse.of(account)
    }

    private fun record(outcome: PasskeyOutcome) {
        metrics.record(outcome)
        logger.info { "Passkey: ${outcome.tag}" }
    }

    private companion object {
        const val MAX_NAME_LENGTH = 60
        const val DEFAULT_NAME = "Passkey"

        fun passkeyName(name: String?) = name?.trim()?.take(MAX_NAME_LENGTH)?.takeIf { it.isNotEmpty() } ?: DEFAULT_NAME

        fun refuse(
            status: HttpStatus,
            reason: String
        ): Nothing = throw ResponseStatusException(status, reason)
    }
}

/** [credential] is `PublicKeyCredential.toJSON()` from the browser, passed on as it is. */
data class PasskeyRegistration(
    val name: String?,
    val credential: Map<String, Any?>
)

data class PasskeyRename(
    val name: String
)

data class PasskeySignIn(
    val credential: Map<String, Any?>
)
