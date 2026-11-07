package de.norm.events.scraper

import de.norm.events.event.DescriptionLanguage
import de.norm.events.event.EventEntity
import de.norm.events.event.EventStatus
import de.norm.events.event.PUBLISHER_ORIGIN
import de.norm.events.event.normalizeMoneyScale
import de.norm.events.licence.SourceLicences
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.Level
import java.net.URI
import java.net.URISyntaxException

private val logger = KotlinLogging.logger {}

/** The [EventEntity] that [ScrapedEvent.toEventEntity] stores, from a row whose description is already cut. */
@Suppress("LongParameterList") // The parameters of toEventEntity, passed through.
internal fun ScrapedEvent.buildEventEntity(
    venueId: Long,
    venueSlug: String,
    eventSourceId: Long,
    existing: EventEntity?,
    slugDiscriminator: String?,
    licences: SourceLicences
): EventEntity {
    // Doors ≤ start: a source listing them the wrong way round (SO36's "Einlass: 19:30, Beginn:
    // 19:00") has transposed the labels.
    val (doors, start) = orderDoorsBeforeStart(doorsTime, startTime)
    // A venue with no status badge writes the cancellation into the title (#1493); the title decides
    // only where the scraper found nothing.
    val badge = EventStatus.parseOrDefault(status)
    val badgeStatus = if (badge == EventStatus.SCHEDULED) parseTitleStatus(title) ?: badge.name else badge.name
    // A "verlegt" badge sits on both ends of a move; which end this row is, only this boundary knows
    // (#1551).
    val notes = listOfNotNull(statusNote, title, subtitle, description, descriptionAlt)
    val relocation = notes.firstNotNullOfOrNull(::parseRelocation)
    // A cancelled badge beside "verlegt auf <later date>" is a move of the date (#4268).
    val (relocatedStatus, relocatedTo) = resolveRelocation(resolveMovedCancellation(badgeStatus, notes, eventDate), relocation, venueSlug)
    // A "verschoben" note sits on both dates of a move too; the row the show moved to takes place (#2206).
    val postponedStatus = resolvePostponement(resolveDatedRelocation(relocatedStatus, relocatedTo, notes), notes, eventDate)
    // A new date can come with a new house; on the date the show left, that is a move (#2708).
    val (storedStatus, postponedTo) = resolvePostponedMove(postponedStatus, notes, venueSlug)
    val storedTitle = storedTitle()
    // Presale dearer than the door is a misread price (#1583); named so the nightly log says which
    // source, stored as read because the fix is per parser.
    if (presaleAboveDoor(pricePresale, priceBoxOffice)) {
        logger.at(Level.WARN) {
            message = "Presale $pricePresale above box office $priceBoxOffice on '$title'"
            payload = mapOf(LogFields.EVENT_SOURCE_ID to sourceId)
        }
    }
    val storedDescription = if (licences.withholdsDescription()) null else description
    val detected = DescriptionLanguage.classify(storedDescription)
    // priceCurrency omitted: every scraped venue is in Berlin, and EventEntity defaults to "EUR".
    return EventEntity(
        // `sourceId` is the immutable identity key matching scraped events to persisted rows.
        id = existing?.id,
        sourceId = existing?.sourceId ?: sourceId,
        createdAt = existing?.createdAt,
        contentHash = existing?.contentHash,
        contentChangedAt = existing?.contentChangedAt,
        // An operator's pick (#1262): no source knows of it, so an import keeps it.
        featuredUntil = existing?.featuredUntil,
        // An operator's link to a festival (#3273), which no source knows of either.
        festivalId = existing?.festivalId,
        venueId = venueId,
        room = room,
        eventSourceId = eventSourceId,
        title = storedTitle,
        subtitle = subtitle,
        // A source that forbids its prose gets none of it stored, not merely hidden (#807): blanking on
        // read would leave the § 16 reproduction in place.
        description = storedDescription,
        descriptionWithheld = licences.withholdsDescription() && !description.isNullOrBlank(),
        // The page tells a reader and a crawler which language the text is in (ADR-026).
        descriptionLanguage = detected?.language?.code,
        descriptionLanguageConfidence = detected?.confidence,
        // OTHER, not CONCERT, when the source provided no category; then promote an under-classified
        // festival title to FESTIVAL, or recover a reading/exhibition/screening filed under the genre
        // field.
        eventType = resolvedEventType().name,
        typeIsFallback = storedTypeIsFallback(),
        status = storedStatus,
        relocatedTo = relocatedTo ?: postponedTo,
        relocatedOn = relocatedOn(storedStatus, notes, eventDate),
        slug = SlugGenerator.slugify(listOfNotNull(eventDate, venueSlug, storedTitle, slugDiscriminator).joinToString("-")),
        eventDate = eventDate,
        doorsTime = doors,
        startTime = start,
        endDate = endDate,
        // The database refuses a time without a date, so a scraper's slip surfaces here, not halfway
        // through a bulk save.
        endTime = endTime?.also { requireNotNull(endDate) { "endTime without endDate on $sourceId" } },
        imageUrl = if (licences.withholdsImage()) null else imageUrl,
        // What the licence took out is recorded as a fact, so the page can say so (#2130).
        imageWithheld = licences.withholdsImage() && !imageUrl.isNullOrBlank(),
        sourceUrl = stripClickIdentifiers(sourceUrl),
        lineupSourceUrl = lineupSourceUrl,
        ticketUrl = ticketUrl?.let(::linkWithHost)?.let(::stripClickIdentifiers),
        genre = storedGenre(),
        pricePresale = pricePresale?.normalizeMoneyScale(),
        priceBoxOffice = priceBoxOffice?.normalizeMoneyScale(),
        priceNote = priceNote,
        soldOut = storedSoldOut(storedStatus),
        // Honour an explicit scraper flag, otherwise derive from prices/note/title.
        free = free || detectFree(pricePresale, priceBoxOffice, priceNote, storedTitle)
    ).withSpokenLanguages(spokenLanguages(storedTitle))
        .withSecondLanguage(publisherAlt(storedDescription, detected), existing)
}

