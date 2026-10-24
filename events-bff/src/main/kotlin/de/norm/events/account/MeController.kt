package de.norm.events.account

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import java.time.Clock

/** The signed-in visitor's own account. Every route needs a session; every POST and DELETE the CSRF header. */
@RestController
@RequestMapping("/api/me")
@Tag(name = "Account", description = "The signed-in visitor's account")
class MeController(
    private val store: AccountStore,
    private val sessionRows: SessionStore,
    private val sessions: SessionSecurityContextRepository,
    private val clock: Clock
) {
    @GetMapping
    @Operation(summary = "The signed-in account")
    fun me(
        @AuthenticationPrincipal account: Account
    ) = AccountResponse.of(account)

    @PostMapping("/sign-out")
    @Operation(summary = "Ends this browser's session")
    suspend fun signOut(exchange: ServerWebExchange): ResponseEntity<Unit> {
        sessions.save(exchange, null).awaitSingleOrNull()
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/sign-out-everywhere")
    @Operation(summary = "Ends every session of the account, on every device")
    suspend fun signOutEverywhere(
        @AuthenticationPrincipal account: Account,
        exchange: ServerWebExchange
    ): ResponseEntity<Unit> {
        sessions.endAll(exchange, account)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/export")
    @Operation(summary = "Everything stored about the account, as a JSON download (GDPR Art. 15 and 20)")
    suspend fun export(
        @AuthenticationPrincipal account: Account
    ): ResponseEntity<AccountExport> =
        ResponseEntity
            .ok()
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition
                    .attachment()
                    .filename("event-junkie-account.json")
                    .build()
                    .toString()
            ).body(
                AccountExport(
                    exportedAt = clock.instant(),
                    email = account.email,
                    locale = account.locale,
                    createdAt = account.createdAt,
                    lastSignInAt = account.lastSignInAt,
                    sessions = sessionRows.sessions(account.id)
                )
            )

    @DeleteMapping
    @Operation(summary = "Deletes the account, its sessions and its sign-in links, at once and for good")
    suspend fun delete(
        @AuthenticationPrincipal account: Account,
        exchange: ServerWebExchange
    ): ResponseEntity<Unit> {
        store.deleteAccount(account)
        sessions.endAll(exchange, account)
        return ResponseEntity.noContent().build()
    }
}
