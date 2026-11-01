package de.norm.events.account

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.UUID

/** Properties of the notification page token for any cursor and any string: `NotificationPagingTest` pins the paging. */
class NotificationCursorPropertyTest {
    @Test
    fun `a token reads back as the cursor that made it`() =
        runTest {
            checkAll(CURSOR) { cursor -> NotificationCursor.parse(cursor.token()) shouldBe cursor }
        }

    @Test
    fun `a token is URL-safe and carries no padding`() =
        runTest {
            checkAll(CURSOR) { cursor -> cursor.token() shouldMatch URL_SAFE }
        }

    @Test
    fun `any string parses to a cursor or to null, and never throws`() =
        runTest {
            checkAll(TOKEN_LIKE) { token ->
                NotificationCursor.parse(token)?.let { cursor -> NotificationCursor.parse(cursor.token()) shouldBe cursor }
            }
        }

    private companion object {
        val URL_SAFE = Regex("[A-Za-z0-9_-]+")

        /** Instants and dates from year 1 to 9999, at nanosecond precision, and the years a five-digit stamp needs. */
        val INSTANT: Arb<Instant> =
            Arb.choice(
                Arb.bind(Arb.long(-62_135_596_800L..253_402_300_799L), Arb.int(0..999_999_999)) { seconds, nanos ->
                    Instant.ofEpochSecond(seconds, nanos.toLong())
                },
                Arb.element(Instant.EPOCH, Instant.MIN, Instant.MAX)
            )

        val DATE: Arb<LocalDate> =
            Arb.choice(
                Arb.long(LocalDate.of(1, 1, 1).toEpochDay()..LocalDate.of(9999, 12, 31).toEpochDay()).map(LocalDate::ofEpochDay),
                Arb.element(LocalDate.MIN, LocalDate.MAX)
            )

        val UUIDS: Arb<UUID> = Arb.bind(Arb.long(), Arb.long()) { most, least -> UUID(most, least) }

        val CURSOR: Arb<NotificationCursor> = Arb.bind(INSTANT, DATE, UUIDS, ::NotificationCursor)

        private val ENCODER = Base64.getUrlEncoder().withoutPadding()

        /** Garbage, base64 of garbage, base64 of three `|`-joined fields of the wrong shape, and a real token cut short. */
        val TOKEN_LIKE: Arb<String> =
            Arb.choice(
                Arb.string(0..80),
                Arb.byteArray(Arb.int(0..60), Arb.byte()).map { ENCODER.encodeToString(it) },
                Arb.list(Arb.element("", "x", "|", "2026-11-01", "2026-13-01", "2026-11-01T10:00:00Z", UUID(0, 0).toString(), "-1"), 1..5).map {
                    ENCODER.encodeToString(it.joinToString("|").toByteArray())
                },
                Arb.bind(CURSOR, Arb.int(0..60)) { cursor, cut -> cursor.token().take(cut) }
            )
    }
}
