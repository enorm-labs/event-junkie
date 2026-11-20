package de.norm.events.account

import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Schema(description = "One visitor account")
data class AccountResponse(
    @Schema(description = "The address, as typed at the first sign-in.", example = "operator@example.org")
    val email: String,
    @Schema(description = "The language of the account's mail: de or en.", example = "de")
    val locale: String,
    val createdAt: Instant,
    val lastSignInAt: Instant,
    @Schema(description = "True when the account sees \"Report a problem\" (PUT /api/admin/accounts/can-report).")
    val canReport: Boolean,
    @Schema(description = "How many follows the account has: artists, venues, promoters, festivals, saved searches and watched acts.")
    val follows: Long,
    @Schema(description = "How many of the account's problem reports wait in the queue.")
    val openReports: Long
)

/**
 * The stored visitor accounts (#5317), so the operator finds an address for the allowlist without a database tunnel.
 * `scripts/accounts.sh` prints them as a table. The addresses stay in the response, never in the log.
 */
@RestController
@RequestMapping("/api/admin/accounts")
@Tag(name = "Admin: Accounts", description = "The visitor accounts, read only")
class AccountAdminController(
    private val store: AccountAdminStore
) {
    @GetMapping
    @Operation(summary = "Every account, oldest first")
    suspend fun list(): List<AccountResponse> {
        val accounts = store.list()
        // The count, never an address: the log leaves the cluster.
        logger.info { "Listed ${accounts.size} accounts" }
        return accounts.map {
            AccountResponse(it.email, it.locale, it.createdAt, it.lastSignInAt, it.canReport, it.follows, it.openReports)
        }
    }
}
