package de.norm.events.account

import de.norm.events.push.PushMessage
import de.norm.events.push.PushResult
import de.norm.events.push.PushSender
import java.time.Clock
import java.util.UUID

/**
 * Sends each account one [message] built from its items, to each of its browsers, and forgets a browser whose
 * subscription is gone. Answers how often each outcome came back.
 */
internal suspend fun <T> PushSubscriptionStore.fanOut(
    push: PushSender,
    byAccount: Map<UUID, List<T>>,
    clock: Clock,
    message: (List<T>) -> PushMessage
): Map<PushResult, Int> =
    byAccount
        .flatMap { (accountId, items) ->
            val body = message(items)
            val now = clock.instant()
            targets(accountId).map { target ->
                push.send(target, body).also { result ->
                    when (result) {
                        PushResult.SENT -> sent(target.endpoint, now)
                        PushResult.GONE -> delete(target.endpoint)
                        PushResult.REJECTED, PushResult.FAILED -> Unit
                    }
                }
            }
        }.groupingBy { it }
        .eachCount()

/** The outcome counts as a job's "push finished" line shows them: `sent 3, gone 1`. */
internal fun Map<PushResult, Int>.summary(): String = entries.joinToString { "${it.key.tag} ${it.value}" }
