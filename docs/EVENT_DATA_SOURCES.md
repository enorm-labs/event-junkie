# Event Data Sources — Berlin

Overview of all venues, clubs, and promoters whose websites are potential sources for importing event data. Sources are grouped by **import status** so the
remaining work is visible at a glance.

**This document answers *which venues*. For *which kinds of event*, see [EVENT_SCOPE.md](EVENT_SCOPE.md)** — the standing reference for what is in scope, what
is deliberately excluded (sport, participation formats, trade fairs, classical) and which coverage questions are still open. Several rows below sit in *Blocked*
on a scope decision rather than a technical one; that document is where those decisions are recorded. The **Comment** column records what matters for building
or maintaining an importer — the platform, where the data lives, and the parsing quirks. For an implemented importer, its KDoc and scraper tests are the
authoritative field mapping; defects worth repairing live in
the [issue tracker](https://github.com/enorm-labs/event-checker/issues).

| Status                              | Meaning                                                                              | Count |
|-------------------------------------|--------------------------------------------------------------------------------------|------:|
| ✅ [Imported](#-imported)           | Importer implemented and scheduled                                                   |    86 |
| 🔨 [Ready](#-ready-to-implement)    | Website analyzed, listings are scrapable — these are the next importers to build     |     3 |
| ⛔ [Blocked](#-blocked--deferred)   | Website analyzed, but no usable listings (no programme page, JS-only, or too sparse) |    87 |
| ❓ [Unanalyzed](#-not-analyzed-yet) | No URL recorded yet — website still needs a first look                               |     0 |

"Website analyzed" also means the [data model](DATA_MODEL.md) was checked against that source — to date no source has required a schema change.

## ✅ Imported

| Name                             | URL                                                         | Type         | Comment                                               |
|----------------------------------|-------------------------------------------------------------|--------------|-------------------------------------------------------|
| ÆDEN                             | https://aedenberlin.com/                                    | Techno Club  | WordPress; /events → month pages; no prices           |
| Admiralspalast                   | https://www.admiralspalast.theater/                         | Theater      | Contao; one event per performance row; no prices      |
| Alte Kantine Kulturbrauerei      | https://alte-kantine.eu/                                    | Concert Hall |                                                       |
| AMT                              | https://www.club-amt.berlin                                 | Techno Club  | Webflow; /events → month pages                        |
| Arcanoa                          | https://www.ssi-media.com/arcanoa/veranst.htm               | Bar          | 1990s HTML; title/date only; year from weekday        |
| arkaoda                          | https://berlin.arkaoda.com/?/default/program                | Bar          | PHP router; only "Konser" typed; RA link in prose     |
| Astra Kulturhaus                 | https://www.astra-berlin.de/                                | Concert Hall | schema.org `MusicEvent`; presale + door prices        |
| Badehaus                         | https://badehaus-berlin.com/                                | Club         | "AUSVERKAUFT"/"VERLEGT" labels; ticket + FB links     |
| Bar jeder Vernunft               | https://www.bar-jeder-vernunft.de/de/programm/kalender.html | Bar          | Neos; per-date JSON-LD; one show page per run         |
| Berghain / Panorama Bar          | https://www.berghain.berlin/de/program/                     | Techno Club  | Server-rendered; list + detail                        |
| Bi Nuu                           | https://binuu.de/                                           | Club         | No genre or prices on site; only via ticket link      |
| Cassiopeia                       | https://cassiopeia-berlin.de/                               | Club         | Webflow; genre tags, sold-out / cancelled badges      |
| Clash Club                       | https://clash-berlin.de/                                    | Club         | WordPress; sparse — no times, prices or text          |
| Club der Visionäre               | https://clubdervisionaere.com/programm                      | Techno Club  | WordPress; one listing, 3 rooms by CSS class          |
| Club OST                         | https://clubost.de/                                         | Techno Club  | Django; homepage is the programme; RA tickets         |
| Colosseum                        | https://www.colosseumberlin.com/event                       | Concert Hall | Wix Events warmup JSON; external shops feign sold-out |
| Columbia Theater                 | https://columbia-theater.de/                                | Concert Hall | WordPress; date in slug; status via `data-*` flag     |
| Columbiahalle                    | https://www.columbiahalle.berlin/veranstaltungen.html       | Concert Hall | Contao; one page, month headings carry the year       |
| Cosmic Comedy Club               | https://comedyclubberlin.com/wp-json/tribe/events/v1/events | Comedy Club  | The Events Calendar REST API; cursor-paged; no prices |
| Crack Bellmer                    | https://www.crackbellmer.de/program/this-month              | Bar          | Webflow; month tabs filter one list; no prices        |
| Der Weiße Hase                   | https://derweissehase.club/events                           | Club         | Contao; invalid `<p>` nesting; RA ticket links        |
| Duncker Club                     | https://www.dunckerclub.de/                                 | Club         |                                                       |
| Eschschloraque Rümschrümp        | https://www.eschschloraque.de/                              | Bar          | Drupal 7; front page = full nodes; RDFa datetimes     |
| Festsaal Kreuzberg               | https://festsaal-kreuzberg.de/de                            | Concert Hall | Nuxt/Wagtail SSR; `ld+json` empty; no prices          |
| Frannz Club                      | https://frannz.eu/                                          | Club         |                                                       |
| gART.n                           | https://www.gartn.xyz/                                      | Techno Club  | Carrd one-pager; year from weekday; no prices         |
| Gärten der Welt                  | https://www.gaertenderwelt.de/events/veranstaltungen/       | Open Air     | TYPO3 events2; paged; park activities excluded        |
| Golden Gate                      | https://goldengate-berlin.de/                               | Techno Club  | Elementor; current Thu–Sat block only; door-only      |
| Gretchen                         | https://www.gretchen-club.de/                               | Club         |                                                       |
| Havanna                          | https://www.havanna-berlin.de/                              | Club         | Undated weekly nights; occurrences derived            |
| Heideglühen                      | https://heidegluehen.berlin/monatsvorschau/                 | Techno Club  | One month at a time; DJ lineup on /aktuell/           |
| Heimathafen Neukölln             | https://heimathafen-neukoelln.de/                           | Concert Hall | WP REST + ACF; one post, many dated performances      |
| Hole 44                          | https://hole-berlin.de/                                     | Concert Hall | Events-Manager; "Abgesagt!" / "VERLEGT!" labels       |
| Humboldthain Club                | https://www.humboldthain.com/                               | Techno Club  | Elfsight widget API; weekly night expanded            |
| Huxleys Neue Welt                | https://huxleysneuewelt.de/events                           | Concert Hall | Events-Manager; ISO slug date; genre/promoter tags    |
| Junction Bar                     | https://www.junction-bar.de/                                | Bar          | Static monthly pages; show times vary by weekday      |
| Kantine am Berghain              | https://www.berghain.berlin/de/program/kantine-am-berghain/ | Concert Hall | Shares BERGHAIN importer                              |
| Kater                            | https://www.katerclub.de/                                   | Techno Club  | Homepage programme; ___ floor rules mark lineups      |
| Klunkerkranich                   | https://klunkerkranich.org/events/                          | Bar          | WordPress; ISO date in slug; ~10-day horizon          |
| Kulturhaus Insel Berlin          | https://www.inselberlin.de/                                 | Concert Hall | Gatsby static-query JSON; times in the blurb          |
| Kulturhaus Peter Edel            | https://www.peteredel.de/events/                            | Concert Hall | Umbraco grid; month heading carries the year          |
| LARK                             | https://larkberlin.com/events/                              | Club         | WP REST + ACF; post date is the event date            |
| Lido                             | https://www.lido-berlin.de/                                 | Concert Hall | Clean slugs; doors + start; "Ausverkauft" badge       |
| Loge                             | https://www.loge-berlin.org/                                | Club         | Wix; tickets on-site; support via "+" in title        |
| MAAYA                            | https://maaya.de/                                           | Club         | Elementor home page; every time written "pm"          |
| Madame Claude                    | https://madameclaude.de/                                    | Bar          | WordPress `event` REST API (ACF)                      |
| Matrix Club Berlin               | https://www.matrix-berlin.de/                               | Club         | WordPress; month pages walked; DJs + door prices      |
| Max-Schmeling-Halle              | https://www.velomax.de/events                               | Arena        | Shared VELOMAX listing; no sport imported             |
| Maxxim Club                      | https://www.maxxim-berlin.de/partys                         | Club         | Wix Events warmup JSON; UTC dates; prices inline      |
| Metropol                         | https://metropol-berlin.de/events                           | Concert Hall | Events-Manager list + detail; no prices; "Verlegt"    |
| migas                            | https://migas.berlin/program/                               | Bar          | WordPress; per-event modal; lazy imgs; page 1 only    |
| Mikropol                         | https://mikropol-berlin.de/                                 | Club         | Events-Manager list + detail; "verlegt in den …"      |
| Modus Berlin                     | https://modus-berlin.de/events                              | Club         | List + detail; rendered date wins over stale slug     |
| Monarch                          | https://www.kottimonarch.de/                                | Bar          | PHP /programm.php; type + status inline in title      |
| Monster Ronson's Ichiban Karaoke | https://www.karaokemonster.de/                              | Bar          | Webflow; ~12-day window; banded prices; closure cards |
| Morphine Raum                    | http://www.morphinerecords.com/events                       | Club         | Hand-coded Kirby; ARCHIVE row skipped; price ranges   |
| MS Hoppetosse                    | https://hoppetosse.berlin/                                  | Techno Club  | Shares the CdV listing; winter location only          |
| Neue Zukunft                     | https://neue-zukunft.org/                                   | Club         | Elfsight Event Calendar widget API                    |
| OHM                              | https://ohmberlin.com/                                      | Techno Club  | Year-less dd/MM; only 1–3 nights listed at a time     |
| Panke Culture                    | https://www.pankeculture.com/programme/                     | Club         | WordPress/Divi; upcoming list only; no event pages    |
| Parkbühne Wuhlheide              | https://www.wuhlheide.de/programm                           | Open Air     | October CMS; ISO date in URL; seasonal, sold-out      |
| Privatclub                       | https://privatclub-berlin.de/                               | Club         | Rich detail pages; genre, presale + AK prices         |
| Quasimodo                        | https://quasimodo.club/events                               | Club         | Events-Manager; .club domain; genre tags + prices     |
| Renate                           | https://www.renate.cc/                                      | Techno Club  | Homepage programme; per-floor lineups, no times       |
| Ritter Butzke                    | https://club.ritterbutzke.com/events                        | Techno Club  | Modus codebase, own template; stale slug dates        |
| Roadrunner's Paradise            | http://www.roadrunners-paradise.de/                         | Bar          | Retro HTML; rich data; year missing on some dates     |
| Säälchen                         | https://www.holzmarkt.com/kalender                          | Concert Hall | Drupal; shared calendar filtered by location          |
| Schokoladen                      | https://www.schokoladen-mitte.de/                           | Club         | Laravel; anchor-based events; genre inside title      |
| silent green                     | https://www.silent-green.net/programm                       | Concert Hall | TYPO3 news; month walk; a run listed per open day     |
| SO36                             | https://www.so36.com/tickets                                | Club         | Cookie wall bypassed via Ticket-Toaster shop          |
| Soda Club                        | https://www.soda-berlin.de/events                           | Club         | disco2app CMS; `MusicEvent` JSON-LD on details        |
| Sonnenraum                       | https://clubdervisionaere.com/programm                      | Club         | Shares the CdV listing; Monday live residency         |
| Supamolly                        | https://www.supamolly.de/?p=programm                        | Club         | Retro PHP; row id is the date stamp; no prices        |
| Tempodrom                        | https://www.tempodrom.de/programm-und-tickets/              | Concert Hall | schema.org `Event` JSON-LD; whole programme           |
| Theater im Delphi                | https://theater-im-delphi.de/programm/                      | Concert Hall | One row per performance; prices only in a leak        |
| Tresor                           | https://tresorberlin.com/club/events/                       | Techno Club  | WordPress; floor-grouped lineup; detail pages         |
| Uber Arena                       | https://www.uber-arena.de/events/all                        | Arena        | AEG CMS; list + detail; no sport imported             |
| Uber Eats Music Hall             | https://www.uber-eats-music-hall.de/events/all              | Concert Hall | Shares the Uber Arena parsers; month names, no cats   |
| UFO im Velodrom                  | https://www.velomax.de/events                               | Concert Hall | Shares the VELOMAX listing                            |
| Urania                           | https://www.urania.de/kalender/                             | Concert Hall | One house; no source attributes an event to a hall    |
| Urban Spree                      | https://www.urbanspree.com/program/                         | Club         | MODX; listing descending + paginated; walks pages     |
| Velodrom                         | https://www.velomax.de/events                               | Arena        | Shares the VELOMAX listing; Microdata details         |
| VOID Club                        | https://www.void-club.de/                                   | Techno Club  | Hand-coded Bootstrap; year from weekday; 2 rooms      |
| Wild at Heart                    | https://www.wildatheartberlin.de/                           | Bar          | Retro frameset; concerts.php; year from weekday       |
| Zenner                           | https://zenner.berlin/programm                              | Club         | Gatsby/Sanity page-data JSON; UTC dates; archive      |
| Zitadelle                        | https://citadel-music-festival.de/events                    | Open Air     | Festival site; WordPress/EM; summer season only       |

85 importer classes cover 86 sources: only Kantine am Berghain has no class of its own, sharing the Berghain importer outright. Three other groups share a
*listing and parser* while keeping one thin `@Component` per venue, so they do not reduce the count — Club der Visionäre, Sonnenraum and MS Hoppetosse; the
three Velomax halls; and Uber Arena with the Uber Eats Music Hall.

## 🔨 Ready to implement

Analyzed and scrapable — the candidates for the next `/scaffold-importer` runs. **Priority** reflects data richness and effort, not venue importance.

**Refilled on 5 August 2026 by analysing the last 48 [Unanalyzed](#-not-analyzed-yet) rows** — the remainder of the 4 August sweep, i.e. the venues whose
recorded URL was an Instagram or Facebook page, or nothing at all. Every row was opened; where the recorded URL was social-only or missing, the venue's own
domain was searched for first, which turned up twelve sites this document did not have. 10 rows landed here and 38 in [Blocked](#-blocked--deferred) — a 21 %
hit rate against the 41 % of the RA top-22 batch, which is what a table of social-first venues should be expected to yield. As before, every entry here was
confirmed by fetching the raw HTML or JSON and reading the events out of it, with no headless browser, per
[ADR-007](adr/ADR-007_WEB_SCRAPING_STRATEGY.md). The [Unanalyzed](#-not-analyzed-yet) table is now empty, so refilling this one means finding new candidates,
not picking from what is already recorded.

**The RA event count turned out to be a poor priority signal, and the promoter listings a good one.** Insel der Jugend was recorded with 2 RA events and
publishes 39 upcoming on its own site; Der Weiße Hase's 17 understate a listing that runs two months out with full DJ lineups. The three richest finds of this
batch — Kulturhaus Peter Edel, Colosseum and Gärten der Welt — carried no RA count at all and reached this document only through Loft, Puschen and Landstreicher
Konzerte. In the other direction, DNA. CLUB's 23 RA events appear nowhere in the venue's own calendar. Weight a promoter mention at least as heavily as an RA
count when the next batch is prioritised.

| Name               | URL                                    | Type        | Priority | Comment                                                     |
|--------------------|----------------------------------------|-------------|----------|-------------------------------------------------------------|
| Fitzroy            | https://fitzroy-berlin.de/events/      | Club        | Medium   | WP REST `event` + ACF — the Madame Claude / LARK codebase   |
| KAOS Berlin        | https://kaosberlin.de/veranstaltungen/ | Techno Club | Low      | The Events Calendar REST API, as Cosmic Comedy; 4 upcoming  |
| DSTRKT Club Berlin | https://www.dstrkt.de/                 | Club        | Low      | Wix one-pager; 2 dated events, which is the whole programme |

One of these needs a decision made once, not per event. **Fitzroy** is on its summer break: the ACF API holds a dense July programme and resumes on 12
September, so only 2 events are upcoming today and a fixture captured now would be unrepresentative — scaffold it in September, when the listing is
representative again.

The third such decision, **Gärten der Welt**'s, was made when it was [imported](#-imported) on 6 August 2026, and is the precedent for the next park- or
campus-like source: the row's category decides whether it is programme at all. Its guided tours, workshops, yoga sessions and handicraft afternoons — 28 of the
41 upcoming rows — are park activities rather than a stage programme, so they are excluded and the remaining 13 are imported. The rule lives in one predicate,
`isProgrammeCategory`, which is where to revisit it.

*A theater, comedy or arena-scale room is in scope, not just live-music clubs. Bar jeder Vernunft set that precedent — its programme is imported, with the
venue's own genre deciding whether a night is a concert or a staged show. **Comedy clubs and theatres were confirmed in scope on 2026-08-08**
([EVENT_SCOPE.md §5](EVENT_SCOPE.md)), so a venue of either kind sitting in [Blocked](#-blocked--deferred) on that question can be moved here and scaffolded
like any other source. That precedent still does **not** extend to classical concerts and orchestras: those are **deferred, not rejected** — the data shape
differs (orchestra plus conductor plus soloists rather than a headliner with support), so `ArtistRole` and the genre vocabulary must be extended first.*

## ⛔ Blocked / deferred

Analyzed, but there is nothing worth importing today. Revisit when the blocker changes — a redesigned website, adopting a headless browser (deferred
per [ADR-007](adr/ADR-007_WEB_SCRAPING_STRATEGY.md)), or applying the Havanna-style derived-occurrence approach to undated recurring nights.

**Last re-checked 3 August 2026**, every entry. Two came out of it: Panke Culture, which now publishes a dated programme and has since been
[imported](#-imported), and the RBB Sendesaal, whose concerts sit in a ROC calendar that turned out to be server-rendered and venue-attributed. The Sendesaal is
back here as of 4 August — **not on scraping, which works, but on scope**: it is an orchestral house. As of 2026-08-08 that question has an answer, and the
answer is *not yet*: classical is **wanted but deferred** until `ArtistRole` and the genre vocabulary can represent an orchestra with a conductor and soloists
([EVENT_SCOPE.md §5](EVENT_SCOPE.md)). Importing it before then would mean flattening its programme into headliner-plus-support, which is wrong in a way that is
expensive to unpick later — so it stays here on purpose, not through neglect. Answer that question and the importer is a short job; the ROC calendar is
server-rendered and attributes each concert to a venue, so
`.ConcertListItem-location` is the only filter needed. Five entries had their blocker *change* without unblocking, which is worth knowing before anyone spends
effort on them:

- **Fluxbau** and **The Pearl** are no longer JS-only — both render their programmes server-side now. Adopting a headless browser would not help either: Fluxbau
  publishes 2 dated events beside undated weekly series, and The Pearl exactly one. They are thin-programme problems now, not rendering ones.
- **Arena Berlin** moved to The Events Calendar, so scraping it would be trivial — but all 5 entries are trade fairs (deGUT, BUCHBERLIN, Einstieg Berlin). The
  blocker was never the markup.
- **Prachtwerk** gained a Programm page that is empty (Squarespace reports `itemCount: 0`). Its gigs are real but reach the web only through Loft's listing,
  which names Prachtwerk more often than any other house.
- **Loft** was blocked on thin, year-less dates; its redesign turned it into a full cross-venue promoter listing, so it now shares the promoter blocker below.

**Artliners Berlin**'s domain stopped resolving altogether. Bohnengold, OXI and Zuckerzauber still redirect to Facebook or Instagram — their HTTPS is broken, so
they answer only over `http://`.

**Promoter sources are deferred on a model limitation, not a scraping one.** Puschen, Trinity Music, Landstreicher Booking, Landstreicher Konzerte and — since
its 2026 redesign — Loft all publish clean, well-structured listings that name the venue per event: Puschen's 35 upcoming shows are spread over ~20 houses,
Loft's 135 over about the same. But an event's venue comes from its `event_source` row (`EventUpsertService.upsertAndCleanup(events, venueId, …)`), one venue
per source, so a promoter's events cannot be attached to the houses they actually play. Importing one today would file every show under a pseudo-venue *and*
duplicate what the venues' own importers already hold — ~30 of Puschen's 35 are at venues already imported. Unblocking them means resolving a venue per event
and de-duplicating against the venue-level sources; until then the promoter data reaches us anyway, as the `promoter` field on the venues' own events.

**A handful of tickets is not a programme.** Sisyphos is the case to reason from: its only web presence is a Shopify merch shop, whose `/pages/tickets`
carried 3 ticketed nights of a single recurring series (`generationS`, one per month) beside the T-shirts, while the club actually runs a programme every
weekend. Importing those 3 would not be a thin-but-truthful import like OHM's — where the venue publishes its real programme on a short rolling horizon — it
would present one series per month as if it were the whole programme. The bar is whether the source reflects what the venue is doing, not whether it yields a
non-zero count.

**The 4 August 2026 analysis of the RA candidates put 13 rows here**, against 9 that reached [Ready](#-ready-to-implement). They are worth reading as a group,
because the failure modes repeat and none of them is "the venue is too small":

- **The busiest venue on RA has no website at all.** Minimal Bar tops the Berlin listing with 58 events, and `minimal-berlin.de` 303-redirects to
  `minimal-berlin.geo.io` — a generic geo.io business-directory page with a stock bar photo and no programme. This is the sharpest case for RA as a source:
  the club's entire published programme exists only there.
- **Squarespace accounts for three of the thirteen.** Bar Neun, Unkompress and Weekend all serve a large page whose event content is client-side only — Bar
  Neun's 1.1 MB of HTML yields no event text at all. Prachtwerk above is the same story.
- **Two sites hand the programme back to RA.** Bulbul Berlin's "Program" button links to `ra.co/clubs/175191`, and its own page carries opening hours plus
  "Special dates (Check: RA)". VOID Club, by contrast, links to RA *for tickets* while still listing the events itself — which is why it is now
  [imported](#-imported).
- **A blog of past parties is not a programme.** Hafenbar Berlin server-renders 61 dated items, all of them write-ups of events that already happened (June,
  May, April 2026); `/events/` and `/veranstaltungen/` both 404.
- **Neue Nationalgalerie repeats the Hamburger Bahnhof result exactly** — the shared SMB TYPO3 calendar renders cleanly and is richly dated, but every entry is
  a Workshop, Gespräch or Öffentliche Führung. Its 11 RA events are concert bookings that never reach the museum's own calendar.

**The 5 August 2026 analysis of the last 48 rows put 38 here**, against 10 that reached [Ready](#-ready-to-implement). These were the social-first leftovers of
the RA sweep, so the outcome is unsurprising, but the failure modes are worth naming because they are cheap to recognise before spending time:

- **Ten venues have no website at all**, only Instagram, Facebook or an RA club page: Haus der Visionäre, Atemporal, Prisma, Mena Berlin, Phantom Bar,
  Containerhafen, ROSA, Rosie's Bar, Süss war gestern and RAW-Gelände. Searching for an own domain was still worth it everywhere else — it turned up twelve
  venue sites this document did not have, of which exactly one, Der Weiße Hase, carries a live programme, plus Backsteinboot's, which is real but a month
  behind.
- **Four recorded domains have died since the sweep.** `bredouille-bar.com` no longer resolves, `tausendberlin.de` is parked and for sale,
  `kulturbrauerei-berlin.de` answers 523 from Cloudflare, and Wendel's `nstp.de` serves plain HTTP only — its TLS handshake fails outright.
- **A site is not a listing.** 8MM, YSY, FOUND, Golden Flamingo, Coco Boule, Atelier Rooftop, Emma Pea and Beach Neukölln all render fine and publish no events;
  Marmorbar's Wix Events widget says "No events at the moment" in as many words, and Funkhaus Berlin's EVENTS page is an archive that stops in 2019.
- **Two listings are simply behind.** The Door Club's weekly grid ends on 1 August and Backsteinboot's Cargo programme still shows July, while both have August
  dates on RA. Both are well-structured and worth re-checking rather than rewriting.
- **Three calendars describe something other than a programme.** KINDL renders tours and exhibition openings, Genezarethkirche a parish calendar of services and
  choir rehearsals, and Spielbank Berlin casino promotions — the Hamburger Bahnhof result, three times over. **DNA. CLUB** is the same shape with an extra
  twist: its events do live in a machine-readable Elfsight calendar, the format already imported for Neue Zukunft and Humboldthain, but that calendar spans 28
  locations including hotels and other clubs, and holds dance classes and workshops rather than the 23 club nights RA lists for the venue.
- **Birgit & Bier and Œlgarten publish only undated weekly series** — "Every Thursday Morgan's Dragshow", a Sangria Friday running July to September with an
  empty occurrence list. That is the Paloma problem, and the Havanna-style derived occurrence is what would fix it.

Two side findings. **Minimal Bar** — the venue this document called the sharpest argument for importing RA — does have an operator site after all,
`birgit.club/minimal`, but it carries a stale Christmas note and no programme, so the row stands. And **Rough Trade** answers 403 to curl while serving the same
page to other clients, so its blocker is the empty Next.js payload rather than the WAF; a 403 is still not evidence that a site is unscrapable.

| Name                             | URL                                            | Type         | Blocker                                                   | Unblocked by               |
|----------------------------------|------------------------------------------------|--------------|-----------------------------------------------------------|----------------------------|
| DNA. CLUB — urban Space          | https://www.dna-artclub.com/events             | Club         | Elfsight calendar is cross-location classes and workshops | RA as a source             |
| Giri                             | https://giri.berlin/                           | Bar          | Programme calendar is empty in HTML; RSVP goes to RA      | RA as a source             |
| Birgit (Birgit & Bier)           | https://www.birgit.club/                       | Techno Club  | Wix one-pager; only undated weekly series                 | Havanna-style occurrences  |
| Prisma                           | —                                              | Club         | No own site; Instagram and RA only                        | RA as a source             |
| Spielbank Berlin                 | https://www.spielbank-berlin.de                | Other        | Casino promotions; `/events` 404s                         | Site change / RA           |
| Haus der Visionäre               | —                                              | Bar          | No own site; not in the CdV listing either                | RA as a source             |
| 8MM                              | https://www.8mmbar.de/program                  | Bar          | Squarespace; the Program page carries no events           | Site change                |
| Marmorbar                        | https://www.marmorbar.com/en                   | Bar          | Wix Events reports "No events at the moment"              | Site change                |
| Ikii                             | https://ikiiberlin.com/                        | Bar          | GoDaddy splash page; no programme                         | Site change / RA           |
| Atemporal                        | —                                              | Club         | No own site; RA and DICE only                             | RA as a source             |
| Süss war gestern                 | —                                              | Bar          | Facebook only; the `.de` domain is an unrelated blog      | Site change                |
| Wendel                           | http://www.nstp.de/nstp/frameset-wendel.htm    | Bar          | Café one-pager, no programme; HTTPS handshake fails       | Site change                |
| Funkhaus Berlin                  | https://www.funkhaus-berlin.net/               | Concert Hall | Blogger site; the events archive ends in 2019             | Site change / promoter     |
| Beate Uwe                        | https://beate-uwe.de/                          | Club         | Elementor one-pager; 1 event in the summer break          | More events / re-check     |
| Jonny Knüppel                    | https://jonnyknueppel.de/                      | Bar          | Imprint-only page                                         | Site change                |
| Backsteinboot                    | https://backsteinboot.org/                     | Club         | Cargo; the programme page still shows July                | Site change / re-check     |
| Œlgarten                         | https://www.oelgarten.com/en                   | Open Air     | Wix Events; two open-ended weekly series, no occurrences  | Havanna-style occurrences  |
| Rough Trade Berlin               | https://www.roughtrade.com/en-de/events/berlin | Other        | Next.js store; the events page carries no event data      | Headless browser           |
| Rosie's Bar                      | —                                              | Bar          | Bar of The Circus Hostel; no listing of its own           | RA as a source             |
| Kulturbrauerei Open Air          | https://www.kulturbrauerei.de/                 | Open Air     | Grounds site links out to each house; no own programme    | Covered by the houses      |
| Tausend                          | http://www.tausendberlin.de/                   | Bar          | Domain parked and offered for sale                        | New site                   |
| Emma Pea                         | https://emmapea.com/                           | Bar          | Restaurant site; no programme                             | Site change                |
| HÖR Berlin                       | https://hoer.berlin/                           | Other        | Shopify merch shop; its "events" are broadcasts           | Scope decision             |
| Bredouille                       | —                                              | Bar          | Domain no longer resolves                                 | New site                   |
| Mena Berlin                      | —                                              | Club         | No own site; Facebook and RA only                         | RA as a source             |
| Atelier Rooftop                  | https://atelierrooftop.de/                     | Club         | Rental one-pager; no programme                            | Site change / promoter     |
| Coco Boule                       | https://cocoboule.com/                         | Bar          | One-pager; no dated content                               | Site change                |
| YSY                              | https://www.ysyberlin.de/calendar              | Club         | `/calendar` says to follow Instagram instead              | Site change                |
| Phantom Bar Berlin               | —                                              | Bar          | No own site; RA only                                      | RA as a source             |
| The Door Club                    | https://thedoor.club/events/                   | Club         | Weekly grid is stale; nothing after 1 August              | Site change / re-check     |
| KINDL                            | https://www.kindl-berlin.com/news              | Concert Hall | Art centre calendar is tours and openings, not concerts   | Promoter feed              |
| Containerhafen                   | —                                              | Open Air     | No own site; RA only                                      | RA as a source             |
| Golden Flamingo                  | http://goldenflamingo.de/                      | Open Air     | Restaurant page; "Website befindet sich im Aufbau"        | Site change                |
| FOUND                            | https://foundberlin.com/                       | Club         | Splash page; address and e-mail only                      | Site change / RA           |
| ROSA                             | —                                              | Club         | New club, no own site; RA only                            | RA as a source             |
| Beach Neukölln                   | https://www.beach-neukoelln.de/                | Open Air     | Rental and public-viewing marketing, not a programme      | Promoter feed              |
| RAW-Gelände                      | —                                              | Open Air     | Compound, not a venue; `raw-gelaende.de` is gone          | Covered by the houses      |
| Genezarethkirche                 | https://www.mlg-neukoelln.de/events            | Concert Hall | Parish calendar: services, rehearsals, courses            | Promoter feed              |
| Minimal Bar                      | https://minimal-berlin.geo.io/                 | Techno Club  | No own site; redirects to a geo.io business page          | RA as a source             |
| Sensorium                        | http://www.sensorium-club.com                  | Techno Club  | Domain serves a 229-byte stub page                        | Site change / RA           |
| Insomnia                         | http://www.insomnia-berlin.de                  | Club         | WAF returns 403 with an empty body to scripts             | Request headers / RA       |
| Hafenbar Berlin                  | https://www.hafenbar-berlin.de                 | Bar          | WordPress blog of *past* parties; no programme            | Site change                |
| Bulbul Berlin                    | https://www.bulbulberlin.de                    | Club         | Own site links out to RA for the programme                | RA as a source             |
| Bar Neun                         | http://barneun.de                              | Bar          | Squarespace; 1.1 MB of HTML, no event text                | Headless browser           |
| Unkompress                       | https://www.unkompress.berlin/                 | Club         | Squarespace; event content is client-side only            | Headless browser           |
| Weekend                          | https://www.weekendclub.berlin/                | Club         | Squarespace; event content is client-side only            | Headless browser           |
| M-BIA                            | http://www.m-bia.de                            | Techno Club  | WordPress, but no dated content is rendered               | Site change / RA           |
| KREUZWERK                        | https://kreuzwerk.club/                        | Techno Club  | Address and hours only; no dated content                  | Site change / RA           |
| ACUD MACHT NEU                   | https://acudmachtneu.de/programm/              | Club         | Renders 2 exhibitions; club nights are JS-only            | Headless browser           |
| Neue Nationalgalerie             | https://www.smb.museum/                        | Concert Hall | SMB calendar is tours and workshops, not concerts         | Promoter feed              |
| Gestrandet a. d. Jannowitzbrücke | https://www.gestrandet-in-berlin.de/           | Open Air     | Site returns 502                                          | Site change                |
| Fluxbau                          | https://www.fluxfm.de/fluxbau                  | Club         | Server-rendered now, but 2 dated events + series          | More events / occurrences  |
| Sage Club                        | https://www.sage-club.de/                      | Club         | TYPO3; `/programm/` renders navigation only               | Headless browser           |
| The Pearl                        | https://thepearl-berlin.de/                    | Club         | `/programm/` renders now, but holds one event             | More events                |
| Prince Charles                   | https://princecharlesberlin.com/               | Club         | No own listings; links out to Resident Advisor            | RA as a source             |
| Artliners Berlin                 | —                                              | Club         | Domain no longer resolves; site gone                      | New site                   |
| Prachtwerk                       | https://www.prachtwerkberlin.com/              | Bar          | Has a Programm page now, but it is empty                  | Site change                |
| Wiener Blut                      | https://www.wienerblut.org/                    | Bar          | Impressum-only page                                       | Site change                |
| Paloma                           | https://www.palomabar.de/                      | Bar          | Party names + DJ lineups but **no dates**                 | Havanna-style occurrences  |
| Loft                             | https://loft.de/                               | Promoter     | Cross-venue; one venue per source (see note)              | Per-event venue resolution |
| Greyzone Tickets                 | https://www.greyzone-tickets.de/               | Promoter     | Contact info only; ticket service, not a listing          | —                          |
| Landstreicher Booking            | https://landstreicher-booking.de/              | Promoter     | Cross-venue; one venue per source (see note)              | Per-event venue resolution |
| Landstreicher Konzerte           | https://landstreicher-konzerte.de/             | Promoter     | Cross-venue, cross-city; also has `/venue/` pages         | Per-event venue resolution |
| Puschen                          | https://puschen.net/berlin/                    | Promoter     | Cross-venue; one venue per source (see note)              | Per-event venue resolution |
| Trinity Music                    | https://trinitymusic.de/                       | Promoter     | Cross-venue; one venue per source (see note)              | Per-event venue resolution |
| Arena Berlin                     | https://www.arena.berlin/veranstaltungen/      | Concert Hall | Tribe calendar now, but trade fairs only                  | Site change / promoter     |
| Frannz Salon                     | https://frannz.eu/                             | Club         | Not a separate listing; a floor of Frannz nights          | Covered by FRANNZ          |
| Kesselhaus                       | https://www.kesselhaus.net/                    | Concert Hall | Angular PWA app shell; no JSON endpoint found             | Headless browser           |
| Maschinenhaus                    | https://www.kesselhaus.net/                    | Concert Hall | Shares the Kesselhaus app — same blocker                  | Headless browser           |
| Passionskirche                   | —                                              | Concert Hall | No own website (akanthus.de lapsed to spam)               | Site change / promoter     |
| Theater des Westens              | https://www.stage-entertainment.de/            | Theater      | Stage portal; one musical, dates in ticket shop           | Scope decision             |
| RBB Sendesaal                    | https://www.roc-berlin.de/kalender/            | Concert Hall | Scrapable; deferred pending the classical scope decision  | Scope decision             |
| Zentraler Festplatz              | https://berliner-festplatz.de/                 | Open Air     | Rental ground; "Events" page is social embeds             | Site change                |
| ://about blank                   | https://aboutblank.li/                         | Techno Club  | `/next` carries no events in the HTML                     | Site change / RA           |
| Bohnengold                       | https://bohnengold.de/                         | Bar          | Domain redirects to Facebook                              | Site change                |
| C115                             | https://www.c115.club/                         | Techno Club  | Mailing-list splash page; no programme                    | Site change / RA           |
| ELSE                             | —                                              | Techno Club  | No own website; listings only on RA                       | RA as a source             |
| Hamburger Bahnhof                | https://www.smb.museum/                        | Open Air     | Museum programme is guided tours, not concerts            | Promoter feed              |
| KitKatClub                       | https://www.kitkatclub.org/                    | Techno Club  | News-style prose; series live on external sites           | Site change                |
| Lokschuppen                      | https://lokschuppen-berlin.com/                | Techno Club  | Readymag site; the content is JS-only                     | Headless browser           |
| OXI & OXI Garten                 | https://oxi-club.de/                           | Techno Club  | Domain redirects to Instagram                             | Site change                |
| RSO                              | https://rso.berlin/                            | Techno Club  | Domain returns 404; no own site found                     | Site change / RA           |
| Sisyphos                         | https://www.sisyphos-berlin.net/               | Techno Club  | Shop-only site; 3 ticketed nights, no programme           | RA as a source             |
| SchwuZ                           | https://www.schwuz.de/                         | Techno Club  | Between locations; ~2 guest events listed                 | New venue / site change    |
| Sisyfass                         | —                                              | Bar          | No website; Instagram and RA only                         | Site change                |
| Strandbad Grünau                 | https://strandbadgruenau.de/                   | Open Air     | `/events/` is rental marketing, not a programme           | Promoter feed              |
| Zuckerzauber                     | https://zuckerzauber.info/                     | Bar          | Domain redirects to Facebook                              | Site change                |

## ❓ Not analyzed yet

New candidates land here first: check for a server-rendered programme, then move the row into [Ready](#-ready-to-implement) or
[Blocked](#-blocked--deferred). A row belongs here only until it has been opened — the URL is recorded, nothing more.

**Empty as of 5 August 2026.** The 4 August sweep put 70 candidates here; the same day's analysis moved out the 22 with the highest RA event counts and a real
own website (9 to [Ready](#-ready-to-implement), 13 to [Blocked](#-blocked--deferred), a 41 % hit rate), and the 5 August analysis cleared the remaining 48 —
the rows whose recorded URL was an Instagram or Facebook page, or none at all — at 10 to [Ready](#-ready-to-implement) and 38 to
[Blocked](#-blocked--deferred). Where no own domain was recorded, one was searched for before the row was filed; the twelve sites that turned up are recorded on
whichever row they belong to. Types were corrected against each venue's own site as it was opened, so the RA-derived guesses this table used to carry are gone.

Where the 70 came from, and what was deliberately left out:

- **Resident Advisor** (<https://de.ra.co/events/de/berlin>) — 1142 events at 201 distinct venues over the 4 August – 30 September 2026 window, read from the
  site's own `eventListings` GraphQL query (Berlin is `areas.eq: 34`). This puts a number on what "RA as a source" is worth, which the techno-club note below
  has called the biggest unblocker left: **66 of the 201 venues are new to this document**, and RA's own busiest Berlin room — Minimal Bar, 58 events — was not
  recorded here at all. Analysis then found Minimal Bar has no website of its own, which makes it the strongest single argument for importing RA directly; it
  now sits in [Blocked](#-blocked--deferred).
- **The promoter listings** — Loft, Puschen, Landstreicher Booking and Trinity Music, re-read the same day. They added 4 venues RA did not surface, all of them
  seated or open-air houses rather than clubs. Trinity Music's 48-venue directory yielded nothing new, as expected: it was worked through in full already.
  Chasing down the Gärten der Welt URL surfaced a promoter this document had missed — **Landstreicher Konzerte**, a separate outfit from Landstreicher Booking,
  now filed under [Blocked](#-blocked--deferred) on the same per-event-venue limitation.

**Excluded on purpose, so a later sweep does not re-litigate them.** RA venues with a single event in the window, unless a promoter listed them too — a one-off
booking is not evidence of a programme, and the [Sisyphos rule](#-blocked--deferred) applies. Also every `TBA …` pseudo-venue (about 60 events: secret
locations, Telegram-only addresses, boat terminals), bare addresses and landmarks used as festival grounds (`Straße des 17. Juni`, `Brandenburger Tor`,
`Tempelhof Airport`), hotels and hostels, and venues outside Berlin that RA files under the Berlin area anyway (Waschhaus in Potsdam, Völklingen Ironworks in
Saarland).

The **Events** column that used to head this table was the RA count for the 4 August – 30 September 2026 window. It is kept on no row now, but the number it
produced is worth recording: it correlated poorly with what a venue actually publishes, and a promoter mention was the better signal — see the note in
[Ready](#-ready-to-implement).

Two source lists were worked through completely and are no longer reproduced here:

- The 48 venues of the **Trinity Music location directory** (<https://trinitymusic.de/locations>) — 17 were already imported, the other 31 are now filed above.
  The venue-level rows are what carries this list now: the **Trinity Music** promoter source itself is deferred (see
  [Blocked](#-blocked--deferred)), and a venue site usually yields richer data than a promoter listing anyway.
- The **techno-club cluster** — 26 clubs and bars, of which 13 turned out to be scrapable. The other 13 publish only through Instagram, Facebook or Resident
  Advisor, which makes **Resident Advisor as a source** the single biggest unblocker left on this list. The 4 August 2026 sweep above sizes that claim: RA
  carries 1142 Berlin events over eight weeks, and names five [Blocked](#-blocked--deferred) venues whose blocker is precisely "no own site" — ://about blank,
  ELSE, KitKatClub, OXI and RSO — beside 66 venues this document had never recorded. Note that RA is one source, not 66: importing it means one `event_source`
  spanning every venue, so it runs into **the same per-event venue resolution** the promoter listings are deferred on.

---

## TODO

Source-discovery and new-importer tasks are tracked in the [coverage epic #351](https://github.com/enorm-labs/event-checker/issues/351) and its sub-issues.
