package de.norm.events.translation

import de.norm.events.event.DescriptionLanguage

/**
 * Closes a low-9 quote the model opened with `„` and closed with a straight `"`.
 *
 * The model does this to every such quote it writes (#4743), so the repair is a rule, not a prompt line. Only a pair with no
 * other quote mark inside is touched, so an unclosed `„` and a correct pair stay as they are.
 */
internal object QuotePairs {
    private val LOW_NINE_THEN_STRAIGHT = Regex("„([^„“”\"]*)\"")

    fun repair(
        text: String,
        to: DescriptionLanguage
    ): String =
        when (to) {
            DescriptionLanguage.GERMAN -> text.replace(LOW_NINE_THEN_STRAIGHT, "„$1“")
            DescriptionLanguage.ENGLISH -> text.replace(LOW_NINE_THEN_STRAIGHT, "“$1”")
            DescriptionLanguage.UNDETERMINED -> text
        }
}
