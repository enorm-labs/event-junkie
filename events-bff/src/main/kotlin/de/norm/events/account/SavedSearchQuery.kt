package de.norm.events.account

import de.norm.events.event.EventFilterParams
import org.springframework.beans.BeanUtils
import org.springframework.web.util.UriComponentsBuilder
import org.springframework.web.util.UriUtils

/**
 * The query string a saved search stores (#3890): the events list's filters, the keys `/feed.xml` takes, plus `when` for a
 * date preset kept relative. Normalised, keys and values sorted, so the same filters in another order are the same search.
 */
object SavedSearchQuery {
    /** `today`, `weekend` and `week`: the list's date presets. A fixed range is not saved, because it is stale in a week. */
    val WHEN = setOf("today", "weekend", "week")

    private const val MAX_LENGTH = 1000

    private val accepted: Set<String> =
        BeanUtils
            .getPropertyDescriptors(EventFilterParams::class.java)
            .map { it.name }
            .filterNot { it == "class" }
            .toSet() + "when"

    /** The normalised query, or the reason it cannot be saved. */
    fun normalise(raw: String): Result<String> {
        val params = UriComponentsBuilder.fromUriString("?" + raw.removePrefix("?")).build().queryParams
        val pairs =
            params
                .flatMap { (key, values) ->
                    values.map { key to UriUtils.decode(it.replace("+", " "), Charsets.UTF_8) }
                }.filter { it.second.isNotBlank() }
        val unknown =
            pairs
                .map { it.first }
                .filterNot { it in accepted }
                .distinct()
                .sorted()
        return when {
            pairs.isEmpty() -> {
                Result.failure(IllegalArgumentException("A saved search needs at least one filter"))
            }

            unknown.isNotEmpty() -> {
                Result.failure(IllegalArgumentException("Not a filter of the events list: ${unknown.joinToString(", ")}"))
            }

            pairs.any { (key, value) -> key == "when" && value !in WHEN } -> {
                Result.failure(IllegalArgumentException("`when` is one of ${WHEN.sorted()}"))
            }

            else -> {
                pairs
                    .distinct()
                    .sortedWith(compareBy({ it.first }, { it.second }))
                    .joinToString("&") { (key, value) -> "$key=${UriUtils.encodeQueryParam(value, Charsets.UTF_8)}" }
                    .let { if (it.length > MAX_LENGTH) Result.failure(IllegalArgumentException("The search is too long")) else Result.success(it) }
            }
        }
    }
}
