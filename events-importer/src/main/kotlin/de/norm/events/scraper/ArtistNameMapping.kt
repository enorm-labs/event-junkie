@file:Suppress("TooManyFunctions") // Cohesive collection of small, single-purpose artist-name mapping utilities.

package de.norm.events.scraper

import de.norm.events.event.EventType

// Artist-name resolution for scraped events: performer names from titles and support lines.
// The rules it reads live beside it: SupportLines.kt, NonArtistNames.kt, ArtistNameCleanup.kt
// and ActSplitting.kt. Event-type classification lives in EventTypeMapping.kt.

/**
 * A leading series label: one ending in "#<n>:" ("OFF THE RAILS #5: …"), or a Berlin festival
 * named before a dash ("Jazzfest Berlin – Wendy Eisenberg", #1841), or a tribute's
 * "Celebrating <artist> –" frame, or the promoter series "Indie in Town –" (#2959); the acts follow. Non-greedy,
 * and a non-blank series name is required, so "9:3" or "H2:O" is untouched.
 */
private val SERIES_PREFIX_PATTERN =
    Regex("""^.+?#\s*\d+\s*:\s*|^(?i:\p{L}+fest(?:ival)?\s+berlin\s+[-–—]\s+)|^(?i:celebrating\s+[^-–—]+?\s+[-–—]\s+)|^(?i:indie\s+in\s+town\s+[-–—]\s+)""")

/**
 * Strips a leading "<series> #<n>:" label: `"OFF THE RAILS #5: Blake Harley & Superior Motive"`
 * to `"Blake Harley & Superior Motive"`. Unchanged when none or stripping would leave nothing.
 */
fun stripSeriesPrefix(title: String): String {
    val stripped = title.trim().replaceFirst(SERIES_PREFIX_PATTERN, "").trim()
    return stripped.ifBlank { title.trim() }
}

/**
 * A leading "A night with" / "An evening with" / "Ein Abend mit" frame, stripped from a
 * title-derived headliner ("A night with GULVØSS II" to "GULVØSS II"). Title-scoped
 * ([headlinersFromTitle]); the stored title is untouched.
 */
private val ARTIST_FRAMING_PREFIX =
    Regex("""^(?:a\s+night\s+with|an\s+evening\s+with|ein\s+abend\s+mit)\s+""", RegexOption.IGNORE_CASE)

/** Strips a leading [ARTIST_FRAMING_PREFIX], keeping the input when stripping would leave nothing. */
private fun stripFramingPrefix(name: String): String {
    val stripped = name.replaceFirst(ARTIST_FRAMING_PREFIX, "").trim()
    return stripped.ifBlank { name.trim() }
}

/**
 * A leading role label that specifically marks a support billing, narrower than
 * [ROLE_LABEL_PREFIX], to decide a title segment's role before its label is stripped.
 */
val SUPPORT_ROLE_PREFIX = Regex("""^(?:$SUPPORT_LABELS)\s*:""", RegexOption.IGNORE_CASE)

/**
 * A leading role or event-format label in front of the billed act (`Support:`, `Opener:`,
 * `Record Release:`, `Listening Session:`, `Live:`), which must not become part of the name
 * (Admiralspalast stored `Support: A.A. Williams`, Loge `Record Release: Pair`, Tresor
 * `Listening Session: Drexciya - Neptune's Lair`, Neue Zukunft `Im Anschlusz: Constant Disappointment`).
 * The colon is required, unlike [ROLE_LABEL_PREFIX]: against an arbitrary title, `Support Lesbiens` and
 * `Session Victim` would be maimed.
 */
private val ARTIST_LABEL_PREFIX =
    Regex(
        """^(?:$SUPPORT_LABELS""" +
            """|listening\s+session|record\s+release|record\s+launch|album\s+release|release\s+show|live""" +
            """|im\s+anschlus[sz])\s*:\s*""",
        RegexOption.IGNORE_CASE
    )

