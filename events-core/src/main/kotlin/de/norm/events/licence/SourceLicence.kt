package de.norm.events.licence

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * What is known about our right to republish one kind of material from one event source.
 *
 * Held per source and per field — a venue's own prose and its agency press photographs are
 * different answers, which is why `event_source` carries `description_licence` and `image_licence`
 * separately rather than one column (#283).
 *
 * **`null` is a fourth state and it is not [UNCLEAR].** A null column means nobody has reviewed the
 * source. [UNCLEAR] means somebody read its pages and found nothing that decides it. Both display
 * today, and they read very differently in a report, so merging them would lose the only signal
 * that says how much review work is left.
 */
enum class SourceLicence {
    /** An explicit grant covers this use — a licence, or a press page allowing editorial reuse. */
    PERMITTED,

    /** The source forbids reuse, or a third party visibly holds the rights (an agency credit). */
    PROHIBITED,

    /** Somebody reviewed the source and found nothing that decides it. The common outcome. */
    UNCLEAR;

    companion object {
        /**
         * Parses [value], falling back to [PROHIBITED] for anything unrecognised.
         *
         * **This is deliberately the opposite of the display rule, and the two are different
         * questions.** The display rule is fail-open: [UNCLEAR] and `null` both show, because
         * silence from a venue is not a prohibition (#283). A value that is neither null nor a
         * member of this enum is something else entirely — corrupted data, or a hand-edited row —
         * and guessing permissively about a legal field on the strength of a typo is not the same
         * as declining to read silence as refusal.
         *
         * It also fails visibly. Material disappears and someone asks why, rather than material
         * staying up that a prohibition was meant to remove.
         */
        fun parseOrProhibited(value: String): SourceLicence =
            entries.find { it.name.equals(value.trim(), ignoreCase = true) }
                ?: PROHIBITED.also { logger.warn { "Unknown SourceLicence '$value', withholding the field" } }
    }
}
