package de.norm.events.translation

import de.norm.events.event.DescriptionLanguage

/**
 * Turns one description into the other language, or declines.
 *
 * **The caller decides whether translation is allowed at all** — only a source whose grant names
 * translation reaches an engine (ADR-026). An engine's job is the text, and its promise is that the
 * names in [TranslationRequest.protectedTerms] survive it verbatim.
 */
interface TranslationEngine {
    /** How this engine names itself in `description_alt_engine`, so a stored text can be traced and redone. */
    val id: String

    /**
     * Whether this engine is switched on. A switched-off engine is never asked, so its nulls do not
     * count as failed translations (#1810).
     */
    val enabled: Boolean get() = true

    /**
     * Translates [request], or returns null when it cannot.
     *
     * Null is an ordinary outcome, not an error: an engine that is switched off returns it for
     * everything. The caller logs and moves on, leaving the columns as they were.
     */
    suspend fun translate(request: TranslationRequest): String?
}

/**
 * One description to translate, and the names that must come through unchanged.
 *
 * [protectedTerms] are this event's own venue, artist and promoter names. They are the words a
 * translation is most likely to damage, and the ones a reader most needs intact: "About Blank" is
 * not "Über Leer". Taken from the event rather than from a curated list, which is #323's question.
 */
data class TranslationRequest(
    val text: String,
    val from: DescriptionLanguage,
    val to: DescriptionLanguage,
    val protectedTerms: List<String> = emptyList()
)