/**
 * Strips a leading [ARTIST_LABEL_PREFIX] from an act name, the title-level counterpart of
 * [stripArtistSuffix]. Unchanged when none or stripping would leave nothing. The colon is what
 * makes it safe: without it Support Lesbiens becomes "Lesbiens".
 */
fun stripArtistPrefix(name: String): String {
    val stripped = name.trim().replaceFirst(ARTIST_LABEL_PREFIX, "").trim()
    return stripped.ifBlank { name.trim() }
}

/**
 * Turns an event title into its headliner entries: [stripSeriesPrefix], [splitHeadlinerTitle],
 * strip an "A night with …" frame and any suffix ([stripArtistSuffix]), drop
 * [isNonArtistName]. Billing order is title order; the caller appends support acts.
 *
 * @param splitOnSlash forwarded to [splitHeadlinerTitle] (false for Madame Claude).
 * @param unpackWithFrame reads a `"<night> w/ <acts>"` or `"<night> mit <acts>"` title as its
 * acts only; [withFrameActs] has why this is opt-in.
 * @param subtitle used only for the label-showcase check ([isPresenterOwnEventTitle]).
 */
@Suppress("ReturnCount") // Three guard clauses (label-led title, presenter credit, w/ frame) read better than nesting
fun headlinersFromTitle(
    rawTitle: String,
    splitOnSlash: Boolean = true,
    unpackWithFrame: Boolean = false,
    subtitle: String? = null,
    description: String? = null
): List<ScrapedArtist> {
    // The persistence boundary strips a cancellation from the title after the acts are built, so
    // `Absage: The Act` must lose the marker here or bill it (#1560), and so must `!!AUSVERKAUFT!! The Act` (#2103).
    val title = stripTitleStatusMarker(stripSoldOutMarker(rawTitle))
    // A title led by a label's own name announces that label's event; nothing in it is an act.
    if (isLedByNonArtistLabel(title)) return emptyList()
    if (isCoBrandedSeriesTitle(title)) return emptyList()
    // Before the split, which would cut `Romeo + Juliet Film in Concert` into two acts.
    if (isScoreConcertTitle(title)) return emptyList()
    // Same conclusion, reached structurally: the subtitle credits the label and the title repeats it.
    if (isPresenterOwnEventTitle(title, subtitle)) return emptyList()
    anniversaryQuotedAct(title)?.let { return it }
    if (unpackWithFrame) withFrameActs(title)?.let { return it }
    // `<act> feat. <guest>` mid-title: the guest is billed as support, the act goes on (#305).
    // A `Vorprogramm:` names the support act, so it bills like `+ Support:` (#1841).
    // The quoted show goes before the split, which would cut `"HARMONY + ECSTASY"` at the `+` (#3622).
    val (billing, guests) =
        splitFeaturedGuests(title.replace(DASHED_QUOTED_SHOW_TAIL, "").replace(VORPROGRAMM_MARKER, " + Support: "))
    presentsFrameActs(billing)?.let { return it + guests }
    coPresentedNightActs(billing)?.let { return it + guests }
    val heads =
        cutConjoinedTail(stripSeriesPrefix(billedSideOfPres(billing, splitOnSlash)))
            .flatMap { splitHeadlinerTitle(it, splitOnSlash, description) }
            .map { segment ->
                // The role is decided from the *raw* segment, before its label is stripped: a title
                // that bills "… + Support: A.A. Williams" names a support act, not a second headliner.
                val role = if (SUPPORT_ROLE_PREFIX.containsMatchIn(segment.trim())) "SUPPORT" else "HEADLINER"
                stripFramingPrefix(stripArtistPrefix(stripArtistSuffix(segment))) to role
            }.filterNot { (name, _) -> isNonArtistName(name) }
            .map { (name, role) -> ScrapedArtist(name = name, role = role, titleDerived = true) }
    // `Jazz Night ft. Flow Rea`: with the night's name dropped, the guests are the whole bill (#3160).
    return if (heads.isEmpty()) guests.map { it.copy(role = "HEADLINER") } else heads + guests
}

