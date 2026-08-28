# Scraping position — our own reasoning, not a legal opinion

> **No lawyer reviewed this.** It is the project's own reasoned position on why the import pipeline is defensible. It
> also records the risk we accept. [#282](https://github.com/enorm-labs/event-junkie/issues/282) asked for a qualified
> opinion. We deferred that deliberately. §6 says what would make us buy one.
>
> Related: [ADR-007 (scraping strategy)](adr/ADR-007_WEB_SCRAPING_STRATEGY.md) · [LEGAL.md](LEGAL.md) ·
> [EVENT_DATA_SOURCES.md](EVENT_DATA_SOURCES.md)

## The short version

1. **We aggregate and link back. We do not republish.** Every event carries a link to its source page.
2. **We take facts.** A title, a date, a start time, a venue and an artist list are the fields the product needs.
3. **We display the venue's description, and we hotlink the venue's image.** §3.1 and §3.6 explain why those two are
   the weakest parts of this position. **A source can now forbid either one**, and §3.1 says what we do until a source
   is reviewed.
4. **We are polite.** One entry page per source, once per day, with a delay between requests and conditional requests on
   top.
5. **A venue can ask us to stop, and we stop.** §5 is the route.
6. **The database right in §3.2 is the argument we would most likely lose.** We say so rather than hide it.

## 1. What this document is

This document records what we believe and why. It is not legal advice and no qualified person checked it. Three things
make it worth writing anyway:

- A position written down can be attacked and improved. An unwritten one cannot.
- It forces the description of the system to be accurate. §2 is checkable against the code.
- It gives a venue operator something to read that is not marketing.

**Section 2 must stay true.** If the pipeline changes and §2 does not, every argument after it describes a system that
no longer exists.

## 2. What the system actually does

| Behaviour            | Where it lives                                                         | What it does                                       |
| -------------------- | ---------------------------------------------------------------------- | -------------------------------------------------- |
| Politeness delay     | `PerHostThrottlingFilter.kt`, `ScraperProperties.politeDelayMillis`    | 200 ms minimum between requests to the same host   |
| Identifying agent    | `ScraperHttpClientConfig.kt`                                           | Names the product and links the repository         |
| Conditional requests | `HtmlFetcher`, ETag and Last-Modified on the `event_source` row        | A `304` response costs the venue almost nothing    |
| Import frequency     | `ScheduledImportService.kt`, `EventSourceEntity.importIntervalMinutes` | Once per day for each source, by default           |
| Page depth           | ADR-007 § Pagination — First Page Only                                 | The first overview page, plus its detail pages     |
| No arbitrary crawl   | Each `EventImporter` parses one known structure                        | The scraper follows no link it discovers at random |

**First page only, with one exception.** Most listings are unpaginated, or their pagination is a no-op. Where a
listing does paginate, the default is to read page one and stop, and ADR-007 records the five reasons. **LARK is the
exception.** `LarkWebsiteImporter` walks the venue's WordPress API for up to 10 pages of 100 entries. It stops early on
a short page, or on a page that reaches the past. ADR-007 permits that as a per-importer concern.

**What we store per event:** title, date, start time, venue, artist list, source URL, ticket URL, event type,
`description` and `imageUrl`.

**What the site displays: all of it, including `description` and `imageUrl`, unless the source forbids that field.**
`EventDetailView.vue` renders the full description. `EventCard.vue`, `VenueCard.vue`, `BaseDetailView.vue` and
`EventDetailView.vue` render the image, and `pageMeta.ts` puts the same URL in `og:image`.

**Two columns on `event_source` decide it, per source and per field.** `description_licence` and `image_licence` each
hold `PERMITTED`, `PROHIBITED`, `UNCLEAR` or nothing at all. The BFF withholds the matching field on `PROHIBITED` and
shows it otherwise. §3.1 explains that rule and what it accepts.

**The image is hotlinked and not copied.** The `src` attribute points at the venue's own server, so the visitor's
browser fetches it from there. §3.6 covers what follows.

**`robots.txt` is read on every request, and a disallow blocks it.** `RobotsTxtFilter` sits on the shared scraper
client, so each outbound request is checked against the host's rules. `RobotsRulesCache` reads the file once per host
per day. A forbidden URL fails the run rather than being fetched — see §3.3.

## 3. The questions, and our answers

### 3.1 Copyright in the listing itself (§§ 2, 16 UrhG)

**Our position:** the factual fields are safe. The description is not, and we display it unless the source forbids it.

A date, a start time, a door time, a venue name and an artist name are facts. German copyright protects a personal
intellectual creation under § 2 (2) UrhG. A concert title is usually the name of the act, which carries no such
creation. That part of the set is the one we are most comfortable with.

**The description is a different question.** A `description` is often a written promotional text, and such a text can
reach the threshold. We store it under § 16 UrhG, and `EventDetailView.vue` also makes it available to the public under
§ 19a UrhG. Both acts need a justification per source.

**The place to record that justification now exists.** `event_source` carries `description_licence` and `image_licence`
([#283](https://github.com/enorm-labs/event-junkie/issues/283)), each holding one of `PERMITTED`, `PROHIBITED` or
`UNCLEAR`, or nothing while the source stays unreviewed. Three more columns hold the evidence: the date of the review,
the page that was read, and the sentence that decided it.

**The rule is that only `PROHIBITED` withholds.** `UNCLEAR` displays. An unreviewed source displays.

We chose that knowingly, for one reason. **Silence from a venue is not a refusal.** To blank every source we did not
read yet would remove material that no venue objects to.

**So this stays the weakest point in the document, and it stays live.** The mechanism does not close the gap. It gives
the gap a shape, an owner per source, and a same-day remedy (§5). Until a source is reviewed we display a text we
cannot justify displaying for that source.

**What we do about it now:** §5 remains the answer for a venue that objects, and it is finer than it was. A venue that
minds only its photographs no longer has to lose its whole listing.

### 3.2 The database right (§§ 87a–87c UrhG)

**Our position:** this is the argument most likely to go against us, and we accept that risk.

A venue programme can qualify as a database under § 87a UrhG when its collection needs a substantial investment. § 87b
forbids the extraction of a substantial part. It also forbids repeated extraction of insubstantial parts when that
conflicts with the normal exploitation of the database.

The second sentence is the problem. We take a small set of fields, and we take it every day, from the same source. A
strict reading of § 87b (1) sentence 2 reaches that behaviour.

Three things weigh the other way, and none of them is decisive:

1. Many venue calendars are a by-product of running the venue. The investment goes into the events, not into collecting
   them, and § 87a asks about investment in the collection.
2. We take the parts a visitor needs to find the event, then send the visitor to the venue. That supports the normal
   exploitation of the calendar rather than substituting for it.
3. The BGH held in _Paperboy_ (I ZR 259/00, 2003) that deep linking is permissible. Short factual references were
   permissible too. The case predates much of the current framework and concerned press articles. So we treat it as
   direction rather than as authority.

**We do not claim this is settled.** A venue that objects on this ground has a real argument, which is why §5 exists.

### 3.3 Website terms and `robots.txt`

**Our position:** we honour `robots.txt`. We do not treat a terms page as binding on us.

Terms published on a website bind a visitor who agrees to them. An automated client that reads a public page agrees to
nothing, so unaccepted browsewrap forms no contract. The CJEU decision in _Ryanair v PR Aviation_ (C-30/14, 2015)
points the other way for a database outside the protection of the directive. National contract law may still apply to
such a database. We note that rather than rely on it.

`robots.txt` is different, and we treat it as binding in practice for two reasons. It is the conventional signal a site
operator uses to state what a machine may fetch. It is also a machine-readable reservation of the kind § 44b (3) UrhG
recognises for text and data mining. We do not argue that § 44b covers what we do, because our purpose includes making
the extracted facts available and not only analysing them. We honour the signal regardless.

**The check is in the code, and it is enforced.** `RobotsTxtFilter` checks every outbound scraper request against the
host's rules and writes the answer to `event_source`. A forbidden URL raises an error and the source's run fails. That
failure carries its own `robots_disallowed` tag, rather than being filed with ordinary scraper breakage.

**The claim is measured, not asserted.** A full import of every source read 65 `robots.txt` files across 81 hosts. It
found **no disallowed URL**, at a listing or at any detail page (#795). Enforcement therefore costs nothing today. What
it buys is the day a venue adds a rule. That turns into a loud failure, instead of into us fetching what they now
forbid.

Two importers also record the check in their KDoc, and both skip a disallowed path.
`BarJederVernunftWebsiteImporter.kt` leaves a disallowed iCal feed alone. `RitterButzkeWebsiteImporter.kt` never fetches
the disallowed calendar links. Those notes say **why a URL is not fetched**, which the filter does not.

### 3.4 Load on the venue's server (§ 823 BGB, § 4 Nr. 4 UWG)

**Our position:** the load we cause is too small to be an interference.

We read one overview page per source per day, with a 200 ms delay between requests to a host. Conditional requests turn
most fetches into a `304`. A venue with a weekly programme serves us less traffic than one visitor with an open browser
tab.

The BGH considered screen scraping of a competing portal under the predecessor of § 4 Nr. 4 UWG in
_Automobil-Onlinebörse_ (I ZR 224/12, 2014) and did not treat it as an unfair obstruction on those facts. We are also
not a competitor of a venue in the sense the provision addresses. We send it visitors.

**What would break this argument:** circumventing a technical block. If a venue blocks our agent, that is an answer and
we treat it as one. We do not rotate agents and we do not disguise the client.

### 3.5 Personal data in the listings

An artist name is personal data when it identifies a person, and many do.
[LEGAL.md §7.3](LEGAL.md#73-artists-are-people) carries that analysis, the Art. 6 (1) (f) balancing, and the Art. 21
objection route. This document does not repeat it.

### 3.6 Hotlinked images

**Our position:** embedding is weaker ground than the rest of this document. It also raises a privacy question we do
not answer today.

The image is not copied to our server. The page carries an `<img>` tag whose `src` is the venue's URL, so the venue
serves the file to the visitor.

**Copyright.** The CJEU treats an embedded image from a freely accessible page as no new communication to the public,
following _Svensson_ (C-466/12) and _BestWater_ (C-348/13). _VG Bild-Kunst_ (C-392/19) limits that where the rights
holder applies technical protection measures. We apply none of our own and we circumvent none, so we read the
embedding as permitted. We do not treat that reading as settled.

**Load on the venue.** Hotlinking sends every visitor request for the image to the venue's server. That is the one
place where our traffic scales with our own popularity rather than with the import schedule. It is the opposite of the
politeness §3.4 claims.

**Privacy.** The visitor's browser discloses their IP address to the venue, and we never told the visitor that.
[AGENTS.md](../AGENTS.md) treats any outbound frontend request to a domain we do not operate as a trigger to update the
privacy notice. That update did not happen, so the notice is incomplete today.

**A cache or a proxy resolves the load and the privacy point, and it weakens the copyright point.** Serving the file
ourselves is a reproduction under § 16 UrhG, which embedding avoids. The three questions therefore trade against each
other and need one decision.

## 4. The gaps we know about

Three things weaken the position above. Each has an owner or needs one.

1. **The description is displayed without a per-source justification** (§3.1). **Narrowed, not closed.** The
   per-source field and the rule that reads it now exist. An unreviewed source displays, which was the decision. The
   review itself remains, source by source, and every source is unreviewed today. Owned by
   [#283](https://github.com/enorm-labs/event-junkie/issues/283) and
   [#364](https://github.com/enorm-labs/event-junkie/issues/364).
2. **The privacy notice does not mention hotlinked images** (§3.6). The visitor's IP address reaches a venue we do not
   operate, and the notice is silent about it.
3. **Import time is an interval, not a window.** ADR-007 best-practice #7 asks for early-morning scrapes.
   `ScheduledImportService` fires when `lastImportAt` plus the interval expires, which drifts across the day.

## 5. The venue opt-out route

**A venue operator who wants out gets out.** We do not ask for a reason and we do not argue the law with them.

1. The operator writes to `hello@event-junkie.de` and names the venue.
2. We disable the source. `EventSourceEntity.enabled` is the switch and it stops the importer.
3. We remove the venue's events from the database.
4. We answer to confirm, within seven days.

**A narrower remedy exists, and it is often the one an operator wants.** An objection to the photographs alone, or to
the description alone, sets `image_licence` or `description_licence` to `PROHIBITED` on that source (§3.1). The field
stops being served and the events stay. Nobody has to lose a listing to remove one thing they mind.

A `robots.txt` rule that disallows the pages we read has the same effect and needs no message.

**The site publishes this route.** `/{locale}/legal/for-venues` carries it in both languages, German authoritative,
and the footer links it from every page. `ForVenuesView.de.vue` is the page. It also states in short form what §2 of
this document describes. A change to the importer therefore reaches a venue operator, and not only this file.

**Why this matters more than the arguments above.** Most disputes of this kind start with an annoyed operator and end
when the data comes down. A route that works, and a fast answer, resolves more risk than §3 does.

## 6. What would change this position

Any of these makes the position stale, and the first three make a qualified opinion worth buying:

- A venue objects on the database right in §3.2 and does not accept the opt-out.
- A venue objects to the display of its description or the embedding of its image, which §3.1 and §3.6 already treat as
  unresolved.
- The project republishes a venue's content instead of linking to it, which would abandon the guiding principle of
  ADR-007.
- A venue asks for a licence fee, which turns this into a commercial question.
- The project takes money for the listings. Commercial use changes the § 87b balance and the UWG analysis.
- Accounts or user submissions arrive ([#398](https://github.com/enorm-labs/event-junkie/issues/398),
  [#399](https://github.com/enorm-labs/event-junkie/issues/399)). Hosted third-party content is a different body of law.

## 7. References

- [UrhG § 2](https://www.gesetze-im-internet.de/urhg/__2.html) · [§ 16](https://www.gesetze-im-internet.de/urhg/__16.html) ·
  [§ 44b](https://www.gesetze-im-internet.de/urhg/__44b.html) · [§ 72](https://www.gesetze-im-internet.de/urhg/__72.html) ·
  [§ 87a](https://www.gesetze-im-internet.de/urhg/__87a.html) · [§ 87b](https://www.gesetze-im-internet.de/urhg/__87b.html)
- [UWG § 4](https://www.gesetze-im-internet.de/uwg_2004/__4.html) · [BGB § 823](https://www.gesetze-im-internet.de/bgb/__823.html)
- BGH _Paperboy_, I ZR 259/00 (2003) · BGH _Automobil-Onlinebörse_, I ZR 224/12 (2014) · CJEU _Ryanair v PR Aviation_, C-30/14 (2015)
