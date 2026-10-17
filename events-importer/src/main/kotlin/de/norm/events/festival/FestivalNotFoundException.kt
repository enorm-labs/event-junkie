package de.norm.events.festival

class FestivalNotFoundException(
    id: Long
) : RuntimeException("Festival with id $id not found")