/**
 * The act an anniversary title quotes, or `null` when the title has no such frame (#1905). In `10 Jahre
 * "The Big Brassers" – Jubiläumskonzert & Party` the quotes mark the act and the rest names the occasion.
 * An unquoted `22 JAHRE CLASH` stays with [NON_ARTIST_EVENT_PATTERN], because that name can be the venue's own.
 */
private fun anniversaryQuotedAct(title: String): List<ScrapedArtist>? =
    ANNIVERSARY_QUOTED_ACT
        .find(title.trim())
        ?.groupValues
        ?.get(1)
        ?.let { stripArtistSuffix(it) }
        ?.let { name -> listOf(name).filterNot(::isNonArtistName).map { ScrapedArtist(name = it, role = "HEADLINER", titleDerived = true) } }

/** A leading `<n> Jahre "<act>"` or `<n> Years of "<act>"`, in straight, German or guillemet quotes. */
private val ANNIVERSARY_QUOTED_ACT =
    Regex("""^\d+\.?\s+(?:jahre|jahr|years?)(?:\s+of)?\s+["„“»]([^"„“”»«]+)["“”«]""", RegexOption.IGNORE_CASE)

private val DASHED_QUOTED_SHOW_TAIL = Regex(DASHED_QUOTED_SHOW)

/**
 * The act a `<act> - "<show>"` title bills, or none when the title has no quoted show after a dash.
 * The quoted show is the evidence that the head names a performer, for a venue that bills an act on no other signal.
 */
fun dashedQuotedShowActs(title: String): List<ScrapedArtist> = if (DASHED_QUOTED_SHOW_TAIL.containsMatchIn(title)) headlinersFromTitle(title) else emptyList()

/** The German opening-act marker inside a title: `KARAT „45 Jahre …“ Vorprogramm: Dirk Michaelis`. */
private val VORPROGRAMM_MARKER = Regex("""\s+vorprogramm\s*:\s*""", RegexOption.IGNORE_CASE)

/**
 * Cuts a dash tail that holds a conjunction, before the title is split on conjunctions (#1842).
 * The split would cut the tail in two and leave neither half in the ` - <tour>` shape that
 * [ARTIST_SUFFIX_PATTERN] strips: `Joe Jackson & Band - Hope and Fury Tour 2026` minted `Fury
 * Tour 2026`. The tail goes only when that pattern matches from the dash, or when the tail opens
 * in lowercase, which an act billed after a dash does not (`LYAPIS TRUBETSKOY - the best & new
 * songs`). A `mit`/`with` tail names performers (`Schund und Asche - mit Moritz Neumeier und Till
 * Reiners`) and stays. A shouted head before [coBilledNames] is a band billed with its members, so
 * the dash bills the head on its own (#3637): `BAD COMPANY LEGACY – Dave Colwell & Robert Hart` is
 * three acts. Any other conjoined tail stays.
 */
private fun cutConjoinedTail(title: String): List<String> {
    val dash = DASH_SEPARATOR.findAll(title).lastOrNull() ?: return listOf(title)
    val head = title.substring(0, dash.range.first).trim()
    val tail = title.substring(dash.range.last + 1).trim()
    val conjoined = head.isNotBlank() && tail.isNotBlank() && CONJUNCTION_SEPARATOR.containsMatchIn(tail)
    if (!conjoined) return listOf(title)
    val suffixFromDash = ARTIST_SUFFIX_PATTERN.find(title)?.range?.first == dash.range.first
    val performerLead = PERFORMER_LEAD.containsMatchIn(tail)
    val proseTail = tail.first().isLowerCase() && !performerLead
    val shoutedHead = head.none { it.isLowerCase() }
    return when {
        suffixFromDash || proseTail -> listOf(head)
        shoutedHead && !performerLead && coBilledNames(tail) -> listOf(head, tail)
        else -> listOf(title)
    }
}

