package de.norm.events.festival

import java.time.LocalDate

/** A 400 with a reason, where the table's CHECK alone would answer a misleading 409. */
class InvalidFestivalDaysException(
    firstDay: LocalDate,
    lastDay: LocalDate
) : RuntimeException("The last day ($lastDay) must not be before the first day ($firstDay)")
