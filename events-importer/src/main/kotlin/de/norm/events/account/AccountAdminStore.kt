package de.norm.events.account

import de.norm.events.EVENTS_SCHEMA
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository
import java.time.Instant

/** One account as the operator reads it. */
data class AccountRow(
    val email: String,
    val locale: String,
    val createdAt: Instant,
    val lastSignInAt: Instant,
    val canReport: Boolean,
    val follows: Long,
    val openReports: Long
)

/** The accounts of `accounts.account` (V193), for the operator (#5317). Reads only. */
@Repository
class AccountAdminStore(
    private val databaseClient: DatabaseClient
) {
    /** Every account, oldest first. A report counts only while its event exists, as in the report queue. */
    suspend fun list(): List<AccountRow> =
        databaseClient
            .sql(
                """
                SELECT a.email, a.locale, a.created_at, a.last_sign_in_at, a.can_report,
                       (SELECT count(*) FROM accounts.follow f WHERE f.account_id = a.id) AS follows,
                       (SELECT count(*) FROM accounts.event_report r JOIN $EVENTS_SCHEMA.event e ON e.id = r.event_id
                        WHERE r.account_id = a.id) AS open_reports
                FROM accounts.account a
                ORDER BY a.created_at, lower(a.email)
                """.trimIndent()
            ).map { row, _ ->
                AccountRow(
                    email = requireNotNull(row.get("email", String::class.java)),
                    locale = requireNotNull(row.get("locale", String::class.java)),
                    createdAt = requireNotNull(row.get("created_at", Instant::class.java)),
                    lastSignInAt = requireNotNull(row.get("last_sign_in_at", Instant::class.java)),
                    canReport = requireNotNull(row.get("can_report", Boolean::class.javaObjectType)),
                    follows = requireNotNull(row.get("follows", Long::class.javaObjectType)),
                    openReports = requireNotNull(row.get("open_reports", Long::class.javaObjectType))
                )
            }.flow()
            .toList()
}