/**
 * Whether [tail] splits into two acts or more, each in mixed case and opening every word with a capital: `Dave
 * Colwell & Robert Hart`. A shouted tail fails it (`GLITTER & GLAM`), and so do a lowercase word (`Große und kleine
 * Fragen`) and an act that opens with a digit (`2000s & 2010s Party`).
 */
private fun coBilledNames(tail: String): Boolean {
    val acts = splitSegmentOnConjunctions(tail).map { it.trim() }
    return acts.size >= 2 &&
        acts.all { act -> act.first().isUpperCase() && act.any { it.isLowerCase() } && act.split(WHITESPACE).none { it.first().isLowerCase() } }
}

/** A tail that goes on to name who performs: `mit …`, `with …`, `w/ …`, `feat. …`. */
private val PERFORMER_LEAD = Regex("""^(?:mit|with|w/|feat\.?|ft\.|featuring)\s""", RegexOption.IGNORE_CASE)

/**
 * A night named for the DJ who runs it: `<night> curated by <acts>`, `<night> hosted by <acts>`,
 * `<night> by <Person Name>` (#339). The acts after the marker are the booking.
 */
private val HOSTED_ACTS_MARKER = Regex("""^.+?\s+(?:curated\s+by|hosted\s+by)\s+(.+)$""", RegexOption.IGNORE_CASE)

/** The bare `by` form, accepted only when what follows is shaped like a person's name. */
private val BY_PERSON_MARKER = Regex("""^.+?\s+by\s+(\p{Lu}[\p{L}'.-]*(?:\s+\p{Lu}[\p{L}'.-]*){1,3})$""")

/**
 * The acts a `curated by` / `hosted by` / `by <Person>` title names, for a venue that publishes no
 * line-up for that night (#339). The marker words are a closed set and the acts follow them, which
 * is what separates this from the `PARTY` guard in [buildArtistsForEventType]: a party title is not
 * an act, but the DJ a party names as its curator is. A venue that does publish a line-up keeps it
 * — Tresor's floor host (`hosted by HARD WAX`) is a collective, not a booking, and its scraper drops
 * it — so callers use this as the fallback for an empty line-up only.
 */
fun hostedActsFromTitle(
    title: String,
    role: String = "HEADLINER"
): List<ScrapedArtist> {
    val acts = HOSTED_ACTS_MARKER.find(title.trim())?.groupValues?.get(1) ?: BY_PERSON_MARKER.find(title.trim())?.groupValues?.get(1)
    return acts
        ?.let(::splitSupportActs)
        .orEmpty()
        .map { stripArtistSuffix(it) }
        .filterNot(::isNonArtistName)
        .map { ScrapedArtist(name = it, role = role, titleDerived = true) }
}

/** The `feat.` / `featuring` / `ft.` marker between an act and its guest, mid-title. */
private val FEATURED_GUEST_MARKER = Regex("""\s+(?:feat\.?|featuring|ft\.)\s+""", RegexOption.IGNORE_CASE)

/** The title's billing before a [FEATURED_GUEST_MARKER], and the guests after it as support acts. */
private fun splitFeaturedGuests(title: String): Pair<String, List<ScrapedArtist>> {
    val marker = FEATURED_GUEST_MARKER.find(title) ?: return title to emptyList()
    val guests =
        splitSupportActs(title.substring(marker.range.last + 1))
            .map { stripArtistSuffix(it) }
            .filterNot(::isNonArtistName)
            .map { ScrapedArtist(name = it, role = "SUPPORT", titleDerived = true) }
    return title.substring(0, marker.range.first) to guests
}

/** `<X> pres. <Y>` / `<X> pres: <Y>` — the abbreviated presenter marker, with what stands on either side. */
private val PRES_MARKER = Regex("""^(.+?)\s+pres[.:]\s+(.+)$""", RegexOption.IGNORE_CASE)

