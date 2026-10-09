// The spoken-language filter (#2524). Its codes are a subset of `SpokenLanguage` in events-core.

/** The languages the filter offers, in display order. The BFF accepts any code; these two hold the listings. */
export const FILTER_LANGUAGES: readonly string[] = ['en', 'de']

/**
 * The event types whose listings say what language is spoken: they held 862 of the 864 events
 * with a language when #2524 was refined. Concerts and parties never say, so the filter would only hide them.
 */
export const LANGUAGE_EVENT_TYPES: readonly string[] = ['COMEDY', 'READING', 'QUIZ', 'SHOW']

/** True when the type filter selects only types that carry a language, so the language filter can help. */
export function languageFilterApplies(eventTypes: readonly string[]): boolean {
  return (
    eventTypes.length > 0 &&
    eventTypes.every((type) => LANGUAGE_EVENT_TYPES.includes(type.toUpperCase()))
  )
}