/**
 * [url] when it names a host. A scheme alone (`https://`), a relative path or a `mailto:` gives a Tickets button that
 * leads nowhere (#4140). The authority, not [URI.getHost], decides, so a host with an underscore stays. A link
 * [URI] refuses (a space in the path) is dropped only when nothing follows its `://`.
 */
private fun ScrapedEvent.linkWithHost(url: String): String? {
    val hasHost =
        try {
            !URI(url).rawAuthority.isNullOrBlank()
        } catch (_: URISyntaxException) {
            !EMPTY_AUTHORITY.matches(url)
        }
    if (!hasHost) {
        logger.at(Level.DEBUG) {
            message = "Ticket URL '$url' of '$title' names no host; not stored"
            payload = mapOf(LogFields.EVENT_SOURCE_ID to sourceId)
        }
    }
    return url.takeIf { hasHost }
}

private val EMPTY_AUTHORITY = Regex("""\s*[A-Za-z][A-Za-z0-9+.-]*://\s*(?:[/?#].*)?""")

/**
 * Whether the row is stored sold out. A marker in the title or subtitle is the venue's word, whether or
 * not its scraper reads it (#2103). A cancelled show sells nothing: Wix closes registration on a
 * cancellation and reports it sold out (#2710).
 */
private fun ScrapedEvent.storedSoldOut(storedStatus: String): Boolean =
    storedStatus != EventStatus.CANCELLED.name && (soldOut || hasSoldOutMarker(title) || hasSoldOutMarker(subtitle))

/**
 * The languages the venue states for this event. Read from both halves of the description even when
 * the licence withholds it: the language is a fact, not the prose.
 */
private fun ScrapedEvent.spokenLanguages(storedTitle: String): SpokenLanguages =
    detectSpokenLanguages(
        storedTitle,
        subtitle,
        listOfNotNull(description, descriptionAlt).joinToString("\n").ifEmpty { null },
        resolvedEventType(),
        houseLanguage
    )

/**
 * [ScrapedEvent.descriptionAlt] with its language, when it is stored: the description is, and the two
 * detect as German and English. Otherwise the page would offer one language twice.
 */
private fun ScrapedEvent.publisherAlt(
    storedDescription: String?,
    detected: DescriptionLanguage.Detection?
): Pair<String, DescriptionLanguage>? {
    val text = descriptionAlt?.takeIf { storedDescription != null } ?: return null
    val read = DescriptionLanguage.detect(text)?.language
    val language = read?.takeIf { detected != null && it != detected.language }
    if (language == null) {
        logger.at(Level.DEBUG) {
            message = "Second-language text of '$title' not stored: it reads as ${read?.code}, the description as ${detected?.language?.code}"
            payload = mapOf(LogFields.EVENT_SOURCE_ID to sourceId)
        }
    }
    return language?.let { text to it }
}

/**
 * The second-language columns: the venue's own text where it wrote one, else the stored machine
 * translation while the description it was made from is unchanged. The translation is derived after
 * the import commits; rebuilding it as null wiped it and bought it again every night (#1301). A
 * refusal is kept on the same terms, or the next pass buys it again (#2714).
 */
private fun EventEntity.withSecondLanguage(
    publisherAlt: Pair<String, DescriptionLanguage>?,
    existing: EventEntity?
): EventEntity {
    if (publisherAlt != null) {
        return copy(
            descriptionAlt = publisherAlt.first,
            descriptionAltLanguage = publisherAlt.second.code,
            descriptionAltOrigin = PUBLISHER_ORIGIN
        )
    }
    val translation = existing?.takeIf { description != null && it.description == description && it.descriptionAltOrigin != PUBLISHER_ORIGIN }
    return copy(
        descriptionAlt = translation?.descriptionAlt,
        descriptionAltLanguage = translation?.descriptionAltLanguage,
        descriptionAltOrigin = translation?.descriptionAltOrigin,
        descriptionAltEngine = translation?.descriptionAltEngine,
        descriptionAltSourceHash = translation?.descriptionAltSourceHash,
        descriptionAltRefusedHash = translation?.descriptionAltRefusedHash
    )
}

private fun EventEntity.withSpokenLanguages(languages: SpokenLanguages): EventEntity =
    copy(spokenLanguages = languages.spoken, subtitleLanguage = languages.subtitle)