/**
 * `<promoter> presents: <bill>` / `<promoter> präsentiert: <bill>` — the spelled-out marker with
 * its colon, which the abbreviated [PRES_MARKER] never reads. Schokoladen's `M:Soundtrack prsts:`
 * contractions read the same way, and so does a night of at most three words between the verb and
 * the colon, as in LARK's `Projektbau presents TOSKA: <acts>` (#3213).
 *
 * The colon is what makes the reading unambiguous where `pres.` is not: what follows it is the
 * night's bill, and what precedes it is who booked it. Gretchen and Morphine each carry a local
 * copy of this frame; this is the shared one, and the promoter half is not stored anywhere yet.
 */
private val PRESENTS_FRAME =
    Regex(
        """^.{2,60}?\s+(?:$PRESENTS_VERBS|$PRESENTS_CONTRACTIONS)(?:\s+[^\s:,]+(?:\s+[^\s:,]+){0,2})?\s*:\s*(.+)$""",
        RegexOption.IGNORE_CASE
    )

/**
 * The acts of a `<promoter> presents: <bill>` title, or `null` when the title carries no such
 * frame (#1690).
 *
 * A bill is a list, so a comma separates acts here where it does not in a headline title: Delphi
 * writes `Berlin Confidential präsentiert: Georgy Gusev, Sven Helbig, Ivan Skanavi & Deutsches
 * Kammerorchester Berlin`, which [splitHeadlinerTitle] alone reads as one 104-character act. Each
 * comma segment then goes through [splitHeadlinerTitle], which keeps `David August w/ MFO` and
 * `AC/DC` whole. Every act is a headliner: the frame says who booked the night, not who opens it.
 */
private fun presentsFrameActs(title: String): List<ScrapedArtist>? {
    val bill = PRESENTS_FRAME.find(title.trim())?.groupValues?.get(1) ?: return null
    return bill
        .split(',')
        .flatMap { splitHeadlinerTitle(it.trim()) }
        .map { stripFramingPrefix(stripArtistPrefix(stripArtistSuffix(it))) }
        .filterNot(::isNonArtistName)
        .map { ScrapedArtist(name = it, role = "HEADLINER", titleDerived = true) }
        .ifEmpty { null }
}

/**
 * `<partner> x <partner>: <night> with <acts>`: two promoters that join forces on a night name
 * its acts after `with` (#2365). Urban Spree bills `Process Party x Effetto Notte: Hall of Bats
 * with Lovataraxx, Hysteric Helen, Olgha`, and neither the promoters nor the night are acts. All
 * three parts are required, so a plain ` x ` co-bill is left to [splitCrossBilled].
 */
private val CO_PRESENTED_NIGHT = Regex("""^[^:]+?\s+x\s+[^:]+?:\s+[^:]+?\s+with\s+(.+)$""", RegexOption.IGNORE_CASE)

/** The acts of a [CO_PRESENTED_NIGHT] title, or `null` when the title has no such frame. */
private fun coPresentedNightActs(title: String): List<ScrapedArtist>? =
    CO_PRESENTED_NIGHT
        .find(title.trim())
        ?.groupValues
        ?.get(1)
        ?.let(::splitSupportActs)
        ?.map { stripArtistSuffix(it) }
        ?.filterNot(::isNonArtistName)
        ?.map { ScrapedArtist(name = it, role = "HEADLINER", titleDerived = true) }
        ?.ifEmpty { null }

/**
 * The side of a `pres.` / `pres:` title that bills the acts (#1581).
 *
 * The marker reads two ways. `hub pres. Doorman + Franco Franco` and `Unguarded pres. Jungstötter +
 * Blurrydog` name a host and its programme, so the acts are on the right; `Burnt Friedman pres:
 * Secret Rhythms` names an act and its project, so the act is on the left. What separates them is
 * the right side: a co-bill is a programme, a single name is a work. The spelled-out `presents` is
 * not handled here — a venue that bills that way splits it itself (Gretchen), and the shared rule
 * that reads a `presents` credit is [isPresenterOwnEventTitle].
 */
private fun billedSideOfPres(
    title: String,
    splitOnSlash: Boolean
): String {
    val match = PRES_MARKER.find(title.trim()) ?: return title
    val (host, programme) = match.destructured
    return if (splitHeadlinerTitle(programme, splitOnSlash).size > 1) programme else host
}

