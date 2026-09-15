package de.norm.events.common

/**
 * Escapes a visitor's search term for use inside a bound `ILIKE` pattern.
 *
 * `%` and `_` in a search term are letters to the visitor, not wildcards: unescaped, `q=%` matches
 * the whole catalogue at full page cost (#1456). PostgreSQL's default escape character is `\`, so
 * no `ESCAPE` clause is needed; the backslash goes first so an escaped `%` is not escaped twice.
 */
fun String.escapeLike(): String = replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
