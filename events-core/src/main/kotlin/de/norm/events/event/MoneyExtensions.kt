package de.norm.events.event

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Standard scale for monetary values in the event domain.
 *
 * All prices (presale, box office) are normalized to this scale when entering
 * the system — whether from web scrapers or the admin API. This ensures
 * consistent storage and avoids false positives in change detection
 * (`contentEquals`), because [BigDecimal.equals] is scale-sensitive
 * (e.g. `BigDecimal("10.0") != BigDecimal("10.00")`).
 */
private const val MONEY_SCALE = 2

/**
 * Normalizes this [BigDecimal] to [MONEY_SCALE] decimal places using [RoundingMode.HALF_UP].
 *
 * Intended for price fields entering the domain — call at mapping boundaries
 * (scraper → entity, request DTO → entity) so all persisted prices have
 * a uniform scale.
 */
fun BigDecimal.normalizeMoneyScale(): BigDecimal = setScale(MONEY_SCALE, RoundingMode.HALF_UP)