/**
 * A `"<night> w/ <acts>"` or `"<night> mit <acts>"` frame, up to and including the marker.
 * `mit` is the German spelling: SO36 bills `SADTEMBER mit TAHA, JOHNBOY M.IKARUS` (#1132), and
 * needs whitespace both sides so Mitski and Submit are untouched.
 *
 * Not a co-bill separator: across the seed every title carrying it names a night, series or
 * label on the left (`RIPPLES W/ AMINE K`, `House of Rave w/ Maceo Plex, …`), and keeping both
 * halves would mint every night name. Not universally a frame either, so unpacking is opt-in:
 * Zenner's `Analogue Foundation presents: David August w/ MFO (live)` joins two collaborating
 * artists, and Zenner anchors its own frame to a leading duration (`180 min w/ Barker`), a cue
 * no other venue shares.
 *
 * The tail is split by [splitSupportActs], since after the marker a comma delimits acts; every
 * act is a headliner. The marker must be preceded by something, so an entry opening with `w/`
 * goes to [ROLE_LABEL_PREFIX]; a tail opening with a [CONJUNCTION_TAIL_MARKERS] word is the
 * act's own backing (`Lacrimosa mit Orchester`). Both return `null`.
 */
private val WITH_FRAME_PATTERN = Regex("""^.+?(?:\bw/\s*|\smit\s+)""", RegexOption.IGNORE_CASE)

/** The acts a [WITH_FRAME_PATTERN] title bills, or `null` when the title carries no such frame. */
private fun withFrameActs(title: String): List<ScrapedArtist>? {
    val frame = WITH_FRAME_PATTERN.find(title) ?: return null
    val tail = title.substring(frame.range.last + 1)
    if (tail.trimStart().substringBefore(' ').lowercase() in CONJUNCTION_TAIL_MARKERS) return null
    val acts =
        splitSupportActs(tail)
            .map { stripFramingPrefix(stripArtistPrefix(stripArtistSuffix(it))) }
            .filterNot { isNonArtistName(it) }
            .map { ScrapedArtist(name = it, role = "HEADLINER", titleDerived = true) }
    return acts.ifEmpty { null }
}

/**
 * Builds an artist list from a headliner title and support names, the "title = headliner +
 * Support:" pattern. [supportNames] confirms the convention; placeholders ("TBA") and bare role
 * labels ("Special Guest") are filtered from the output but still serve as the signal.
 *
 * @param title the event title, one or more headliner names.
 * @param supportNames support acts; empty returns an empty list.
 * @param subtitle forwarded to [headlinersFromTitle] for the label-showcase check.
 * @param description forwarded to [headlinersFromTitle], which reads it to corroborate a comma.
 * @return headliner(s) first, then support acts in order.
 */
fun buildArtistList(
    title: String,
    supportNames: List<String>,
    subtitle: String? = null,
    description: String? = null
): List<ScrapedArtist> {
    if (supportNames.isEmpty()) return emptyList()

    val supportActs =
        supportNames
            .filterNot { isNonArtistName(it) }
            .map { ScrapedArtist(name = it, role = "SUPPORT") }

    return headlinersFromTitle(title, subtitle = subtitle, description = description) + supportActs
}

/**
 * The `"<Performer> – <Show>"` idiom every variety and comedy house bills a solo act with (#315):
 * a person-shaped head — two to four capitalised words — before a dash or colon, and a show after
 * it. Cosmic Comedy derives its `Comedy Special` acts the same way; a production title
 * (`DIE KLIMA-MONOLOGE`) has no such head and yields nothing.
 */
private val SOLO_BILL = Regex("""^(\p{Lu}[\p{L}'.-]*(?:\s+\p{Lu}[\p{L}'.-]*){1,3})\s*(?:[-–—]|:)\s+\S.*$""")

