package de.norm.events.account

import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.stereotype.Component

/** `AllAcceptedCredentialsOptions`: the account's user handle and every credential id the site still accepts, base64url. */
data class AcceptedPasskeys(
    val rpId: String,
    val userId: String,
    val allAcceptedCredentialIds: List<String>
)

/**
 * What `PublicKeyCredential.signalAllAcceptedCredentials()` takes (#4954), so the password manager hides a deleted passkey. Its own
 * class because it reads the handle without making one, unlike [PasskeyStore.userHandle].
 */
@Component
class AcceptedPasskeysReader(
    private val db: DatabaseClient,
    private val store: PasskeyStore,
    private val passkeys: Passkeys
) {
    /** Null for an account that never made a passkey: it has no user handle to name. */
    suspend fun read(account: Account): AcceptedPasskeys? {
        val handle =
            db
                .sql("SELECT passkey_user_handle FROM accounts.account WHERE id = :account AND passkey_user_handle IS NOT NULL")
                .bind("account", account.id)
                .map { row -> row.required("passkey_user_handle", ByteArray::class.java) }
                .awaitOneOrNull() ?: return null
        return AcceptedPasskeys(
            rpId = passkeys.rpId,
            userId = Passkeys.base64(handle),
            allAcceptedCredentialIds = store.credentialIds(account.id).map(Passkeys::base64)
        )
    }
}
