package de.norm.events.account

import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.stereotype.Component

/**
 * Whether new accounts can be created: false while an operator has paused sign-up (V200, #3906). The importer's admin API
 * writes the one row; this reads it on every call, so a flip lands at once on every replica.
 */
@Component
class SignUpSwitch(
    private val db: DatabaseClient
) {
    suspend fun isOpen(): Boolean =
        db
            .sql("SELECT open FROM accounts.sign_up")
            .map { row -> row.required("open", Boolean::class.javaObjectType) }
            .awaitOneOrNull() ?: true
}