/** The solo act a show title bills, or none: a shouted head (`FOTZENSCHLEIMPOWER GEGEN RAUBTIER – …`) is a production's title, not a name. */
private fun soloBillOf(title: String): List<ScrapedArtist> =
    SOLO_BILL
        .find(stripTitleStatusMarker(title).trim())
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?.takeIf { performer -> performer.any { it.isLowerCase() } && !isNonArtistName(performer) }
        ?.let { listOf(ScrapedArtist(name = it, role = "HEADLINER", titleDerived = true)) }
        .orEmpty()
        .ifEmpty { knownSoloPerformer(title) }

/**
 * One-word stage names, lower-cased. [SOLO_BILL] needs two words, because one word before a dash
 * is far more often a production (`Hamlet – …`) than a performer.
 */
private val KNOWN_SOLO_PERFORMERS: Set<String> = setOf("fil")

/** `<Name> – `, `<Name>: ` or `<Name>'s ` at the start of a title; the possessive is Peter Edel's `FIL'S TESTSHOWS`. */
private val SOLO_PERFORMER_HEAD = Regex("""^([\p{L}]+)(?:['’]s\b|\s*(?:[-–—]|:)\s)""", RegexOption.IGNORE_CASE)

/**
 * The [KNOWN_SOLO_PERFORMERS] act a show title opens with: Mehringhof's `Fil mit Lieder of the
 * World`, Peter Edel's `FIL - MIT NEUER SHOW`. A bare title that is only the name bills it too.
 */
fun knownSoloPerformer(title: String): List<ScrapedArtist> {
    val text = stripTitleStatusMarker(title).trim()
    val name = SOLO_PERFORMER_HEAD.find(text)?.groupValues?.get(1) ?: text
    return if (name.lowercase() in KNOWN_SOLO_PERFORMERS) listOf(ScrapedArtist(name = name, role = "HEADLINER", titleDerived = true)) else emptyList()
}

/** Two to four capitalised words with lower case in them: `Till Reiners`, `Hans-Joachim Heist`; not `Lebefrau`, not `LIVE`. */
private val PERSON_NAME = Regex("""^\p{Lu}[\p{L}'.-]*(?:\s+\p{Lu}[\p{L}'.-]*){1,3}$""")

private fun isPersonShaped(name: String): Boolean = PERSON_NAME.matches(name) && name.any { it.isLowerCase() }

/** A show's own format named beside its host: `FUN FACTS mit Pinar Atalay & Live Show`. */
private val SHOW_FORMAT_PART = Regex("""\b(?:Show|Mix|Special)$""")

/** `<show> mit <names>`, the dash optional: `Schund und Asche - mit Moritz Neumeier und Till Reiners`. */
private val BILLED_WITH = Regex("""^.+?\s+(?:[-–—]\s+)?mit\s+(.+)$""")

private val BILLED_WITH_SEPARATOR = Regex(""",\s*|\s+(?:und|&)\s+""")

/**
 * The performers a show or comedy line bills after `mit`: `Schund und Asche - mit Moritz Neumeier
 * und Till Reiners` (Admiralspalast), `Qualitätspodcast mit Till Reiners und Moritz Neumeier`
 * (SO36's subtitle). Stricter than the concert frame [withFrameActs]: every part must be a
 * person's name, or the line bills nobody — `Helene Bockhorst mit Lebefrau` names a programme,
 * `Komödie mit Maria Hartmann, Heinrich Schafmeister u.a.` an open cast. A part naming the
 * show's format is dropped, not fatal.
 */
fun performersBilledWith(line: String?): List<ScrapedArtist> {
    val tail = line?.let { BILLED_WITH.find(stripTitleStatusMarker(it).trim()) }?.groupValues?.get(1) ?: return emptyList()
    val names = tail.split(BILLED_WITH_SEPARATOR).map { it.trim() }.filterNot { SHOW_FORMAT_PART.containsMatchIn(it) }
    if (names.isEmpty() || !names.all { isPersonShaped(it) && !isNonArtistName(it) }) return emptyList()
    return names.map { ScrapedArtist(name = it, role = "HEADLINER", titleDerived = true) }
}

