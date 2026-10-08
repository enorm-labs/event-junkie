package de.norm.events.importing

import de.norm.events.event.EventEntity
import de.norm.events.event.EventStatus
import de.norm.events.scraper.PUBLISHER_ORIGIN
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.ScrapedField
import de.norm.events.scraper.parseTitleStatus

/**
 * This event with every empty field the detail page fills taken from [stored], for a row whose
 * detail page yielded nothing ([ScrapedEvent.detailUnavailable]). A transient redirect would otherwise blank
 * the start time and image the last run stored (#2421). A field in [ScrapedEvent.detailPageOwns] takes the
 * stored value even when the listing filled it (#2505). The end date and time move as a pair,
 * because the database refuses a time without a date.
 */
fun ScrapedEvent.withGapsFromStored(stored: EventEntity): ScrapedEvent =
    withOwnedTextFromStored(stored)
        .withStatusAndFlagsFromStored(stored)
        .withOwnedScheduleFromStored(stored)
        .withScheduleGapsFromStored(stored)
        .withLinkAndPriceGapsFromStored(stored)
        .withPublisherAltFromStored(stored)

/** The stored second language the venue wrote, when the stored description is kept beside it (#330). */
private fun ScrapedEvent.withPublisherAltFromStored(stored: EventEntity): ScrapedEvent {
    val keepsStoredDescription = description != null && description == stored.description
    return if (descriptionAlt == null && keepsStoredDescription && stored.descriptionAltOrigin == PUBLISHER_ORIGIN) {
        copy(descriptionAlt = stored.descriptionAlt)
    } else {
        this
    }
}

/**
 * [withGapsFromStored] for the text and the type the detail page owns. A cancellation the
 * listing's title carries is read before the stored title replaces it.
 */
private fun ScrapedEvent.withOwnedTextFromStored(stored: EventEntity): ScrapedEvent {
    val titleStatus = if (ScrapedField.TITLE in detailPageOwns && status == EventStatus.SCHEDULED.name) parseTitleStatus(title) else null
    return copy(
        title = keep(ScrapedField.TITLE, title, stored.title),
        status = titleStatus ?: status,
        subtitle = keep(ScrapedField.SUBTITLE, subtitle, stored.subtitle),
        description = keep(ScrapedField.DESCRIPTION, description, stored.description),
        imageUrl = keep(ScrapedField.IMAGE, imageUrl, stored.imageUrl),
        genre = keep(ScrapedField.GENRE, genre, stored.genre),
        eventType = keep(ScrapedField.EVENT_TYPE, eventType, stored.eventType),
        typeIsFallback = keep(ScrapedField.EVENT_TYPE, typeIsFallback, stored.typeIsFallback)
    )
}

/**
 * [withGapsFromStored] for the status and the sold-out and free flags, which a listing states only
 * when it has a cue: `SCHEDULED` and `false` are its silence (#2542). A cancellation in the title is the
 * listing's word. Free yields to a listing price. `RELOCATED` stays out: its destination is stored
 * beside it, and this row cannot carry it.
 */
private fun ScrapedEvent.withStatusAndFlagsFromStored(stored: EventEntity): ScrapedEvent {
    val silent = status == EventStatus.SCHEDULED.name && parseTitleStatus(title) == null
    val keepsStatus = silent && stored.status in REFILLED_STATUSES
    return copy(
        status = if (keepsStatus) stored.status else status,
        soldOut = soldOut || stored.soldOut,
        free = free || (stored.free && pricePresale == null && priceBoxOffice == null)
    )
}

/** The stored statuses a silent listing keeps. */
private val REFILLED_STATUSES = setOf(EventStatus.CANCELLED.name, EventStatus.POSTPONED.name)

/** [withGapsFromStored] for the dates, the start and the prices the detail page owns. Each pair moves as one. */
private fun ScrapedEvent.withOwnedScheduleFromStored(stored: EventEntity): ScrapedEvent {
    val run = ScrapedField.RUN_DATES in detailPageOwns
    val prices = ScrapedField.PRICES in detailPageOwns && (stored.pricePresale != null || stored.priceBoxOffice != null)
    return copy(
        startTime = keep(ScrapedField.START_TIME, startTime, stored.startTime),
        eventDate = if (run) stored.eventDate else eventDate,
        endDate = if (run) stored.endDate else endDate,
        endTime = if (run) stored.endTime else endTime,
        pricePresale = if (prices) stored.pricePresale else pricePresale,
        priceBoxOffice = if (prices) stored.priceBoxOffice else priceBoxOffice,
        priceNote = if (prices) stored.priceNote else priceNote
    )
}

/** The stored value where the detail page owns [field] and one is stored; the listing's otherwise. */
private fun <T> ScrapedEvent.keep(
    field: ScrapedField,
    listing: T,
    stored: T?
): T = if (field in detailPageOwns) stored ?: listing else listing

/** [withGapsFromStored] for the text, the times and the room. */
private fun ScrapedEvent.withScheduleGapsFromStored(stored: EventEntity): ScrapedEvent {
    val (end, endAt) = if (endDate == null) stored.endDate to stored.endTime else endDate to endTime
    return copy(
        subtitle = subtitle ?: stored.subtitle,
        description = description ?: stored.description,
        doorsTime = doorsTime ?: stored.doorsTime,
        startTime = startTime ?: stored.startTime,
        endDate = end,
        endTime = endAt,
        room = room ?: stored.room
    )
}

/** [withGapsFromStored] for the image, the links, the genre and the prices. */
private fun ScrapedEvent.withLinkAndPriceGapsFromStored(stored: EventEntity): ScrapedEvent =
    copy(
        imageUrl = imageUrl ?: stored.imageUrl,
        lineupSourceUrl = lineupSourceUrl ?: stored.lineupSourceUrl,
        ticketUrl = ticketUrl ?: stored.ticketUrl,
        genre = genre ?: stored.genre,
        pricePresale = pricePresale ?: stored.pricePresale,
        priceBoxOffice = priceBoxOffice ?: stored.priceBoxOffice,
        priceNote = priceNote ?: stored.priceNote
    )
