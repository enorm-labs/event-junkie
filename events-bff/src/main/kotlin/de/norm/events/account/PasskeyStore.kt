package de.norm.events.account

import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.awaitSingle
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** What a verification needs of a passkey. [credential] is webauthn4j's attested credential data, AAGUID zeroed. */
class StoredPasskey(
    val credentialId: ByteArray,
    val credential: ByteArray,
    val signCount: Long,
    val flags: PasskeyFlags,
    val transports: List<String>
)

/** The authenticator's flags at registration: user verification, and whether the passkey may be and is backed up (synced). */
data class PasskeyFlags(
    val uvInitialized: Boolean?,
    val backupEligible: Boolean?,
    val backupState: Boolean?
)

/** A passkey row with its account, as a sign-in finds it by the credential id. */
class PasskeyRow(
    val id: UUID,
    val accountId: UUID,
    val userHandle: ByteArray?,
    val passkey: StoredPasskey
)

/** A passkey as the account page and the export show it: never the key. */
data class PasskeyInfo(
    val id: UUID,
    val name: String,
    val createdAt: Instant,
    val lastUsedAt: Instant?
)

/** The rows of `accounts.passkey` and `accounts.passkey_challenge` (V204, #3888). */
@Component
class PasskeyStore(
    private val db: DatabaseClient
) {
    /** The account's user handle, created at the first call. */
    suspend fun userHandle(
        accountId: UUID,
        fresh: ByteArray
    ): ByteArray =
        db
            .sql(
                "UPDATE accounts.account SET passkey_user_handle = COALESCE(passkey_user_handle, :fresh) WHERE id = :account " +
                    "RETURNING passkey_user_handle"
            ).bind("fresh", fresh)
            .bind("account", accountId)
            .map { row -> row.required("passkey_user_handle", ByteArray::class.java) }
            .awaitSingle()

    /** [accountId] is the account that registers, or null for a sign-in. */
    suspend fun issueChallenge(
        challenge: ByteArray,
        accountId: UUID?,
        expiresAt: Instant
    ) {
        db
            .sql("INSERT INTO accounts.passkey_challenge (challenge, account_id, expires_at) VALUES (:challenge, :account, :expires)")
            .bind("challenge", challenge)
            .let { if (accountId == null) it.bindNull("account", UUID::class.java) else it.bind("account", accountId) }
            .bind("expires", expiresAt)
            .await()
    }

    /** Deletes the challenge and answers whether it was live and issued to [accountId] (null: a sign-in). Single use either way. */
    suspend fun consumeChallenge(
        challenge: ByteArray,
        accountId: UUID?,
        now: Instant
    ): Boolean =
        db
            .sql("DELETE FROM accounts.passkey_challenge WHERE challenge = :challenge RETURNING account_id, expires_at")
            .bind("challenge", challenge)
            .map { row -> row.get("account_id", UUID::class.java) == accountId && row.required("expires_at", Instant::class.java) > now }
            .awaitOneOrNull() ?: false

    /** Stores the passkey unless the account has [max] already. Answers whether it did. */
    suspend fun add(
        accountId: UUID,
        passkey: StoredPasskey,
        name: String,
        now: Instant,
        max: Long
    ): Boolean =
        db
            .sql(
                "INSERT INTO accounts.passkey (account_id, credential_id, credential, sign_count, uv_initialized, backup_eligible, backup_state, " +
                    "transports, name, created_at) " +
                    "SELECT :account, :credentialId, :credential, :signCount, :uv, :be, :bs, :transports, :name, :now " +
                    "WHERE (SELECT count(*) FROM accounts.passkey WHERE account_id = :account) < :max"
            ).bind("account", accountId)
            .bind("credentialId", passkey.credentialId)
            .bind("credential", passkey.credential)
            .bind("signCount", passkey.signCount)
            .bindFlag("uv", passkey.flags.uvInitialized)
            .bindFlag("be", passkey.flags.backupEligible)
            .bindFlag("bs", passkey.flags.backupState)
            .bind("transports", passkey.transports.toTypedArray())
            .bind("name", name)
            .bind("now", now)
            .bind("max", max)
            .fetch()
            .awaitRowsUpdated() == 1L

    /** The credential ids of the account, which a new registration must not repeat. */
    suspend fun credentialIds(accountId: UUID): List<ByteArray> =
        db
            .sql("SELECT credential_id FROM accounts.passkey WHERE account_id = :account")
            .bind("account", accountId)
            .map { row -> row.required("credential_id", ByteArray::class.java) }
            .flow()
            .toList()

    suspend fun find(credentialId: ByteArray): PasskeyRow? =
        db
            .sql(
                "SELECT p.*, a.passkey_user_handle FROM accounts.passkey p JOIN accounts.account a ON a.id = p.account_id " +
                    "WHERE p.credential_id = :credentialId"
            ).bind("credentialId", credentialId)
            .map(::toRow)
            .awaitOneOrNull()

    /**
     * Records a sign-in with the passkey, and counts it as use of the account, as a renewed session does (#3905). Answers the account,
     * or null when the passkey was deleted meanwhile.
     */
    suspend fun signedIn(
        id: UUID,
        signCount: Long,
        backupState: Boolean,
        now: Instant
    ): Account? =
        db
            .sql(
                "WITH p AS (UPDATE accounts.passkey SET sign_count = :signCount, backup_state = :bs, last_used_at = :now WHERE id = :id " +
                    "RETURNING account_id) " +
                    "UPDATE accounts.account a SET last_sign_in_at = :now, deletion_warned_at = NULL FROM p WHERE a.id = p.account_id " +
                    "RETURNING ${ACCOUNT_COLUMNS.split(", ").joinToString { "a.$it" }}"
            ).bind("id", id)
            .bind("signCount", signCount)
            .bind("bs", backupState)
            .bind("now", now)
            .map(::toAccount)
            .awaitOneOrNull()

    suspend fun list(accountId: UUID): List<PasskeyInfo> =
        db
            .sql("SELECT id, name, created_at, last_used_at FROM accounts.passkey WHERE account_id = :account ORDER BY created_at")
            .bind("account", accountId)
            .map { row ->
                PasskeyInfo(
                    row.required("id", UUID::class.java),
                    row.required("name", String::class.java),
                    row.required("created_at", Instant::class.java),
                    row.get("last_used_at", Instant::class.java)
                )
            }.flow()
            .toList()

    suspend fun rename(
        accountId: UUID,
        id: UUID,
        name: String
    ): Boolean =
        db
            .sql("UPDATE accounts.passkey SET name = :name WHERE id = :id AND account_id = :account")
            .bind("name", name)
            .bind("id", id)
            .bind("account", accountId)
            .fetch()
            .awaitRowsUpdated() == 1L

    suspend fun delete(
        accountId: UUID,
        id: UUID
    ): Boolean =
        db
            .sql("DELETE FROM accounts.passkey WHERE id = :id AND account_id = :account")
            .bind("id", id)
            .bind("account", accountId)
            .fetch()
            .awaitRowsUpdated() == 1L

    /** Challenges nobody answered. A consume already refuses them; this keeps the table from growing. */
    suspend fun deleteExpiredChallenges(now: Instant): Long =
        db
            .sql("DELETE FROM accounts.passkey_challenge WHERE expires_at < :now")
            .bind("now", now)
            .fetch()
            .awaitRowsUpdated()
}

private fun DatabaseClient.GenericExecuteSpec.bindFlag(
    name: String,
    value: Boolean?
) = if (value == null) bindNull(name, Boolean::class.javaObjectType) else bind(name, value)

private fun toRow(row: Readable) =
    PasskeyRow(
        id = row.required("id", UUID::class.java),
        accountId = row.required("account_id", UUID::class.java),
        userHandle = row.get("passkey_user_handle", ByteArray::class.java),
        passkey =
            StoredPasskey(
                credentialId = row.required("credential_id", ByteArray::class.java),
                credential = row.required("credential", ByteArray::class.java),
                signCount = row.required("sign_count", Long::class.javaObjectType),
                flags =
                    PasskeyFlags(
                        uvInitialized = row.get("uv_initialized", Boolean::class.javaObjectType),
                        backupEligible = row.get("backup_eligible", Boolean::class.javaObjectType),
                        backupState = row.get("backup_state", Boolean::class.javaObjectType)
                    ),
                transports = row.required("transports", Array<String>::class.java).toList()
            )
    )
