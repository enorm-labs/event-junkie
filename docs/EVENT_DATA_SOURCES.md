# Event Data Sources — Berlin

Overview of all venues, clubs, and promoters whose websites are potential sources for importing event data. Sources are grouped by **import status** so the
remaining work is visible at a glance.

## The short version

Four tables, one per import status, and a fifth for promoters. The counts below are the state of the work.
**[Ready](#-ready-to-implement) is the one to read** — those rows are the next `/scaffold-importer` runs. Everything in
[Blocked](#-blocked--deferred) names what would unblock it. Each venue has one main source, and a [promoter](#-promoters)
only enriches the events it lists (ADR-043). **Resident Advisor is not a source.** Its
terms forbid automated access, and [Blocked](#-blocked--deferred) says what that means for a venue that publishes only
there.

**This document answers _which venues_.** For _which kinds of event_, see [EVENT_SCOPE.md](EVENT_SCOPE.md). That is
the standing reference for scope. It records what is in, what is deliberately excluded — sport, participation formats,
trade fairs, classical — and which coverage questions are open. Several rows below sit in _Blocked_ on a scope
decision rather than a technical one, and that document is where those decisions are recorded. The **Comment** column
records what matters for building or maintaining an importer: the platform, where the data lives, and the parsing
quirks. For an implemented importer, its KDoc and scraper tests are the authoritative field mapping. Defects worth
repairing live in the [issue tracker](https://github.com/enorm-labs/event-junkie/issues).

| Status                              | Meaning                                                                              | Count |
| ----------------------------------- | ------------------------------------------------------------------------------------ | ----: |
| ✅ [Imported](#-imported)           | Importer implemented and scheduled                                                   |   123 |
| 🔨 [Ready](#-ready-to-implement)    | Website analyzed, listings are scrapable — these are the next importers to build     |    47 |
| ⛔ [Blocked](#-blocked--deferred)   | Website analyzed, but no usable listings (no programme page, JS-only, or too sparse) |   136 |
| 📣 [Promoters](#-promoters)         | Cross-venue listings; enrich the venues' own events, never add events (ADR-043)      |    11 |
| ❓ [Unanalyzed](#-not-analyzed-yet) | URL recorded, but the website still needs a first look                               |    11 |

A count is the number of rows in its table. `scripts/sources-parity.sh` checks each count, in CI and in the
commit hook.

"Website analyzed" also means the [data model](DATA_MODEL.md) was checked against that source, and no source needed a
schema change.

[Second language](#-second-language) records which imported sources publish a second language, and how.

## ✅ Imported

| Name                             | URL                                                         | Type         | Comment                                                   |
| -------------------------------- | ----------------------------------------------------------- | ------------ | --------------------------------------------------------- |
| 808 Nachtklub Berlin             | https://808.berlin/                                         | Club         | Astro island props; opening hours from JSON-LD            |
| A-Trane                          | https://a-trane.de/                                         | Club         | EventON cards: JSON-LD, style tags, ticket prices         |
| Abstand                          | https://radar.squat.net/en/node/1608                        | Bar          | radar group API; bands only in prose                      |
| ÆDEN                             | https://aedenberlin.com/                                    | Techno Club  | WordPress; /events → month pages; no prices               |
| Admiralspalast                   | https://www.admiralspalast.theater/                         | Theater      | Contao; one event per performance row; no prices          |
| Alte Kantine Kulturbrauerei      | https://alte-kantine.eu/                                    | Concert Hall |                                                           |
| AMT                              | https://www.club-amt.berlin                                 | Techno Club  | Webflow; month pages; calendar stale since August         |
| Arcanoa                          | https://www.ssi-media.com/arcanoa/veranst.htm               | Bar          | 1990s HTML; title/date only; year from weekday            |
| ART Stalker                      | https://art-stalker.reservix.de/                            | Bar          | Reservix shop root; robots bars `/events`, 25 events      |
| Astra Kulturhaus                 | https://www.astra-berlin.de/                                | Concert Hall | schema.org `MusicEvent`; presale + door prices            |
| Badehaus                         | https://badehaus-berlin.com/                                | Club         | "AUSVERKAUFT"/"VERLEGT" labels; ticket + FB links         |
| Ballhaus Wedding                 | https://www.ballhauswedding.de/veranstaltungen              | Other        | Wix rich text; years by weekday vote; Wix Events          |
| Bar jeder Vernunft               | https://www.bar-jeder-vernunft.de/de/programm/kalender.html | Bar          | Neos; per-date JSON-LD; one show page per run             |
| Bar Tausend                      | https://tausendberlin.com/                                  | Bar          | Divi JSON-LD graph; full and EN text from blocks          |
| Berghain / Panorama Bar          | https://www.berghain.berlin/de/program/                     | Techno Club  | Server-rendered; list + detail                            |
| Bi Nuu                           | https://binuu.de/                                           | Club         | No genre or prices on site; only via ticket link          |
| Cassiopeia                       | https://cassiopeia-berlin.de/                               | Club         | Webflow; genre tags, badges; walks all pages              |
| Clash Club                       | https://clash-berlin.de/                                    | Club         | WordPress; prices, doors and blurb only as prose          |
| Club der Visionäre               | https://clubdervisionaere.com/programm                      | Techno Club  | WordPress; one listing, 3 rooms by CSS class              |
| Club OST                         | https://clubost.de/                                         | Techno Club  | Django; homepage is the programme; RA tickets             |
| Colosseum                        | https://www.colosseumberlin.com/event                       | Concert Hall | Wix Events warmup JSON; external shops feign sold-out     |
| Columbia Theater                 | https://columbia-theater.de/                                | Concert Hall | WordPress; date in slug; status via `data-*` flag         |
| Columbiahalle                    | https://www.columbiahalle.berlin/veranstaltungen.html       | Concert Hall | Contao; one page, month headings carry the year           |
| Comedy Café Berlin               | https://www.comedycafeberlin.com/                           | Comedy Club  | The Events Calendar REST API, as Cosmic Comedy            |
| Cosmic Comedy Club               | https://comedyclubberlin.com/wp-json/tribe/events/v1/events | Comedy Club  | The Events Calendar REST API; cursor-paged; no prices     |
| Crack Bellmer                    | https://www.crackbellmer.de/program/this-month              | Bar          | Webflow; month tabs filter one list; no prices            |
| Der Weiße Hase                   | https://derweissehase.club/events                           | Club         | Contao; invalid `<p>` nesting; RA ticket links            |
| Die Wühlmäuse                    | https://wuehlmaeuse.de/                                     | Theater      | WooCommerce Store API; one product per seat tier          |
| Downstairs Comedy Club           | https://www.downstairscomedy.shop/tickets                   | Comedy Club  | tickettoaster Turbo frame; JSON-LD per show               |
| Drugstore                        | https://drugstore-berlin.de/                                | Other        | radar group API; bands only in prose                      |
| Duncker Club                     | https://www.dunckerclub.de/                                 | Club         |                                                           |
| Erreichbar                       | https://radar.squat.net/en/node/6653                        | Bar          | radar group API; Punkrocktresen only                      |
| Eschschloraque Rümschrümp        | https://www.eschschloraque.de/                              | Bar          | Drupal 7; front page = full nodes; RDFa datetimes         |
| Festsaal Kreuzberg               | https://festsaal-kreuzberg.de/de                            | Concert Hall | Nuxt/Wagtail SSR; `ld+json` empty; no prices              |
| Fitzroy                          | https://fitzroy-berlin.de/events/                           | Club         | Shares the LARK parser; every event typed `Party`         |
| Frannz Club                      | https://frannz.eu/                                          | Club         |                                                           |
| gART.n                           | https://www.gartn.xyz/                                      | Techno Club  | Carrd one-pager; year from weekday; no prices             |
| Gärten der Welt                  | https://www.gaertenderwelt.de/events/veranstaltungen/       | Open Air     | TYPO3 events2; paged; park activities excluded            |
| Golden Gate                      | https://goldengate-berlin.de/                               | Techno Club  | Elementor; current Thu–Sat block only; door-only          |
| Gretchen                         | https://www.gretchen-club.de/                               | Club         |                                                           |
| Havanna                          | https://www.havanna-berlin.de/                              | Club         | Undated weekly nights; occurrences derived                |
| Heideglühen                      | https://heidegluehen.berlin/monatsvorschau/                 | Techno Club  | One month at a time; DJ lineup on /aktuell/               |
| Heimathafen Neukölln             | https://heimathafen-neukoelln.de/                           | Concert Hall | WP REST + ACF; one post, many dated performances          |
| Hole 44                          | https://hole-berlin.de/                                     | Concert Hall | Events-Manager; "Abgesagt!" / "VERLEGT!" labels           |
| House of Music Berlin            | https://www.houseofmusic.berlin/                            | Concert Hall | Wix Events warmup JSON; acts from title shapes            |
| Humboldthain Club                | https://www.humboldthain.com/                               | Techno Club  | Elfsight widget API; weekly night expanded                |
| Huxleys Neue Welt                | https://huxleysneuewelt.de/events                           | Concert Hall | Events-Manager; ISO slug date; genre/promoter tags        |
| Junction Bar                     | https://www.junction-bar.de/                                | Bar          | Static monthly pages; show times vary by weekday          |
| Kabarett-Theater DISTEL          | https://distel-berlin.de/spielplan/kalender/                | Theater      | Contao month calendar; ~7 MB pages of inline SVG          |
| Kantine am Berghain              | https://www.berghain.berlin/de/program/kantine-am-berghain/ | Concert Hall | Shares BERGHAIN importer                                  |
| Kater                            | https://www.katerclub.de/                                   | Techno Club  | Homepage programme; ___ floor rules mark lineups          |
| Kesselhaus                       | https://www.kesselhaus.net/de/calendar                      | Concert Hall | Angular transfer state; ?part= month windows              |
| KitKatClub                       | https://kitkatclub.org/Home/Club/Index.html                 | Techno Club  | Table CMS; one week; year-less dates; rooms in line-up    |
| Klunkerkranich                   | https://klunkerkranich.org/events/                          | Bar          | WordPress; ISO date in slug; ~10-day horizon              |
| KØPI                             | https://koepi137.net/                                       | Club         | radar group API; bill read from the description           |
| Kulturhaus Insel Berlin          | https://www.inselberlin.de/                                 | Concert Hall | Gatsby static-query JSON; times in the blurb              |
| Kulturhaus Peter Edel            | https://www.peteredel.de/events/                            | Concert Hall | Umbraco grid; month heading carries the year              |
| LARK                             | https://larkberlin.com/events/                              | Club         | WP REST + ACF; post date is the event date                |
| Lido                             | https://www.lido-berlin.de/                                 | Concert Hall | Clean slugs; doors + start; "Ausverkauft" badge           |
| Loge                             | https://www.loge-berlin.org/                                | Club         | Wix; tickets on-site; support via "+" in title            |
| MAAYA                            | https://maaya.de/                                           | Club         | Elementor home page; year-less dates; `pm` half real      |
| Madame Claude                    | https://madameclaude.de/                                    | Bar          | WordPress `event` REST API (ACF)                          |
| Maschinenhaus                    | https://www.kesselhaus.net/de/calendar                      | Concert Hall | Shares the Kesselhaus calendar; room filter               |
| Matrix Club Berlin               | https://www.matrix-berlin.de/                               | Club         | WordPress; month pages walked; DJs + door prices          |
| Max-Schmeling-Halle              | https://www.velomax.de/events                               | Arena        | Shared VELOMAX listing; no sport imported                 |
| Maxxim Club                      | https://www.maxxim-berlin.de/partys                         | Club         | Wix Events warmup JSON; UTC dates; prices inline          |
| Mehringhof-Theater               | https://www.mehringhoftheater.de/programm/                  | Theater      | IONOS month tables; tickettoaster JSON-LD                 |
| Metropol                         | https://metropol-berlin.de/events                           | Concert Hall | Events-Manager list + detail; no prices; "Verlegt"        |
| migas                            | https://migas.berlin/program/                               | Bar          | WordPress; per-event modal; lazy imgs; POSTs page 2+      |
| Mikropol                         | https://mikropol-berlin.de/                                 | Club         | Events-Manager list + detail; "verlegt in den …"          |
| Modus Berlin                     | https://modus-berlin.de/events                              | Club         | List + detail; rendered date wins over stale slug         |
| Monarch                          | https://www.kottimonarch.de/                                | Bar          | PHP /programm.php; type + status inline in title          |
| Monster Ronson's Ichiban Karaoke | https://www.karaokemonster.de/                              | Bar          | Webflow; paged listing; banded prices; closure cards      |
| Morphine Raum                    | http://www.morphinerecords.com/events                       | Club         | Hand-coded Kirby; ARCHIVE row skipped; price ranges       |
| MS Hoppetosse                    | https://hoppetosse.berlin/                                  | Techno Club  | Shares the CdV listing; winter location only              |
| Neue Zukunft                     | https://neue-zukunft.org/                                   | Club         | Elfsight Event Calendar widget API                        |
| OHM                              | https://ohmberlin.com/                                      | Techno Club  | Year-less dd/MM; only 1–3 nights listed at a time         |
| Orangerie Neukölln               | https://www.orangerie-nk.de/                                | Bar          | Static one-pager; JSON-LD ItemList plus cards             |
| Orania.Berlin                    | https://orania.berlin/concerts                              | Bar          | TYPO3 Calendarize; three pages of ten; always free        |
| PANDA platforma                  | https://panda-platforma.berlin/veranstaltungen-in-berlin/   | Other        | TEC REST API; series type the night; jazz credits players |
| Panke Culture                    | https://www.pankeculture.com/programme/                     | Club         | WordPress/Divi; upcoming list only; no event pages        |
| Parkbühne Wuhlheide              | https://www.wuhlheide.de/programm                           | Open Air     | October CMS; ISO date in URL; seasonal, sold-out          |
| Pfefferberg Haus 13              | https://haus13.pfefferwerk.de/                              | Concert Hall | Event Organiser; 2 listing pages + event pages            |
| Privatclub                       | https://privatclub-berlin.de/                               | Club         | Rich detail pages; genre, presale + AK prices             |
| PUNCH L!NE Club                  | https://punchlineberlin.com/de/tickets                      | Comedy Club  | Next.js flight payload; all dates on one page             |
| Quasimodo                        | https://quasimodo.club/events                               | Club         | Events-Manager; .club domain; genre tags + prices         |
| Quatsch Comedy Club              | https://quatsch-comedy-club.de/tickets/                     | Comedy Club  | Eventim calendar plugin; one admin-ajax POST per day      |
| Renate                           | https://www.renate.cc/                                      | Techno Club  | Homepage programme; per-floor lineups and opening times   |
| Richten25                        | https://richten25.de/events                                 | Other        | Hostinger; HTML embed in Astro props; lineups only        |
| Ritter Butzke                    | https://club.ritterbutzke.com/events                        | Techno Club  | Modus codebase, own template; stale slug dates            |
| Roadrunner's Paradise            | http://www.roadrunners-paradise.de/                         | Bar          | Retro HTML; rich data; year missing on some dates         |
| ROSA                             | https://www.rosaclub.de/dates                               | Club         | Next.js; age-gate cookie; events in the flight payload    |
| Säälchen                         | https://www.holzmarkt.com/kalender                          | Concert Hall | Drupal; shared calendar filtered by location              |
| Scheinbar Varieté                | https://www.scheinbar.de/programm/                          | Theater      | ProcessWire list by month; one show a night               |
| Schokoladen                      | https://www.schokoladen-mitte.de/                           | Club         | Laravel; anchor-based events; genre inside title          |
| silent green                     | https://www.silent-green.net/programm                       | Concert Hall | TYPO3 news; month walk; a run listed per open day         |
| Sisyphos                         | https://www.sisyphos-berlin.net/                            | Techno Club  | Calendar JSON + shop + sisy.fan line-ups (ADR-038)        |
| SO36                             | https://www.so36.com/tickets                                | Club         | Cookie wall bypassed via Ticket-Toaster shop              |
| Soda Club                        | https://www.soda-berlin.de/events                           | Club         | disco2app CMS; `MusicEvent` JSON-LD on details            |
| Sonnenraum                       | https://clubdervisionaere.com/programm                      | Club         | Shares the CdV listing; Monday live residency             |
| Soulcat                          | https://soulcat-berlin.com/                                 | Bar          | TEC REST API; title-only DJ nights, no Fussball           |
| Speakeazy                        | https://www.speakeazyberlin.de/events                       | Bar          | Squarespace HTML, robots bars `?format=json`; door prices |
| Supamolly                        | https://www.supamolly.de/?p=programm                        | Club         | Retro PHP; row id is the date stamp; no prices            |
| Tempodrom                        | https://www.tempodrom.de/programm-und-tickets/              | Concert Hall | JSON-LD listing; promoter per event page                  |
| The Wall Comedy Club             | https://thewallcomedy.com/                                  | Comedy Club  | Spotagig JSON-LD; HTMX "Show more" partials               |
| Theater im Delphi                | https://theater-im-delphi.de/programm/                      | Concert Hall | One row per performance; prices only in a leak            |
| Tiffany Club                     | https://tiffany-berlin.de/upcoming-events/                  | Club         | Elementor; year from the weekday; blurb per event page    |
| Tresor                           | https://tresorberlin.com/club/events/                       | Techno Club  | WordPress; floor-grouped lineup; detail pages             |
| Uber Arena                       | https://www.uber-arena.de/events/all                        | Arena        | AEG CMS; list + detail; no sport imported                 |
| Uber Eats Music Hall             | https://www.uber-eats-music-hall.de/events/all              | Concert Hall | Shares the Uber Arena parsers; month names, no cats       |
| ufaFabrik                        | https://ufafabrik.de/spielplan.html                         | Theater      | Drupal month pages; this month + next; no kids' shows     |
| UFO im Velodrom                  | https://www.velomax.de/events                               | Concert Hall | Shares the VELOMAX listing                                |
| Urania                           | https://www.urania.de/kalender/                             | Concert Hall | One house; no source attributes an event to a hall        |
| Urban Spree                      | https://www.urbanspree.com/program/                         | Club         | MODX; listing descending + paginated; walks pages         |
| Velodrom                         | https://www.velomax.de/events                               | Arena        | Shares the VELOMAX listing; Microdata details             |
| VOID Club                        | https://www.void-club.de/                                   | Techno Club  | Hand-coded Bootstrap; year from weekday; 2 rooms          |
| Wild at Heart                    | https://www.wildatheartberlin.de/                           | Bar          | Retro frameset; concerts.php; year from weekday           |
| Zenner                           | https://zenner.berlin/programm                              | Club         | Gatsby/Sanity page-data JSON; UTC dates; archive          |
| Zig Zag Hall                     | https://www.zigzag-jazzclub.berlin/programmneu              | Concert Hall | Shares the Zig Zag list; `ZIG ZAG HALL:` items only       |
| Zig Zag Jazz Club                | https://www.zigzag-jazzclub.berlin/programmneu              | Club         | Squarespace list + event pages; Hall items dropped        |
| ZIMMER 16                        | https://zimmer16.com/                                       | Other        | YesTicket cards + event pages; next 20 only               |
| Zitadelle                        | https://citadel-music-festival.de/events                    | Open Air     | Festival site; WordPress/EM; summer season only           |
| Zur Klappe                       | https://zurklappe.org/events                                | Techno Club  | Next.js flight payload; no genre or prices                |

122 importer classes cover 123 sources. Only Kantine am Berghain has no class of its own, and it shares the Berghain
importer outright. Its rows have their own `sourceId` prefix, `kantine_am_berghain:`. Four other groups share a _listing and parser_ while keeping one thin `@Component` per venue, so
they do not reduce the count. They are Club der Visionäre with Sonnenraum and MS Hoppetosse, and the three Velomax halls.
The other two are Kesselhaus with Maschinenhaus, and Uber Arena with the Uber Eats Music Hall.

## 🌐 Second language

This table records which imported sources publish a second language, and how they do it (#330). The decision in
ADR-026 uses these counts.

The audit read each listing page one time on 2026-10-04, with the importer's User-Agent. Where a second-language page
exists, the audit also read that page one time. Club OST got a second request with `Accept-Language: en`. The scraper
test fixtures supply most of the "one field" rows, because a listing page usually does not show the full description.

- **Second language** is _Yes_ when the site links to a second language or one description holds two languages. _No_
  means that the page showed neither. _Unknown_ means that the site did not send the page.
- **Event text** compares the two listing pages. _Translated_ means that the event text changes language. _Not on the
  listing_ means that only the site text changes. The detail pages possibly show translated text. _One field_ means
  that one description holds both languages, with a marker between them.
- **Evidence** is the page that shows the second language. A _captured page_ is a fixture that the scraper tests read.

47 sources have a second language, 72 have none, and 4 are unknown. Eight translate the event text on a second page.
Ten put both languages in one field.

The scrapers of the one-field sources cut the description at its marker (`splitBilingualDescription`). The second half
becomes the publisher's second language. Each half must read as German or English, so a Russian half stays in the
whole text.

Six importers also read the page in the other language. They store its event text as the publisher's second language:

- Bar Tausend and Columbiahalle read the English listing. This adds one request to each run.
- Bar jeder Vernunft and Theater im Delphi read the English page of each production. This adds one request for each
  production.
- silent green reads the English page of each programme entry. This adds one request for each entry.
- Orania.Berlin reads the German page of each concert. This adds one request for each concert.

The importer finds the other page through the `hreflang` link of the page that it reads. When that page does not load,
the run keeps the first language and stores no second language. The upsert stores a second text only when the two
texts read as German and English. Club OST is not on this list, because none of its events has a description.

| Name                             | Second language | How                                                          | Event text         | Evidence                                                                                                                           |
| -------------------------------- | --------------- | ------------------------------------------------------------ | ------------------ | ---------------------------------------------------------------------------------------------------------------------------------- |
| 808 Nachtklub Berlin             | Yes             | `/en/` path, `hreflang`                                      | Not on the listing | https://808.berlin/en/                                                                                                             |
| A-Trane                          | Yes             | One field: German, then `English`                            | One field          | https://a-trane.de/                                                                                                                |
| Abstand                          | Unknown         | radar.squat.net sent a bot challenge                         | —                  |                                                                                                                                    |
| ÆDEN                             | No              | —                                                            | —                  |                                                                                                                                    |
| Admiralspalast                   | No              | —                                                            | —                  |                                                                                                                                    |
| Alte Kantine Kulturbrauerei      | No              | —                                                            | —                  |                                                                                                                                    |
| AMT                              | No              | —                                                            | —                  |                                                                                                                                    |
| Arcanoa                          | No              | —                                                            | —                  |                                                                                                                                    |
| ART Stalker                      | Yes             | `?_locale=en` on the Reservix shop                           | Not translated     | https://art-stalker.reservix.de/events?_locale=en                                                                                  |
| Astra Kulturhaus                 | No              | —                                                            | —                  |                                                                                                                                    |
| Badehaus                         | Yes             | `/en/` path, `hreflang`                                      | Not on the listing | https://badehaus-berlin.com/en/                                                                                                    |
| Ballhaus Wedding                 | No              | —                                                            | —                  |                                                                                                                                    |
| Bar jeder Vernunft               | Yes             | `/en/` path, `hreflang`                                      | Translated         | https://www.bar-jeder-vernunft.de/en/whats-on/calendar.html                                                                        |
| Bar Tausend                      | Yes             | Separate page, `hreflang` on the link                        | Translated         | https://tausendberlin.com/en/lineup/                                                                                               |
| Berghain / Panorama Bar          | Yes             | `/en/` path, `hreflang`                                      | Not on the listing | https://www.berghain.berlin/en/program/                                                                                            |
| Bi Nuu                           | Yes             | `/en` path, `hreflang`                                       | Not on the listing | https://binuu.de/en                                                                                                                |
| Cassiopeia                       | No              | —                                                            | —                  |                                                                                                                                    |
| Clash Club                       | No              | —                                                            | —                  |                                                                                                                                    |
| Club der Visionäre               | No              | —                                                            | —                  |                                                                                                                                    |
| Club OST                         | Yes             | Content negotiation (`Vary: Accept-Language`), language form | Translated         | https://clubost.de/                                                                                                                |
| Colosseum                        | No              | —                                                            | —                  |                                                                                                                                    |
| Columbia Theater                 | No              | —                                                            | —                  |                                                                                                                                    |
| Columbiahalle                    | Yes             | Separate page, `hreflang` on the link                        | Translated         | https://www.columbiahalle.berlin/events.html                                                                                       |
| Comedy Café Berlin               | No              | —                                                            | —                  |                                                                                                                                    |
| Cosmic Comedy Club               | No              | —                                                            | —                  |                                                                                                                                    |
| Crack Bellmer                    | No              | —                                                            | —                  |                                                                                                                                    |
| Der Weiße Hase                   | Yes             | Separate page `/events-2`, `hreflang`                        | Not on the listing | https://derweissehase.club/events-2                                                                                                |
| Die Wühlmäuse                    | No              | —                                                            | —                  |                                                                                                                                    |
| Downstairs Comedy Club           | No              | —                                                            | —                  |                                                                                                                                    |
| Drugstore                        | No              | —                                                            | —                  |                                                                                                                                    |
| Duncker Club                     | No              | —                                                            | —                  |                                                                                                                                    |
| Erreichbar                       | Unknown         | radar.squat.net sent a bot challenge                         | —                  |                                                                                                                                    |
| Eschschloraque Rümschrümp        | No              | —                                                            | —                  |                                                                                                                                    |
| Festsaal Kreuzberg               | Yes             | `/en` path                                                   | Not on the listing | https://festsaal-kreuzberg.de/en                                                                                                   |
| Fitzroy                          | No              | —                                                            | —                  |                                                                                                                                    |
| Frannz Club                      | No              | —                                                            | —                  |                                                                                                                                    |
| gART.n                           | No              | —                                                            | —                  |                                                                                                                                    |
| Gärten der Welt                  | Yes             | `/en/` path, `hreflang`                                      | Not on the listing | https://www.gaertenderwelt.de/en/events/events/                                                                                    |
| Golden Gate                      | No              | —                                                            | —                  |                                                                                                                                    |
| Gretchen                         | Yes             | `/en/` path                                                  | Not on the listing | https://www.gretchen-club.de/en/                                                                                                   |
| Havanna                          | No              | —                                                            | —                  |                                                                                                                                    |
| Heideglühen                      | No              | —                                                            | —                  |                                                                                                                                    |
| Heimathafen Neukölln             | No              | —                                                            | —                  |                                                                                                                                    |
| Hole 44                          | Yes             | `/en/` path, `hreflang`                                      | Not on the listing | https://hole-berlin.de/en/                                                                                                         |
| House of Music Berlin            | No              | —                                                            | —                  |                                                                                                                                    |
| Humboldthain Club                | Yes             | One field: `English version above`                           | One field          | [captured page](../events-importer/src/test/resources/scraper/humboldthain/humboldthain-api.json)                                  |
| Huxleys Neue Welt                | Yes             | `/en/` path, `hreflang`                                      | Not on the listing | https://huxleysneuewelt.de/en/events/                                                                                              |
| Junction Bar                     | No              | —                                                            | —                  |                                                                                                                                    |
| Kabarett-Theater DISTEL          | No              | —                                                            | —                  |                                                                                                                                    |
| Kantine am Berghain              | Yes             | `/en/` path, `hreflang`                                      | Not on the listing | https://www.berghain.berlin/en/program/kantine-am-berghain/                                                                        |
| Kater                            | No              | —                                                            | —                  |                                                                                                                                    |
| Kesselhaus                       | Yes             | `/en/` path                                                  | Not on the listing | https://www.kesselhaus.net/en/calendar                                                                                             |
| KitKatClub                       | No              | —                                                            | —                  |                                                                                                                                    |
| Klunkerkranich                   | Yes             | One field: German, `[EN]`, English                           | One field          | https://klunkerkranich.org/events/2026-10-09-trauma-mia-presents-bass-island-w-clavd-frau-kaufmann-confred-trauma-mia-resi-regelt/ |
| KØPI                             | No              | —                                                            | —                  |                                                                                                                                    |
| Kulturhaus Insel Berlin          | Yes             | One field: `[English below]`                                 | One field          | [captured page](../events-importer/src/test/resources/scraper/insel/insel-events.json)                                             |
| Kulturhaus Peter Edel            | No              | —                                                            | —                  |                                                                                                                                    |
| LARK                             | No              | —                                                            | —                  |                                                                                                                                    |
| Lido                             | No              | —                                                            | —                  |                                                                                                                                    |
| Loge                             | Yes             | `/en` path, `hreflang`                                       | Unknown            | https://www.loge-berlin.org/en                                                                                                     |
| MAAYA                            | Unknown         | The page sent a bot challenge (403)                          | —                  |                                                                                                                                    |
| Madame Claude                    | No              | —                                                            | —                  |                                                                                                                                    |
| Maschinenhaus                    | Yes             | `/en/` path                                                  | Not on the listing | https://www.kesselhaus.net/en/calendar                                                                                             |
| Matrix Club Berlin               | Yes             | `/en/` path, `hreflang`                                      | Translated         | https://www.matrix-berlin.de/en/                                                                                                   |
| Max-Schmeling-Halle              | Yes             | `/en/` path, `hreflang`                                      | Not on the listing | https://www.velomax.de/en/events                                                                                                   |
| Maxxim Club                      | No              | —                                                            | —                  |                                                                                                                                    |
| Mehringhof-Theater               | No              | —                                                            | —                  |                                                                                                                                    |
| Metropol                         | No              | —                                                            | —                  |                                                                                                                                    |
| migas                            | No              | —                                                            | —                  |                                                                                                                                    |
| Mikropol                         | Yes             | `/en/` path, `hreflang`                                      | Not on the listing | https://mikropol-berlin.de/en/                                                                                                     |
| Modus Berlin                     | No              | —                                                            | —                  |                                                                                                                                    |
| Monarch                          | No              | —                                                            | —                  |                                                                                                                                    |
| Monster Ronson's Ichiban Karaoke | No              | —                                                            | —                  |                                                                                                                                    |
| Morphine Raum                    | No              | —                                                            | —                  |                                                                                                                                    |
| MS Hoppetosse                    | No              | —                                                            | —                  |                                                                                                                                    |
| Neue Zukunft                     | No              | —                                                            | —                  |                                                                                                                                    |
| OHM                              | No              | —                                                            | —                  |                                                                                                                                    |
| Orangerie Neukölln               | Yes             | `?lang=en` on the link                                       | Not on the listing | https://www.orangerie-nk.de/?lang=en                                                                                               |
| Orania.Berlin                    | Yes             | English first; `/de/` path, `hreflang`                       | Translated         | https://orania.berlin/de/konzerte                                                                                                  |
| PANDA platforma                  | Yes             | One field: Russian, `[EN]`, English                          | One field          | [captured page](../events-importer/src/test/resources/scraper/pandaplatforma/pandaplatforma-events.json)                           |
| Panke Culture                    | No              | —                                                            | —                  |                                                                                                                                    |
| Parkbühne Wuhlheide              | No              | —                                                            | —                  |                                                                                                                                    |
| Pfefferberg Haus 13              | No              | —                                                            | —                  |                                                                                                                                    |
| Privatclub                       | Yes             | One field: `— english version —`                             | One field          | https://privatclub-berlin.de/                                                                                                      |
| PUNCH L!NE Club                  | Yes             | `/en/` path                                                  | Not on the listing | https://punchlineberlin.com/en/tickets                                                                                             |
| Quasimodo                        | Yes             | `/en/` path, `hreflang`                                      | Not on the listing | https://quasimodo.club/en/events                                                                                                   |
| Quatsch Comedy Club              | No              | —                                                            | —                  |                                                                                                                                    |
| Renate                           | No              | —                                                            | —                  |                                                                                                                                    |
| Richten25                        | No              | —                                                            | —                  |                                                                                                                                    |
| Ritter Butzke                    | No              | —                                                            | —                  |                                                                                                                                    |
| Roadrunner's Paradise            | No              | —                                                            | —                  |                                                                                                                                    |
| ROSA                             | No              | —                                                            | —                  |                                                                                                                                    |
| Säälchen                         | No              | —                                                            | —                  |                                                                                                                                    |
| Scheinbar Varieté                | No              | —                                                            | —                  |                                                                                                                                    |
| Schokoladen                      | No              | —                                                            | —                  |                                                                                                                                    |
| silent green                     | Yes             | `/en/` path, `hreflang`                                      | Translated         | https://www.silent-green.net/en/programme                                                                                          |
| Sisyphos                         | No              | —                                                            | —                  |                                                                                                                                    |
| SO36                             | Yes             | One field: `(Deutsche Version unten)`                        | One field          | [captured page](../events-importer/src/test/resources/scraper/so36/so36-detail-tiers.html)                                         |
| Soda Club                        | No              | `hreflang` `en` redirects to the German page                 | —                  |                                                                                                                                    |
| Sonnenraum                       | No              | —                                                            | —                  |                                                                                                                                    |
| Soulcat                          | No              | —                                                            | —                  |                                                                                                                                    |
| Speakeazy                        | Yes             | One field: German, `EN`, English                             | One field          | https://www.speakeazyberlin.de/events                                                                                              |
| Supamolly                        | No              | —                                                            | —                  |                                                                                                                                    |
| Tempodrom                        | Yes             | `/en/` path, `hreflang`                                      | Not on the listing | https://www.tempodrom.de/en/events-and-tickets/                                                                                    |
| The Wall Comedy Club             | No              | —                                                            | —                  |                                                                                                                                    |
| Theater im Delphi                | Yes             | `/en/` path                                                  | Translated         | https://theater-im-delphi.de/en/programm/                                                                                          |
| Tiffany Club                     | No              | —                                                            | —                  |                                                                                                                                    |
| Tresor                           | No              | —                                                            | —                  |                                                                                                                                    |
| Uber Arena                       | Unknown         | The page refused the request (406)                           | —                  |                                                                                                                                    |
| Uber Eats Music Hall             | Yes             | `/en/` path                                                  | Unknown            | https://www.uber-eats-music-hall.de/en/events/all/                                                                                 |
| ufaFabrik                        | Yes             | `/en/` path                                                  | Not on the listing | https://ufafabrik.de/en/program.html                                                                                               |
| UFO im Velodrom                  | Yes             | `/en/` path, `hreflang`                                      | Not on the listing | https://www.velomax.de/en/events                                                                                                   |
| Urania                           | No              | —                                                            | —                  |                                                                                                                                    |
| Urban Spree                      | Yes             | English first; `/de/` and `/fr/` paths                       | Not on the listing | https://www.urbanspree.com/de/program/                                                                                             |
| Velodrom                         | Yes             | `/en/` path, `hreflang`                                      | Not on the listing | https://www.velomax.de/en/events                                                                                                   |
| VOID Club                        | Yes             | One field, in the club text only                             | Not on the listing | https://www.void-club.de/                                                                                                          |
| Wild at Heart                    | No              | —                                                            | —                  |                                                                                                                                    |
| Zenner                           | Yes             | Script switch, no URL                                        | Unknown            | https://zenner.berlin/programm                                                                                                     |
| Zig Zag Hall                     | Yes             | One field: `(for English please scroll down)`                | One field          | [captured page](../events-importer/src/test/resources/scraper/zigzag/zigzag-hall-detail.html)                                      |
| Zig Zag Jazz Club                | Yes             | One field: `(for English please scroll down)`                | One field          | [captured page](../events-importer/src/test/resources/scraper/zigzag/zigzag-detail.html)                                           |
| ZIMMER 16                        | No              | —                                                            | —                  |                                                                                                                                    |
| Zitadelle                        | Yes             | `/en/` path, `hreflang`                                      | Not on the listing | https://citadel-music-festival.de/en/events/                                                                                       |
| Zur Klappe                       | No              | —                                                            | —                  |                                                                                                                                    |

## 🔨 Ready to implement

Analyzed and scrapable — the candidates for the next `/scaffold-importer` runs. **Priority** reflects data richness and effort, not venue importance.

**Most rows here already have a venue page without events** (#2766). The page links to the venue's programme, and
`/add-venue` creates it. An importer for such a venue reuses the existing row, as `/scaffold-importer` § 6 says.

**A row reaches this table only by being read.** Every entry was confirmed by fetching the raw HTML or JSON and
reading the events out of it, with no headless browser, per [ADR-007](adr/ADR-007_WEB_SCRAPING_STRATEGY.md). The
[Unanalyzed](#-not-analyzed-yet) table holds the next candidates to open.

**The RA event count is a poor priority signal, and a promoter listing is a good one.** Insel der Jugend was recorded
with 2 RA events and publishes 39 upcoming on its own site. Der Weiße Hase's 17 understate a listing that runs two
months out with full DJ lineups. Three of the richest finds carried no RA count at all: Kulturhaus Peter Edel,
Colosseum and Gärten der Welt. They reached this document only through Loft, Puschen and Landstreicher Konzerte. In
the other direction, DNA. CLUB's 23 RA events appear nowhere in the venue's own calendar. Weight a promoter mention at
least as heavily as an RA count when the next batch is prioritised.

| Name                           | URL                                                                      | Type         | Priority | Comment                                                      |
| ------------------------------ | ------------------------------------------------------------------------ | ------------ | -------- | ------------------------------------------------------------ |
| KAOS Berlin                    | https://kaosberlin.de/veranstaltungen/                                   | Techno Club  | Low      | TEC REST API; 16 events in 2026, none upcoming on 10-03      |
| DSTRKT Club Berlin             | https://www.dstrkt.de/                                                   | Club         | Low      | Wix one-pager; 2 dated events, which is the whole programme  |
| Slaughterhouse                 | https://slaughterhouse-berlin.de/konzerte/                               | Club         | Medium   | Gutenberg paragraphs; 6 dated entries, unsorted              |
| Speiches Rock- und Blueskneipe | http://www.rockradio.de/rr_termine_speiche_werbung_termine_raumerstr.php | Bar          | Medium   | Static table in an iframe; ~45 rows; year-less dates         |
| Alte Münze                     | https://www.alte-muenze-berlin.de/programm/                              | Other        | Low      | TEC REST API; 4 upcoming, mostly workshops; no prices        |
| Konrad Tönz                    | http://www.konradtoenzbar.de/                                            | Bar          | Low      | Site-builder text blob; ~13 DJ nights, no per-event markup   |
| L.U.X                          | https://www.lux-berlin.net/                                              | Bar          | Low      | Hand-written HTML; ~10-day window; year-less dates           |
| Surprise Club                  | https://surprise-berlin.de/events-programs.php                           | Club         | Low      | Hand-built PHP page; only the current weekend                |
| Torhaus Berlin                 | https://torhausberlin.de/                                                | Other        | Low      | Next.js RSC; 3 events; `www.` hits a Vercel checkpoint       |
| Weltwirtschaft                 | https://weltwirtschaft.berlin/                                           | Bar          | Low      | TEC REST API; monthly DJ nights among closure notices        |
| Showfenster-Theater            | https://www.showfenster-show.de/übersichtskalender                       | Theater      | Medium   | Wix Events warmup JSON; 57 dates; `/events` is a 404         |
| WABE                           | https://www.wabe-berlin.info/                                            | Concert Hall | Medium   | Jimdo month pages (`/okt-2026/`); ~14 events a month         |
| ausland                        | https://ausland.berlin/program/all                                       | Club         | Medium   | TYPO3 list; date, time, artists and series per event         |
| Kühlspot Social Club           | https://kuehlspot.com/                                                   | Other        | Medium   | Home page section; dated nights with full line-ups           |
| BKA Theater                    | https://www.bka-theater.de/spielplan/                                    | Theater      | Medium   | Server-rendered list; time and genre; year in a hidden field |
| TIPI am Kanzleramt             | https://www.tipi-am-kanzleramt.de/                                       | Theater      | Medium   | Server-rendered overview; time, sold-out flag, genre         |
| Zebrano Theater                | https://www.zebrano-theater.de/programm.html                             | Theater      | Medium   | Month table; time, price, genre; year from the heading       |
| Mad Monkey Room                | https://t.rausgegangen.de/tickets/shop/mad-monkey-2                      | Comedy Club  | Medium   | rausgegangen ticket shop; ~470 dated shows with prices       |
| Baiz                           | https://radar.squat.net/en/node/1609                                     | Bar          | Medium   | radar group API, 66 upcoming; talks and quizzes too          |
| Labsaal                        | https://labsaal.de/events/                                               | Other        | Low      | Dated list in Lübars; music among readings and workshops     |
| Little Stage Bar               | https://littlestageclubneukoelln.wordpress.com/                          | Bar          | Low      | WordPress.com posts; date and time only in the prose         |
| Brotfabrik                     | https://brotfabrik-berlin.de/veranstaltungen/                            | Other        | Low      | TEC REST API; mostly cinema; shows sit in `Bühne`            |
| Schlosspark Theater            | https://www.schlossparktheater.de/spielplan/kalender.html                | Theater      | Low      | Server-rendered calendar; plays, readings, price ranges      |
| Volksbühne                     | https://www.volksbuehne-berlin.de/konzerte/                              | Theater      | Low      | Concert page; 2 upcoming, with date, time and room           |
| Renaissance-Theater            | https://renaissance-theater.de/spielplan/                                | Theater      | Low      | Day blocks under month headings; plays only                  |
| Spindler & Klatt               | https://www.spindlerklatt.com/club                                       | Club         | Low      | Wix list; party nights with date and time, no line-ups       |
| Jugendclub Café Köpenick       | https://radar.squat.net/en/node/8188                                     | Club         | Low      | radar group; own site hdjk.de links a JS calendar            |
| KuBiZ                          | https://www.kubiz-wallenberg.de/                                         | Other        | Low      | WordPress posts; date in the title; jazz about monthly       |
| Galiläakirche                  | https://galilaea-kirche.de/programm/                                     | Other        | Medium   | Very Simple Event List; ~14 upcoming, mostly concerts        |
| ORWO Haus                      | https://www.orwohaus.de/veranstaltungen/                                 | Concert Hall | Medium   | Events-Manager list; workshops need a filter                 |
| Promenaden Eck                 | https://promenaden-eck-berlin.de/                                        | Bar          | Medium   | SPA; public Supabase `calendar_events` table, 26 upcoming    |
| ZK/U                           | https://www.zku-berlin.org/                                              | Other        | Low      | TYPO3 calendar; club nights among talks and markets          |
| Paloma                         | https://www.palomabar.de/programm.php                                    | Bar          | Medium   | Custom PHP; programm.php, 15 dates in Oct; no year           |
| KREUZWERK                      | https://kreuzwerk.club/events                                            | Techno Club  | Medium   | Ritter Butzke codebase; detail ld+json; 5 events             |
| The Door Club                  | https://thedoor.club/events/                                             | Club         | Low      | WordPress/Oxygen; weekly grid; placeholder Fri/Sat           |
| The Cloud                      | https://thecloud.berlin/events/                                          | Bar          | Medium   | TEC REST API; 6 events to mid-Nov; no prices                 |
| Passionskirche                 | https://www.halle-luja.berlin/kommende-events                            | Concert Hall | Low      | IONOS; free text "<title> am <date> um <time>"               |
| Kallasch&                      | https://kallasch.berlin/programm/                                        | Bar          | Medium   | WordPress MEC; ?mec-ical-feed=1 iCal; expand RRULEs          |
| M-BIA                          | https://www.m-bia.de/                                                    | Techno Club  | Low      | WordPress posts; post date is event date; RA links           |
| Hafenbar Berlin                | https://www.hafenbar-berlin.de                                           | Bar          | Low      | WordPress; 2 weekly party pages, next date only              |
| Marmorbar                      | https://www.marmorbar.com/en/event-list                                  | Bar          | Medium   | Wix Events warmup JSON; prices include fees                  |
| Giri                           | https://giri.berlin/programme/                                           | Bar          | Medium   | WordPress; /programme/?month=; robots bars its JSON          |
| ACUD MACHT NEU                 | https://acudmachtneu.de/programm/                                        | Club         | Medium   | WordPress; /programm/YYYY/M/ pages; time on detail           |
| Studio1111                     | https://studioiiii.de/                                                   | Club         | Low      | Tilda; homepage text blocks; 3 events, no year               |
| Weekend                        | https://www.weekendclub.berlin/upcoming                                  | Club         | Medium   | Squarespace /upcoming HTML; robots bars JSON                 |
| Beate Uwe                      | https://beate-uwe.de/                                                    | Club         | Medium   | WordPress/Elementor; homepage list; RA links                 |
| Backsteinboot                  | https://backsteinboot.org/program-(landing-page)-1                       | Club         | Low      | Cargo; month in HTML; mostly workshops and talks             |

**The 8 rows from Slaughterhouse to Weltwirtschaft came from the [Clubcommission member list](https://www.clubcommission.de/members/)
on 2026-10-03.** Its 187 members also gave 26 rows in [Blocked](#-blocked--deferred). The rest are party collectives,
festivals, agencies and associations, or are recorded already.

**The 22 rows below Weltwirtschaft were opened on 2026-10-04.** The first 18 came from the
[Unanalyzed](#-not-analyzed-yet) table. The last four, from Galiläakirche on, came from theclubmap.com. Four rows do
not point at the venue's own page:

- **Baiz** and **Jugendclub Café Köpenick** point at their radar.squat.net group, which `scraper/radar/` reads. Baiz's
  own programme page is one month of plain text. Café Köpenick's own domain is parked.
- **Mad Monkey Room** points at its rausgegangen ticket shop. Its own Next.js site carries no dates.
- **Showfenster-Theater** points at its overview calendar. Its `/events` page answers 404.

**Promenaden Eck** renders in the browser. The page reads its events from a public Supabase table, with the anon key
that the page itself ships. That is a JSON endpoint behind the page, like the Wix warmup data. Confirm that
[ADR-007](adr/ADR-007_WEB_SCRAPING_STRATEGY.md) covers it before the importer is built.

**Gärten der Welt** set the precedent for the next park- or campus-like source when it was
[imported](#-imported). The row's category decides whether it is programme at all. Its guided tours, workshops, yoga
sessions and handicraft afternoons — 28 of the 41 upcoming rows — are park activities rather than a stage programme.
They are excluded, and the remaining 13 are imported. The rule lives in one predicate, `isProgrammeCategory`, which is
where to revisit it.

_A theater, comedy or arena-scale room is in scope, not only a live-music club. Bar jeder Vernunft set that
precedent. Its programme is imported, and the venue's own genre decides whether a night is a concert or a staged show.
**Comedy clubs and theatres are in scope** ([EVENT_SCOPE.md §5](EVENT_SCOPE.md)). A venue of either kind sitting in
[Blocked](#-blocked--deferred) on that question can be moved here and scaffolded like any other source. That precedent
does **not** extend to classical concerts and orchestras. Those are **deferred, not rejected**. The data shape
differs: an orchestra plus a conductor plus soloists, rather than a headliner with support. `ArtistRole` and the genre
vocabulary must be extended first._

## ⛔ Blocked / deferred

Analyzed, but there is nothing worth importing today. Revisit when the blocker changes. That means a redesigned
website, a headless browser (deferred per [ADR-007](adr/ADR-007_WEB_SCRAPING_STRATEGY.md)), or the Havanna-style
derived-occurrence approach applied to undated recurring nights.

**Resident Advisor is not a source, and it does not become one without RA's written permission (#356).** RA's
[terms of use](https://ra.co/terms) forbid access by any means that RA did not authorise in writing. They name scripts,
bots, crawlers and scrapers (§4.4(f)). §4.4(a) also forbids automated extraction of content or data for commercial
purposes. English law governs the terms. The database right may also protect RA's event listings as a whole. RA has no
public API. The GraphQL endpoint behind the site is internal, and `robots.txt` disallows `/api/`. The terms decide
the question, not `robots.txt`. The rule covers candidate sweeps too. Read RA by hand in a browser, never by script.

A venue that publishes only on RA still has four routes:

1. Ask the venue for a feed of its own, for example an iCal link or a shared calendar.
2. Enter its events by hand, as #334 plans.
3. Link to the venue's RA page. A plain link copies no data.
4. Ask RA for written permission or a data partnership. That is the only route to RA's own data.

The **Unblocked by** column reads `Site change / manual entry` for these venues.

**The RBB Sendesaal is blocked on scope, not on scraping**, and it is the one entry worth explaining. Its concerts sit
in a ROC calendar that is server-rendered and venue-attributed, so the scraping works. It is an orchestral house.
Classical is **wanted but deferred** until `ArtistRole` and the genre vocabulary can represent an orchestra with a
conductor and soloists ([EVENT_SCOPE.md §5](EVENT_SCOPE.md)). Importing it before then would flatten its programme
into headliner-plus-support, which is wrong in a way that is expensive to unpick later. It stays here on purpose, not
through neglect. Answer that question and the importer is a short job. The ROC calendar attributes each concert to a
venue, so `.ConcertListItem-location` is the only filter needed.

Five entries had their blocker _change_ without unblocking, which is worth knowing before anyone spends effort on
them:

- **Fluxbau** and **The Pearl** are no longer JS-only. Both render their programmes on the server. A headless browser
  would not help either: Fluxbau publishes 2 dated events beside undated weekly series, and The Pearl exactly one.
  They are thin-programme problems, not rendering ones.
- **Arena Berlin** moved to The Events Calendar, so scraping it would be trivial. But all 5 entries are trade fairs —
  deGUT, BUCHBERLIN, Einstieg Berlin. The blocker was never the markup.
- **Prachtwerk** gained a Programm page that is empty (Squarespace reports `itemCount: 0`). Its gigs are real, and
  they reach the web only through Loft's listing, which names Prachtwerk more often than any other house.
- **Loft** was blocked on thin, year-less dates. Its redesign turned it into a full cross-venue promoter listing, so
  it is now in [Promoters](#-promoters).

**Artliners Berlin**'s domain stopped resolving altogether. Bohnengold, OXI and Zuckerzauber still redirect to Facebook
or Instagram. Their HTTPS is broken, so they answer only over `http://`.

**Alte Feuerwache THF** is a seasonal interim use at Tempelhof airport. Its 2026 season ends on 17 October. The
calendar is server-rendered, with dates, categories and line-ups, so it is easy to parse. Look at it again when the
next season is announced.

**Ballhaus Berlin** is easy to parse but cannot be fetched. Its `robots.txt` answers HTTP 500, and so does every missing
path, because the server's error page is broken too. RFC 9309 reads a 5xx as a complete disallow, and `RobotsTxtFilter`
enforces that (#887). The `/de/termine/` page lists every event since 2019, with an ISO date attribute, prices and a
ticket link. Scaffold it once `robots.txt` answers 200 or 404.

**Kunstfabrik Schlot** is easy to parse but may not be fetched. Its `robots.txt` reads `User-agent: *` and
`Disallow: *`, which disallows every path, and `RobotsTxtFilter` refuses every request. The WordPress list at
`/programm/` links one page per event, and the year, the time and the price are only on those pages. `schlot.de` shows
only a placeholder. The Events Calendar REST API and iCal are switched off, and the `Product` JSON-LD on an event page
carries a price of `0` USD. Scaffold it once `robots.txt` allows `/programm/` and `/event/`.

**A handful of tickets is not a programme.** The bar for a candidate is whether the source reflects what the venue is
doing, not whether it yields a non-zero count. A shop that sells one series a month presents that series as if it were
the whole programme. Sisyphos looked like that case. Its shop sells `generationS`, one night a month, beside the
T-shirts. The real programme is on the homepage, in iframes that a script fills from
`dashboard.sisyphos-berlin.net/kalender-media/events.json`. A plain fetch of the homepage shows none of it. So look
inside the iframes before you call a venue's programme missing. The importer reads that calendar first (ADR-038). The
shop adds prices, and the fan-run sisy.fan adds each weekend's line-up and set times, with its developer's permission
(ADR-036). The importer reads sisy.fan only from Friday 22:00 to Sunday 04:00.

**The blockers repeat, and not one of them is "the venue is too small".** They are worth reading as a group, because
each is cheap to recognise before spending time on a candidate:

- **The busiest venue on RA has no usable website.** Minimal Bar tops the Berlin listing with 58 events, and
  `minimal-berlin.de` 303-redirects to `minimal-berlin.geo.io`. That is a generic business-directory page with a stock
  bar photo and no programme. Its operator site, `birgit.club/minimal`, carries a stale Christmas note and no
  programme either. This is the sharpest case for manual entry (#334). The club's entire published programme exists
  only on RA, and RA is not a source.
- **Squarespace accounts for two of them.** Bar Neun and Unkompress both serve a large page whose event
  content is client-side only. Bar Neun's 1.1 MB of HTML yields no event text at all. Prachtwerk above is the same
  story.
- **Two sites hand the programme back to RA.** Bulbul Berlin's "Program" button links to `ra.co/clubs/175191`, and its
  own page carries opening hours plus "Special dates (Check: RA)". VOID Club links to RA _for tickets_ while still
  listing the events itself, which is why it is now [imported](#-imported).
- **Neue Nationalgalerie repeats the Hamburger Bahnhof result exactly.** The shared SMB TYPO3 calendar renders cleanly
  and is richly dated, and every entry is a Workshop, Gespräch or Öffentliche Führung. Its 11 RA events are concert
  bookings that never reach the museum's own calendar.

- **Nine venues have no website at all**, only Instagram, Facebook or an RA club page. They are Haus der Visionäre,
  Atemporal, Prisma, Mena Berlin, Phantom Bar, Containerhafen, Rosie's Bar, Süss war gestern and RAW-Gelände. ROSA was
  the tenth and now has its own site.
- **A second RA sweep on 2026-09-13 found the same shapes again.** The eight-week window held 1123 events at
  234 venues. Every venue with two or more events and no row here was opened, seventeen in all, and not one is
  importable. Seven have no site of their own: 90mil, West Germany, Torte Bar, Ipse, Lauschangriff, Studiodb and PKH
  Warehouse. YAAM runs EventON and reports no upcoming events. Musikbrauerei is the only one with dated HTML, a
  hand-edited page that keeps its 2025 entries and holds two upcoming concerts. RA's `Void Hall` is VOID Club, already
  imported.
  Searching for an own domain is still worth it everywhere else. It turned up twelve venue sites this document did not
  have, and exactly one of them — Der Weiße Hase — carries a live programme.
- **arkaoda closed on 2026-08-30.** The Neukölln outpost of the Istanbul bar announced it in the last event it
  published. The programme page still answers 200. It serves the bare template, because there is no programme.
  `V051` removes the venue, its source row and its one past event from both clusters (#1788). It is not listed
  above. Do not add it back.
- **The 2026-10-04 sweep left four rows on a scope decision.** Begine is a women's centre, and Sama32 opens its nights
  to association members only. Whether an event with a closed audience is in scope is a product decision.
  Blackmore's plays classical recitals, which the RBB Sendesaal paragraph above covers. Kunstquartier Bethanien is an
  art centre, and its preview page holds one performance.
- **Two recorded domains are dead.** `kulturbrauerei-berlin.de` answers 523 from Cloudflare. Wendel's `nstp.de` serves
  plain HTTP only, and its TLS handshake fails outright. Bredouille and Tausend moved to new domains, and their rows
  carry them.
- **A site is not a listing.** 8MM, YSY, FOUND, Golden Flamingo, Coco Boule, Atelier Rooftop, Emma Pea, Beach
  Neukölln and Komplex Berlin all render fine and publish no events. Funkhaus Berlin's EVENTS page is an archive that stops in 2019.
- **Three calendars describe something other than a programme.** KINDL renders tours and exhibition openings,
  Genezarethkirche a parish calendar of services and choir rehearsals, and Spielbank Berlin casino promotions. That is
  the Hamburger Bahnhof result, three times over. **DNA. CLUB** is the same shape with an extra twist. Its events do
  live in a machine-readable Elfsight calendar, the format already imported for Neue Zukunft and Humboldthain. But
  that calendar spans 28 locations, including hotels and other clubs. It holds dance classes and workshops rather than
  the club nights RA lists for the venue.
- **Birgit & Bier and Œlgarten publish only undated weekly series.** "Every Thursday Morgan's Dragshow", and a Sangria
  Friday with an empty occurrence list. A Havanna-style derived occurrence would fix it.

One side finding. **Rough Trade** answers 403 to curl while serving the same page to other clients. Its blocker is
the empty Next.js payload rather than the WAF, and a 403 is not evidence that a site is unscrapable.

| Name                              | URL                                                 | Type         | Blocker                                                   | Unblocked by               |
| --------------------------------- | --------------------------------------------------- | ------------ | --------------------------------------------------------- | -------------------------- |
| DNA. CLUB — urban Space           | https://www.dna-artclub.com/events                  | Club         | Elfsight calendar is cross-location classes and workshops | Site change / manual entry |
| Birgit (Birgit & Bier)            | https://www.birgit.club/                            | Techno Club  | Wix one-pager; only undated weekly series                 | Havanna-style occurrences  |
| Prisma                            | —                                                   | Club         | No own site; Instagram and RA only                        | Site change / manual entry |
| Spielbank Berlin                  | https://www.spielbank-berlin.de                     | Other        | Casino promotions; `/events` 404s                         | Site change                |
| Haus der Visionäre                | —                                                   | Bar          | No own site; not in the CdV listing either                | Site change / manual entry |
| 8MM                               | https://www.8mmbar.de/program                       | Bar          | Squarespace; the Program page carries no events           | Site change                |
| Ikii                              | https://ikiiberlin.com/                             | Bar          | GoDaddy splash page; no programme                         | Site change                |
| Atemporal                         | —                                                   | Club         | No own site; RA and DICE only                             | Site change / manual entry |
| Süss war gestern                  | —                                                   | Bar          | Facebook only; the `.de` domain is an unrelated blog      | Site change                |
| Wendel                            | http://www.nstp.de/nstp/frameset-wendel.htm         | Bar          | Café one-pager, no programme; HTTPS handshake fails       | Site change                |
| Funkhaus Berlin                   | https://www.funkhaus-berlin.net/                    | Concert Hall | Blogger site; the events archive ends in 2019             | Site change / promoter     |
| Kraftwerk Berlin                  | https://kraftwerkberlin.de/de/programm              | Concert Hall | Programme page empty between festivals (Atonal, CONTRA)   | Site change / promoter     |
| Jonny Knüppel                     | https://jonnyknueppel.de/                           | Bar          | Last season; leaves its site in October 2026              | Not importable (closing)   |
| Œlgarten                          | https://www.oelgarten.com/en                        | Open Air     | Wix Events; two open-ended weekly series, no occurrences  | Havanna-style occurrences  |
| Rough Trade Berlin                | https://www.roughtrade.com/en-de/events/berlin      | Other        | Next.js store; the events page carries no event data      | Headless browser           |
| Rosie's Bar                       | —                                                   | Bar          | Bar of The Circus Hostel; no listing of its own           | Site change / manual entry |
| Kulturbrauerei Open Air           | https://www.kulturbrauerei.de/                      | Open Air     | Grounds site links out to each house; no own programme    | Covered by the houses      |
| Emma Pea                          | https://emmapea.com/                                | Bar          | Restaurant site; no programme                             | Site change                |
| HÖR Berlin                        | https://hoer.berlin/                                | Other        | Shopify merch shop; its "events" are broadcasts           | Scope decision             |
| Bredouille                        | https://bredouille-bar.de/upcomingevents-bredouille | Bar          | Free-text Squarespace page; nothing after 14 Sep          | Site change                |
| Mena Berlin                       | —                                                   | Club         | No own site; Facebook and RA only                         | Site change / manual entry |
| Atelier Rooftop                   | https://atelierrooftop.de/                          | Club         | Rental one-pager; no programme                            | Site change / promoter     |
| Coco Boule                        | https://cocoboule.com/                              | Bar          | One-pager; no dated content                               | Site change                |
| YSY                               | https://www.ysyberlin.de/calendar                   | Club         | `/calendar` says to follow Instagram instead              | Site change                |
| Phantom Bar Berlin                | —                                                   | Bar          | No own site; RA only                                      | Site change / manual entry |
| KINDL                             | https://www.kindl-berlin.com/news                   | Concert Hall | Art centre calendar is tours and openings, not concerts   | Promoter feed              |
| Containerhafen                    | —                                                   | Open Air     | No own site; RA only                                      | Site change / manual entry |
| Golden Flamingo                   | http://goldenflamingo.de/                           | Open Air     | Restaurant page; "Website befindet sich im Aufbau"        | Site change                |
| FOUND                             | https://foundberlin.com/                            | Club         | Splash page; address and e-mail only                      | Site change                |
| Beach Neukölln                    | https://www.beach-neukoelln.de/                     | Open Air     | Rental and public-viewing marketing, not a programme      | Promoter feed              |
| RAW-Gelände                       | —                                                   | Open Air     | Compound, not a venue; `raw-gelaende.de` is gone          | Covered by the houses      |
| Genezarethkirche                  | https://www.mlg-neukoelln.de/events                 | Concert Hall | Parish calendar: services, rehearsals, courses            | Promoter feed              |
| Minimal Bar                       | https://minimal-berlin.geo.io/                      | Techno Club  | No own site; redirects to a geo.io business page          | Site change / manual entry |
| Sensorium                         | http://www.sensorium-club.com                       | Techno Club  | Opened in March 2026 in the former AVA; RA only           | Site change / manual entry |
| Insomnia                          | http://www.insomnia-berlin.de                       | Club         | WAF returns 403 with an empty body to scripts             | Request headers            |
| Bulbul Berlin                     | https://www.bulbulberlin.de                         | Club         | Own site links out to RA for the programme                | Site change / manual entry |
| Bar Neun                          | http://barneun.de                                   | Bar          | Squarespace; 1.1 MB of HTML, no event text                | Headless browser           |
| Unkompress                        | https://www.unkompress.berlin/                      | Club         | Squarespace; event content is client-side only            | Headless browser           |
| Neue Nationalgalerie              | https://www.smb.museum/                             | Concert Hall | SMB calendar is tours and workshops, not concerts         | Promoter feed              |
| Gestrandet a. d. Jannowitzbrücke  | https://strandhaus-berlin.de/gestrandet/events      | Open Air     | Season over; last event 5 Sep 2026                        | 2027 season / re-check     |
| Fluxbau                           | https://www.fluxfm.de/fluxbau                       | Club         | Server-rendered now, but 2 dated events + series          | More events / occurrences  |
| Sage Club                         | https://www.sage-club.de/                           | Club         | Closed; the last party was on 28 Dec 2023                 | Not importable (closed)    |
| The Pearl                         | https://thepearl-berlin.de/                         | Club         | `/programm/` renders now, but holds one event             | More events                |
| Prince Charles                    | https://princecharlesberlin.com/                    | Club         | No own listings; links out to Resident Advisor            | Site change / manual entry |
| Artliners Berlin                  | —                                                   | Club         | Domain no longer resolves; site gone                      | New site                   |
| Prachtwerk                        | https://www.prachtwerkberlin.com/                   | Bar          | Has a Programm page now, but it is empty                  | Site change                |
| Wiener Blut                       | https://www.wienerblut.org/                         | Bar          | Impressum-only page                                       | Site change                |
| Arena Berlin                      | https://www.arena.berlin/veranstaltungen/           | Concert Hall | Tribe calendar now, but trade fairs only                  | Site change / promoter     |
| Frannz Salon                      | https://frannz.eu/                                  | Club         | Not a separate listing; a floor of Frannz nights          | Covered by FRANNZ          |
| Theater des Westens               | https://www.stage-entertainment.de/                 | Theater      | Stage portal; one musical, dates in ticket shop           | Site change                |
| RBB Sendesaal                     | https://www.roc-berlin.de/kalender/                 | Concert Hall | Scrapable; deferred pending the classical scope decision  | Scope decision             |
| Zentraler Festplatz               | https://berliner-festplatz.de/                      | Open Air     | Rental ground; "Events" page is social embeds             | Site change                |
| Fahrradkeller                     | http://www.im-fahrradkeller.de/                     | Other        | Private house concerts, by invitation only                | Not importable             |
| Anita Berber                      | —                                                   | Bar          | Private hire only since December 2021                     | Not importable (closed)    |
| Clubliebe                         | —                                                   | Club         | An association, not a venue                               | Not importable (no venue)  |
| Fraktion Nimmersatt               | —                                                   | Bar          | A collective, not a venue                                 | Not importable (no venue)  |
| Funkloch                          | —                                                   | Bar          | `funkloch.berlin` does not resolve; Facebook only         | Site change / manual entry |
| Villa Neukölln                    | http://www.villaneukoelln.de/                       | Bar          | Contact one-pager; no programme                           | Site change                |
| Ficken 3000                       | https://www.ficken3000.com/                         | Bar          | Domain serves a 428-byte stub page                        | Site change                |
| All Club                          | https://allberlin.de/all-club                       | Club         | Youth club (14–27); Contao event list is empty            | More events / re-check     |
| Böse Buben                        | https://www.boese-buben-berlin.de/events.html       | Club         | Fetish sex parties only; no music programme               | Not importable             |
| Golgatha                          | https://golgatha-berlin.de/                         | Open Air     | Beer garden; no programme page; winter break              | Not importable             |
| Guesstimate                       | https://guesstimate.de/                             | Other        | Music agency and studio, not a venue                      | Not importable             |
| Interkosmos                       | https://www.interkosmos.cc/                         | Bar          | WP REST `events`; 2 stale entries without dates           | More events / re-check     |
| Kumpelnest 3000                   | http://www.kumpelnest3000.com/                      | Bar          | Static site; last event 2017; news on Facebook            | Site change                |
| Nuke Club                         | http://nukeclub.berlin/                             | Club         | Lost its venue in 2021; no Berlin site since              | Not importable (closed)    |
| Per Aspera                        | https://per-aspera.net/                             | Theater      | Production company, not a venue; last event 2013          | Not importable             |
| PlaceOne                          | https://www.placeone.eu/                            | Other        | Rooftop rental; public dates are dance-school parties     | Not importable             |
| Plötze                            | https://ploetze.berlin/                             | Open Air     | WordPress frozen since 2021; programme is stale           | More events / re-check     |
| Roberta Bar                       | http://www.roberta-bar.de/                          | Bar          | Site offline: hosting provider error page                 | Site change / manual entry |
| Silverwings                       | https://silverwings.de/program.html                 | Club         | Contao list empty; programme only on Facebook             | Site change                |
| solar                             | https://solar-berlin.de/                            | Bar          | Sky bar; the only event is a Christmas brunch             | More events / re-check     |
| Taff Club                         | https://www.taff-club.de/                           | Club         | Holding page still up on 2026-10-08; no programme         | Re-check after relaunch    |
| Trompete                          | https://www.trompete-berlin.de/                     | Club         | Squarespace; weekly Thu/Sat hours, no dates               | Site change / manual entry |
| Village Berlin                    | https://wearevillage.org/kalender                   | Other        | Queer community centre; workshops, no music               | Not importable             |
| Zu Mir Oder Zu Dir                | https://www.zumiroderzudir.com/                     | Bar          | Archived one-pager; opening hours only                    | Site change / manual entry |
| ://about blank                    | https://aboutblank.li/                              | Techno Club  | `/next` carries no events in the HTML                     | Site change                |
| Bohnengold                        | https://bohnengold.de/                              | Bar          | Domain redirects to Facebook                              | Site change                |
| C115                              | https://www.c115.club/                              | Techno Club  | Mailing-list splash page; no programme                    | Site change                |
| ELSE                              | https://www.else.tv/events-tickets                  | Techno Club  | Own Wix site, but no dated events in the HTML             | Site change                |
| Hamburger Bahnhof                 | https://www.smb.museum/                             | Open Air     | Museum programme is guided tours, not concerts            | Promoter feed              |
| Lokschuppen                       | https://lokschuppen-berlin.com/                     | Techno Club  | Readymag site; the content is JS-only                     | Headless browser           |
| OXI & OXI Garten                  | https://oxi-club.de/                                | Techno Club  | Domain redirects to Instagram                             | Site change                |
| RSO                               | https://rso.berlin/                                 | Techno Club  | Domain returns 404; no own site found                     | Site change                |
| SchwuZ                            | https://www.schwuz.de/                              | Techno Club  | Insolvent; parties as guest at other venues               | New venue                  |
| Sisyfass                          | —                                                   | Bar          | No website; Instagram and RA only                         | Site change                |
| Strandbad Grünau                  | https://strandbadgruenau.de/                        | Open Air     | `/events/` is rental marketing, not a programme           | Promoter feed              |
| Zuckerzauber                      | https://zuckerzauber.info/                          | Bar          | Domain redirects to Facebook                              | Site change                |
| Sameheads                         | http://www.sameheads.com                            | Bar          | Headless WordPress; label and art pages, no programme     | Site change / manual entry |
| 90mil                             | —                                                   | Open Air     | Farewell festival in May 2026; site demolished            | Not importable (closed)    |
| Taborkirche                       | https://www.taborkirche.de                          | Concert Hall | Church; RA's own link is a Leipzig parish site            | Site change                |
| West Germany                      | —                                                   | Club         | No own site; RA only                                      | Site change / manual entry |
| YAAM                              | https://yaam.de/programm/                           | Club         | WordPress + EventON; calendar says no upcoming events     | Site change / re-check     |
| Torte Bar                         | —                                                   | Bar          | No own site; Instagram and RA only                        | Site change / manual entry |
| Ipse                              | —                                                   | Open Air     | Back as IPSE.UFER; no own site, RA only                   | Site change / manual entry |
| Musikbrauerei                     | https://musikbrauerei.com/events/                   | Concert Hall | Hand-edited Kadence page; 2025 entries kept, 2 upcoming   | More events / re-check     |
| P61 Gallery                       | https://www.p61gallery.com/programm                 | Other        | Digital-art museum; programme is exhibitions              | Scope decision             |
| Chausseestrasse 131               | https://www.chausseestrasse131.com/                 | Club         | Wix; "WEBSITE UNDER CONSTRUCTION"                         | Site change                |
| SaltyAcid Space                   | http://saltyacid.space/                             | Open Air     | One-pager with a video; PROGRAM link goes nowhere         | Site change                |
| Lauschangriff                     | —                                                   | Bar          | No own site; Facebook and RA only                         | Site change / manual entry |
| Studiodb                          | —                                                   | Other        | No own site; Instagram and RA only                        | Site change / manual entry |
| PKH Warehouse                     | —                                                   | Other        | No own site; RA only                                      | Site change / manual entry |
| Badenscher Hof Jazzclub           | https://www.badenscher-hof.de/                      | Club         | Duda one-pager; programme only as monthly PNG images      | Site change                |
| Mokum                             | —                                                   | Bar          | No own site; Facebook only                                | Site change                |
| Komplex Berlin                    | https://komplex.berlin/                             | Other        | Adobe Portfolio rental site; no programme                 | Site change / manual entry |
| Zielona Góra                      | —                                                   | Bar          | radar only; anniversary nights, no running programme      | More events                |
| ciao ciao Bar                     | —                                                   | Bar          | No own site; Instagram and RA only                        | Site change / manual entry |
| JIWAR                             | —                                                   | Other        | No own site; Instagram only                               | Site change / manual entry |
| Ashawo Cafe                       | —                                                   | Other        | No own site; events on Eventbrite                         | Site change / manual entry |
| GöRE Comedy                       | https://www.eventbrite.com/cc/gore-shows-4785723/   | Comedy Club  | Eventbrite only; its terms likely forbid scraping         | Eventbrite API token       |
| SaliGari Bar                      | —                                                   | Bar          | No own site; Facebook only; mostly comedy nights          | Site change / manual entry |
| 800A Bar & Cabaret                | https://www.800aberlin.com/                         | Bar          | Two dated shows on the home page; the rest on Instagram   | More events                |
| Tipsy Bear                        | https://www.tipsybearberlin.com/events              | Bar          | Squarespace; one highlight, then weekly bingo and karaoke | More events                |
| Pastiche                          | https://pasticheinternational.com/                  | Bar          | Cargo month pages; one event in October                   | More events                |
| SOLID                             | https://solid.stadtlandladen.org/events             | Bar          | Wix Events; talks, films and food nights, no music        | Scope decision             |
| Zemin                             | https://www.zeminberlin.de/                         | Other        | Art space; the programme block shows July to September    | Site change                |
| Slap'd                            | https://slapd.de/                                   | Club         | Event-agency site, rendered in the browser; no programme  | Site change                |
| Alte Feuerwache THF               | https://alte-feuerwache-thf.de/calendar             | Other        | Clean Next.js list, but the season ends on 2026-10-17     | Next season                |
| Ballhaus Berlin                   | https://ballhaus-berlin.de/de/termine/              | Club         | `robots.txt` answers 500, so every fetch is refused       | Site change                |
| Kunstfabrik Schlot                | https://kunstfabrik-schlot.de/programm/             | Club         | `robots.txt` disallows every path (`Disallow: *`)         | Site change                |
| Kunstquartier Bethanien           | https://kunstquartier-bethanien.de/vorschau         | Other        | Art centre; the preview page holds one performance        | Scope decision             |
| Begine                            | https://www.begine.de/programm/aktueller-monat.html | Other        | Women's centre; groups and courses, a few parties         | Scope decision             |
| Blackmore's – Berlins Musikzimmer | https://www.blackmores-musikzimmer.de/de/programm   | Concert Hall | Scrapable Contao list, but classical recitals             | Scope decision             |
| Sama32                            | https://radar.squat.net/en/node/1614                | Bar          | radar group; nights for association members only          | Scope decision             |
| Revier Südost                     | https://www.reviersuedost.de/programm               | Open Air     | Wix Events; the open-air season ends on 2026-10-09        | Next season                |
| Kulturhaus Spandau                | https://kulturhaus-spandau.de/programm/             | Other        | The browser fills the list; the HTML has teasers only     | Headless browser           |
| Clärchens Ballhaus                | https://claerchensball.haus/programm/               | Club         | Calendar JSON in the page, but 403 to the importer        | Request headers            |
| Mein Freund Harvey                | https://www.meinfreundharvey.com/                   | Bar          | Jimdo behind Cloudflare; 403 to every script              | Request headers            |
| Block1                            | https://block1berlin.com/                           | Other        | SPA; events hard-coded in the bundle, one upcoming        | More events / re-check     |
| Villa Kuriosum                    | https://www.villakuriosum.net/                      | Other        | TEC calendar with no upcoming events                      | More events / re-check     |
| Salon Wellenmaschine              | https://www.diewellenmaschine.com/                  | Other        | Event-agency site; a music series, no dated programme     | Site change                |
| diskoBabel                        | https://diskobabel.de/                              | Club         | Association site; lists its collectives, no programme     | Site change / manual entry |
| Mom's Limousine Service           | —                                                   | Bar          | No own site; Instagram and RA only                        | Site change / manual entry |
| M01                               | —                                                   | Techno Club  | No own site; RA only                                      | Site change / manual entry |
| Loft 6                            | —                                                   | Other        | No own site; RA only                                      | Site change / manual entry |
| 60 Hz                             | —                                                   | Bar          | No own site; `60hz.berlin` does not resolve               | Site change / manual entry |

## 📣 Promoters

A promoter is a different kind of source from a venue. It lists events at many houses and has no house of its own.
[ADR-043](adr/ADR-043_ONE_MAIN_SOURCE_PER_VENUE_AND_ENRICHMENT_SOURCES.md) gives a promoter two roles.

**At a venue with its own importer, a promoter is an enrichment source.** It fills empty fields, such as the support
act or the set times, on events that the venue's page lists. It never adds an event. An event matches on venue, date
and a start time within one hour. Most promoter shows are at such venues: about 30 of Puschen's 35.

**At a venue with no own publication, a promoter can be the main source**, with permission. One thin source per venue
keeps only that venue's shows and has its own `sourceId` prefix (#2557). Zig Zag and the Velomax halls use the same
pattern.

Neither role exists in the importer yet. The rows below stay here until it does.

**Tag der Clubkultur** runs only once a year. The Clubcommission festival week in October 2026 listed 130 events at
about 80 places. About half of these places are already imported. Many events are panels, workshops, exhibitions or
screenings, which are out of scope. Use the programme to find new venues.

**Lachkater** runs stand-up shows at three Berlin places. The Saturday mixed show is at Tiffany Club, which is
imported. The Tuesday show is at Al Hamra in Prenzlauer Berg, which has no row yet. The open-air show at Luftschloss
Tempelhofer Feld ended its season on 2026-09-30. The site is a React SPA. Its show dates are hard-coded in the
JavaScript bundle, and tickets sell through Universe.

| Name                   | URL                                            | Blocker                                           | Unblocked by       |
| ---------------------- | ---------------------------------------------- | ------------------------------------------------- | ------------------ |
| Loft                   | https://loft.de/                               | Cross-venue (see note)                            | Enrichment sources |
| Greyzone Tickets       | https://www.greyzone-tickets.de/               | Contact info only; ticket service, not a listing  | —                  |
| Landstreicher Booking  | https://landstreicher-booking.de/              | Cross-venue (see note)                            | Enrichment sources |
| Landstreicher Konzerte | https://landstreicher-konzerte.de/             | Cross-venue, cross-city; also has `/venue/` pages | Enrichment sources |
| Puschen                | https://puschen.net/berlin/                    | Cross-venue (see note)                            | Enrichment sources |
| Trinity Music          | https://trinitymusic.de/                       | Cross-venue (see note)                            | Enrichment sources |
| Sofar Sounds           | https://www.sofarsounds.com/cities/berlin      | Secret venues; only the district is published     | Not importable     |
| Tag der Clubkultur     | https://tagderclubkultur.berlin/programm/      | Cross-venue; one festival week a year (see note)  | Not importable     |
| Jazz am Helmholtzplatz | http://www.jazzamhelmholtzplatz.com/           | Cross-venue; venue only in free text              | Enrichment sources |
| Tränenpalast           | https://traenenpalast.tickettoaster.de/tickets | Talk-show agency; all dates at other venues       | Not importable     |
| Lachkater              | https://www.lachkater.com/                     | Cross-venue; dates only in the SPA's JS bundle    | Enrichment sources |

## ❓ Not analyzed yet

New candidates land here first. Check for a server-rendered programme, then move the row into
[Ready](#-ready-to-implement) or [Blocked](#-blocked--deferred). A row belongs here only until someone opens it — the
URL is recorded, nothing more. A venue's type is a first guess. Correct it against the venue's own site when the row
is opened.

All rows come from the promoter sweep of 2026-10-04 (see the list below). **Found via** names the promoter site that
showed a show there. A URL comes from a web search. Nobody opened it yet. A dash means that the search found
no site of the venue's own.

| Name                  | URL                                                          | Type         | Found via                      |
| --------------------- | ------------------------------------------------------------ | ------------ | ------------------------------ |
| Baketown              | https://baketown.berlin/                                     | Other        | ZART Agency                    |
| Ernst-Reuter-Saal     | —                                                            | Concert Hall | New Berlin Konzerte            |
| Friedrichstadt-Palast | https://www.palast.berlin/gastspiele/                        | Theater      | Antenne Brandenburg            |
| Haus der Statistik    | https://hausderstatistik.org/programm/                       | Other        | Crunch Tapes                   |
| HAU Hebbel am Ufer    | https://www.hebbel-am-ufer.de/en/programme/schedule-tickets/ | Theater      | Powerline Agency, Musikexpress |
| K19                   | —                                                            | Club         | Patchanka Booking              |
| Kiezkapelle           | https://www.kiezkapelle.de/programm/                         | Other        | Puschen                        |
| Paradox Music Hall    | —                                                            | Club         | Diffus, Landstreicher Konzerte |
| Trixxter              | —                                                            | Bar          | Metal Hammer                   |
| Wowsville             | —                                                            | Bar          | Crunch Tapes                   |
| Zita Club & Bar       | https://zita-club.de/                                        | Club         | Channel Music                  |

Check these points when a row is opened:

- **Trixxter** is the former Trickster under a new spelling. Old listings show Trickster as closed. Trixxter has
  shows on 2026-11-05 and 2026-11-26 (Rolling Stone, Musikexpress), so it is open. One Metal Hammer event spells it
  "Trixxxstar".
- **Paradox Music Hall** is at Ganghoferstraße 10 in Neukölln (berlin.de). Diffus lists yola there on 2026-10-16,
  moved up from the Prachtwerk. Landstreicher Konzerte has a venue page for it. The search found no site of its own.
- **Ernst-Reuter-Saal** has one New Berlin Konzerte show. The page does not give the district.
- **Zita Café & Weingarten** (<https://zita-cafe.de/>) has the same address as Zita Club & Bar. Channel Music lists
  both. Only the club has a row.

Three venues the sweep found are deliberately left out. **Narva Lounge** is the lounge room of Matrix Club Berlin, and the
Matrix importer covers it. **arkaoda** closed (see above). Crunch Tapes still lists shows there, but the listing is
stale. **Die Spirale**, a youth and culture centre in Wilmersdorf, is out of scope. Its own domain is dead, and its
concerts appear only in promoter and aggregator listings.

Three more bars host comedy several nights a week but have no working site to record: KARA KAS Bar, Valentin Stüberl
and Z-Bar. The aggregator Comedy in English lists all three. Its Events Calendar API gives a venue and an address for
each show.

Where candidates come from, and what is deliberately left out:

- **Resident Advisor** (<https://de.ra.co/events/de/berlin>). One eight-week window carried 1142 events at 201
  distinct venues, **66 of them new to this document**. RA's own busiest Berlin room — Minimal Bar, 58 events — was not
  recorded here at all, and it has no website of its own. It sits in [Blocked](#-blocked--deferred). That sweep read
  RA's `eventListings` GraphQL query by script, and RA's terms forbid that. A later sweep reads RA by hand, in a
  browser.
- **The promoter listings** — Loft, Puschen, Landstreicher Booking and Trinity Music. They add venues RA does not
  surface, and they are seated or open-air houses rather than clubs. Chasing down the Gärten der Welt URL surfaced
  **Landstreicher Konzerte**, a separate outfit from Landstreicher Booking, now filed under
  [Promoters](#-promoters) on the same per-event-venue limitation.
- **tipBerlin** (<https://www.tip-berlin.de/ausgehen/konzerteclubs/>), the city magazine's calendar. It is a
  WordPress site, and `/wp-json/wp/v2/event` returns every event with a `location-<slug>` class. Cloudflare Turnstile
  answers curl with 403, so the sweep ran in a browser. It held 6209 events at 363 locations with a music category.
  Classical halls and churches were left out, and so was Potsdam. tipBerlin itself is not a source, because its texts
  are editorial.
- **XCEED** (<https://xceed.me/de/berlin/events>), a nightlife ticket marketplace. Its open API
  `event-b2c.xceed.me/v2/events?cities[0]=berlin` held 31 events at 11 venues on 2026-09-30. Every venue with a
  programme was already recorded, so XCEED added nothing.
- **Stressfaktor** (<https://stressfaktor.squat.net/termine>), the calendar of Berlin's left subculture. It shows
  the events of radar.squat.net, and an Anubis proof-of-work wall protects its HTML. Do not scrape it. The radar JSON
  API `radar.squat.net/api/1.2/search/events.json` is outside the wall, and radar invites reuse. The `music-concert`
  and `party` categories held 220 Berlin events at about 50 locations. 159 of them came from the venue's own radar
  group, and those groups gave the eight new rows. KØPI, Abstand and Drugstore import through the radar reader
  (#2166). The other 61 came only through the Stressfaktor group, at one-off locations. Schokoladen, Loge, Supamolly, SO36, Clash, Neue Zukunft, ausland and ://about blank were already
  recorded.
- **Tag der Clubkultur** (<https://tagderclubkultur.berlin/programm/>), the Clubcommission festival week. The 2026
  programme on 2026-10-01 held 130 events at about 80 places. It gave one imported venue and twelve Blocked rows.
  These places were left out as out of scope: two cinemas, a museum and a youth dance theatre. Also two galleries, a
  healing space, a hammam, a radio station and a headphone shop. A comedy club was also left out, but comedy is in
  scope (`docs/EVENT_SCOPE.md` §5). See #352. Two more places have no fixed address, and no search identified one
  more.
- **theclubmap.com** (<https://www.theclubmap.com/music-style/>), a club guide for Berlin and eight other cities. It is a
  WordPress site with an EventON calendar. On 2026-10-04, `/wp-json/wp/v2/event_location` held 632 locations and
  `/wp-json/wp/v2/ajde_events` held 183 events. The sweep opened every Berlin location with two or more events and no
  row here. It also opened the venues on the guide's techno district pages. That gave four Ready rows and eight Blocked
  rows. Valley and the Baergarten are part of Revier Südost, and Napoleon Komplex is the Komplex Berlin row. Two
  museums were left out, Dark Matter and Hamburger Bahnhof. So were two rental spaces, Freischwimmer and Villa K. So
  were three event grounds: Teufelsberg, Malzfabrik and Frachtkante at Tegel airport. The guide's black-music page names
  Auster Club, Cheshire Cat, St. Georg, Bohannon and Hangar 49. That page dates from January 2024, and no event in the
  calendar names those places. They were not checked.
- **The promoter websites** in `docs/promoters/REVIEWED.tsv`. On 2026-10-04, 132 rows had a website, and 127 distinct
  sites were fetched once each, plus one events page. Four rows have only an Instagram or Facebook page, and those
  were not opened. Most Berlin venues on these pages are already recorded. The sweep gave the eleven rows above. Many
  sites render their dates in JavaScript or give only a city, so they named no venue. These places were left out as
  out of scope: a cinema (Kino International) and the classical halls (Philharmonie, Kammermusiksaal, UdK
  Konzertsaal). Also the children's venues FEZ and Theater an der Parkaue (see `docs/EVENT_SCOPE.md` §3.5). Also a
  hotel, two radio studios and the event grounds at Tempelhof and the Olympiapark. A media calendar with one event
  was not enough for a row: BLO Kantine and OGH (one each). Milchsalon's "Audimax" date is a children's show, so it is
  out of scope as well.

**Excluded on purpose, so a later sweep does not re-litigate them.** An RA venue with a single event in the window,
unless a promoter listed it too. A one-off booking is not evidence of a programme, and the
[Sisyphos rule](#-blocked--deferred) applies. Also every `TBA …` pseudo-venue — about 60 events at secret locations,
Telegram-only addresses and boat terminals. Also bare addresses and landmarks used as festival grounds:
`Straße des 17. Juni`, `Brandenburger Tor`, `Tempelhof Airport`. Also hotels and hostels, and venues outside Berlin
that RA files under the Berlin area anyway — Waschhaus in Potsdam, Völklingen Ironworks in Saarland.

Two source lists were worked through completely and are no longer reproduced here:

- The 48 venues of the **Trinity Music location directory** (<https://trinitymusic.de/locations>). 17 were already
  imported, and the other 31 are filed above. The venue-level rows carry this list now. The **Trinity Music** promoter
  source itself is deferred (see [Promoters](#-promoters)), and a venue site usually yields richer data than a
  promoter listing anyway.
- The **techno-club cluster** — 26 clubs and bars, of which 13 turned out to be scrapable. The other 13 publish only
  through Instagram, Facebook or Resident Advisor. RA is not a source (see [Blocked](#-blocked--deferred)), so those 13
  wait for a site of their own or for manual entry. RA names three [Blocked](#-blocked--deferred) venues whose blocker
  is precisely "no own site": ://about blank, OXI and RSO. ELSE has a site now, with no dated events in it. Beside them it carries 66 venues that
  this document does not record at all.

---

## TODO

Source-discovery and new-importer tasks are tracked in the [coverage epic #351](https://github.com/enorm-labs/event-junkie/issues/351) and its sub-issues.