/** A performer noun before a name, as an arena's copy introduces its act: `kommt der Comedian Till Reiners`. */
private const val PERFORMER_NOUN = """(?:Comedian|Komiker|Kabarettist|Entertainer|Zauberer|Magier)(?:in)?"""

/**
 * The solo act a bare-name show title bills when the description introduces that very name as a
 * performer: Uber Arena's `Till Reiners`, whose copy says `kommt der Comedian Till Reiners`.
 * The title alone decides nothing — `Der Kirschgarten` is person-shaped too — so the noun is the
 * evidence. The description's spelling wins over a shouted title.
 */
private fun soloActNamedInDescription(
    title: String,
    description: String?
): List<ScrapedArtist> {
    val name = stripTitleStatusMarker(title).trim()
    if (description.isNullOrBlank() || !PERSON_NAME.matches(name)) return emptyList()
    return Regex("""\b$PERFORMER_NOUN\s+(${Regex.escape(name)})\b""", RegexOption.IGNORE_CASE)
        .find(description)
        ?.groupValues
        ?.get(1)
        ?.takeIf { isPersonShaped(it) && !isNonArtistName(it) }
        ?.let { listOf(ScrapedArtist(name = it, role = "HEADLINER", titleDerived = true)) }
        .orEmpty()
}

/**
 * Builds an artist list from the source's own event type, for venues with a clean `kind` label
 * (Astra, Lido): festivals and parties extract none; a show or comedy night with no support line
 * bills its solo act via [SOLO_BILL] (#315); concerts always add the title plus the subtitle's support acts;
 * unknown falls back to [buildArtistList]. An ice show ([isIceShow]) of any type keeps only its
 * support acts: its title names a production, never a performer.
 *
 * The `FESTIVAL`/`PARTY` guard is unconditional, and narrowing it is the trap: measured across
 * the seeded database, of the party and festival titles reaching here exactly one hid a
 * recoverable act and about ninety-five would have produced a wrong one, most storing the
 * night's name verbatim as a 30–60 character "artist", and a tribute night splitting like a
 * co-bill: `Friday I'm in Love – A Tribute to Post-Punk · Dark 80s + Nick Cave` yields `Nick
 * Cave`, whose row resolves by slug onto the real one. The single recoverable case was a
 * classification defect: `PARTY_TITLE_KEYWORDS` matched a bare `club`, typing Columbiahalle's
 * `Two Door Cinema Club` as `PARTY`. The `"<night> curated by / invites / hosted by <act>"`
 * idiom is #339.
 *
 * @param unpackWithFrame forwarded to [headlinersFromTitle] for a concert.
 */

fun buildArtistsForEventType(
    title: String,
    subtitle: String?,
    eventType: String?,
    description: String? = null,
    unpackWithFrame: Boolean = false
): List<ScrapedArtist> {
    if (eventType == EventType.FESTIVAL.name || eventType == EventType.PARTY.name) return emptyList()

    val supportNames = extractSupportFromSubtitle(subtitle)
    val supportActs =
        supportNames
            .filterNot { isNonArtistName(it) }
            .map { ScrapedArtist(name = it, role = "SUPPORT") }
    if (isIceShow(title, subtitle)) return supportActs
    if ((eventType == EventType.SHOW.name || eventType == EventType.COMEDY.name) && supportNames.isEmpty()) {
        return soloBillOf(title)
            .ifEmpty { performersBilledWith(title) }
            .ifEmpty { soloActNamedInDescription(title, description) }
    }
    if (eventType != EventType.CONCERT.name) return buildArtistList(title, supportNames, subtitle, description)

    // Concert: the title carries the headliner(s) (co-bills split out), then support acts in listing order.
    // The subtitle goes in as well as being read for support: a `"<X> presents"` credit beside a
    // title that opens with `<X>` means the title is the label's night, not the act.
    return headlinersFromTitle(title, unpackWithFrame = unpackWithFrame, subtitle = subtitle, description = description) + supportActs
}
