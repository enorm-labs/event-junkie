# Accepted limitations

<!-- Generated from AcceptedLimitations.kt by AcceptedLimitationsTest. Do not edit by hand. -->

What each venue's source does not publish, declared next to its parser (#715). A data-quality finding matching a row here is
**known and accepted**, not a defect — see [`/data-quality-audit`](../../.github/prompts/data-quality-audit.prompt.md).

A declaration says the source is silent, not that the column is always null: where the parser derives a value anyway, the reason
says so.

| Source                | Aspect             | Why the source is silent                                                                                                                         | Issue |
| --------------------- | ------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------ | ----- |
| `AEDEN`               | `PRICE`            | the month page carries no prices                                                                                                                 | —     |
| `AEDEN`               | `PER_EVENT_PAGE`   | the month page links no page per night                                                                                                           | —     |
| `ADMIRALSPALAST`      | `GENRE`            | the house classifies by staging format (Konzert, Lesung) and names no musical style anywhere                                                     | —     |
| `AMT`                 | `ARTISTS`          | the DJ line separates names with spaces and nothing else, so it cannot be split apart reliably                                                   | —     |
| `ARCANOA`             | `PER_EVENT_PAGE`   | the whole programme is one hand-coded page                                                                                                       | —     |
| `ARKAODA`             | `DOORS_TIME`       | a set time is written into the prose blurb, which has no reliable delimiter                                                                      | —     |
| `ARKAODA`             | `START_TIME`       | a set time is written into the prose blurb, which has no reliable delimiter                                                                      | —     |
| `ARKAODA`             | `PRICE`            | a door price is written into the prose blurb, which has no reliable delimiter                                                                    | —     |
| `ARKAODA`             | `SOLD_OUT`         | the venue runs no ticket integration and has no field for the sold-out state                                                                     | —     |
| `ARKAODA`             | `GENRE`            | the venue has no structured genre field                                                                                                          | —     |
| `BAR_JEDER_VERNUNFT`  | `DOORS_TIME`       | the calendar and the show pages state one Beginn time and never an Einlass                                                                       | —     |
| `BADEHAUS`            | `ARTISTS`          | the venue publishes no roster; for a concert the title is taken as the act and a Support: subtitle as the rest                                   | —     |
| `BADEHAUS`            | `EVENT_TYPE`       | the venue publishes no category; the type is inferred from the title and subtitle                                                                | —     |
| `BINUU`               | `EVENT_TYPE`       | the SvelteKit payload carries no category field, and neither does anywhere else on the site                                                      | —     |
| `CASSIOPEIA`          | `PAGINATION`       | only the first page of the listing is read                                                                                                       | —     |
| `CLASH`               | `PER_EVENT_PAGE`   | the `event` post type is not exposed over the WordPress REST API and the numeric permalinks 404                                                  | —     |
| `CLASH`               | `DOORS_TIME`       | the homepage listing is the whole source and carries no doors time                                                                               | —     |
| `CLASH`               | `PRICE`            | the homepage listing is the whole source and carries no price                                                                                    | —     |
| `CLASH`               | `GENRE`            | the homepage listing is the whole source and carries no genre                                                                                    | —     |
| `CLASH`               | `PROMOTERS`        | the homepage listing is the whole source and names no promoter                                                                                   | —     |
| `CLASH`               | `EVENT_TYPE`       | the site has no category field; the type is inferred from the title, defaulting to a concert                                                     | —     |
| `CLUB_DER_VISIONAERE` | `START_TIME`       | the venue never publishes one; a from-HH:mm marker on the act line is that act's set time                                                        | —     |
| `CLUB_DER_VISIONAERE` | `EVENT_TYPE`       | the venue publishes no category of its own; every listing is a club night                                                                        | —     |
| `CLUB_DER_VISIONAERE` | `PER_EVENT_PAGE`   | the programme page is the source for every night                                                                                                 | —     |
| `CLUB_OST`            | `DESCRIPTION`      | the venue programmes through Resident Advisor and leaves the CMS description empty on every event                                                | —     |
| `CLUB_OST`            | `EVENT_TYPE`       | the listing carries no category; every card is a flyer, a title, a start time and a ticket link                                                  | —     |
| `CLUB_OST`            | `GENRE`            | the listing carries no genre                                                                                                                     | —     |
| `CLUB_OST`            | `PRICE`            | the listing carries no price; tickets are sold on Resident Advisor                                                                               | —     |
| `CLUB_OST`            | `ARTISTS`          | the listing carries no lineup, though the CMS holds an empty div where one would go                                                              | —     |
| `CLUB_OST`            | `DOORS_TIME`       | the listing carries one time per night and no doors time                                                                                         | —     |
| `COLOSSEUM`           | `EVENT_TYPE`       | `categories` is empty on every event, so the type is inferred from the title and subtitle                                                        | —     |
| `COLOSSEUM`           | `DOORS_TIME`       | the Wix payload carries one `startDate` per event, and the detail page repeats one boilerplate Einlass line for all of them                      | —     |
| `COLOSSEUM`           | `GENRE`            | the house names no musical style anywhere                                                                                                        | —     |
| `COLOSSEUM`           | `ARTISTS`          | no support-act convention exists in the subtitles, and a title is as often an event name as a performer's                                        | —     |
| `COLUMBIAHALLE`       | `PER_EVENT_PAGE`   | the venue's own iCal export keys the event on the same Contao id and points back at the listing anchor                                           | —     |
| `COSMIC_COMEDY`       | `PRICE`            | `cost` and `cost_details` are empty on every event                                                                                               | —     |
| `CRACK_BELLMER`       | `EVENT_TYPE`       | the venue emits no category at all; the type is read from the title and then the genre line                                                      | —     |
| `CRACK_BELLMER`       | `DOORS_TIME`       | the venue publishes no doors time                                                                                                                | —     |
| `CRACK_BELLMER`       | `PRICE`            | the venue publishes no prices                                                                                                                    | —     |
| `CRACK_BELLMER`       | `TICKET_URL`       | the venue links no ticket shop                                                                                                                   | —     |
| `DER_WEISSE_HASE`     | `PRICE`            | the club publishes no prices anywhere, not even at the door                                                                                      | —     |
| `DER_WEISSE_HASE`     | `GENRE`            | the club publishes no genre                                                                                                                      | —     |
| `DER_WEISSE_HASE`     | `DOORS_TIME`       | the club publishes no doors time                                                                                                                 | —     |
| `DER_WEISSE_HASE`     | `EVENT_TYPE`       | the club states no category anywhere and programmes nothing but DJ nights, so the type is fixed rather than inferred                             | —     |
| `DER_WEISSE_HASE`     | `PER_EVENT_PAGE`   | the club sells through Resident Advisor and the listing links off-site                                                                           | —     |
| `DER_WEISSE_HASE`     | `CANCELLATION`     | a cancelled night is taken off the page rather than labelled                                                                                     | —     |
| `DUNCKER`             | `PER_EVENT_PAGE`   | the whole programme is one hand-coded page                                                                                                       | —     |
| `ESCHSCHLORAQUE`      | `PRICE`            | entry is settled at the door and the venue names no figure                                                                                       | —     |
| `ESCHSCHLORAQUE`      | `TICKET_URL`       | the venue runs no ticket shop                                                                                                                    | —     |
| `ESCHSCHLORAQUE`      | `SOLD_OUT`         | the venue flags nothing sold out                                                                                                                 | —     |
| `ESCHSCHLORAQUE`      | `CANCELLATION`     | the venue flags nothing cancelled, so every event stays scheduled                                                                                | —     |
| `ESCHSCHLORAQUE`      | `EVENT_TYPE`       | the programme mixes DJ nights, live sets, bingo and theatre with no kind field anywhere                                                          | —     |
| `ESCHSCHLORAQUE`      | `DOORS_TIME`       | the venue publishes a single ab-HH-Uhr start and never a separate doors time                                                                     | —     |
| `FESTSAAL`            | `EVENT_TYPE`       | the API exposes no category field; its `genre` node is a musical genre, not an event kind                                                        | —     |
| `FRANNZ`              | `PER_EVENT_PAGE`   | nothing on the site links a `/events/<slug>/` page                                                                                               | —     |
| `FRANNZ`              | `SOLD_OUT`         | the word ausverkauft appears only in the prose blurb, where it also turns up describing a past tour                                              | —     |
| `GAERTEN_DER_WELT`    | `GENRE`            | the park's only classification is the format category the event type is already built from; it names no musical style, not even in prose         | —     |
| `GARTN`               | `PRICE`            | the venue publishes no prices                                                                                                                    | —     |
| `GARTN`               | `GENRE`            | the venue publishes no genre                                                                                                                     | —     |
| `GARTN`               | `IMAGE`            | the venue publishes no per-event image                                                                                                           | —     |
| `GARTN`               | `DESCRIPTION`      | the venue publishes no per-event text                                                                                                            | —     |
| `GARTN`               | `PER_EVENT_PAGE`   | the Carrd page emits no per-event URL, and removes an event once it has passed                                                                   | —     |
| `GARTN`               | `DOORS_TIME`       | the venue states one time per night and no separate doors time                                                                                   | —     |
| `GARTN`               | `EVENT_TYPE`       | the venue states no category; every night here is a DJ party                                                                                     | —     |
| `GOLDEN_GATE`         | `EVENT_TYPE`       | the club emits no category at all and programmes nothing but DJ nights, so the type is fixed rather than inferred                                | —     |
| `GOLDEN_GATE`         | `PER_EVENT_PAGE`   | there is no custom `event` post type in the WordPress REST API and no structured data; the single rendered page is the source                    | —     |
| `HAVANNA`             | `EVENT_DATE`       | the venue publishes no dated programme: its three resident nights carry only a weekday, so occurrences are generated from the weekly schedule    | —     |
| `HEIDEGLUEHEN`        | `PER_EVENT_PAGE`   | the site has no per-event pages and no archive; one rich-text block lists the month's Saturdays and is replaced wholesale                        | —     |
| `HEIMATHAFEN`         | `GENRE`            | its `events_tag` vocabulary mixes genres with formats across 560 terms, the payload carries only term ids, and the inlined slugs are lossy       | —     |
| `HUMBOLDTHAIN`        | `PER_EVENT_PAGE`   | the calendar widget exposes no per-event URLs                                                                                                    | —     |
| `HUMBOLDTHAIN`        | `PRICE`            | prices appear only in the prose, in too many spellings to parse                                                                                  | —     |
| `HUMBOLDTHAIN`        | `SOLD_OUT`         | nothing in the payload marks a night sold out                                                                                                    | —     |
| `HUMBOLDTHAIN`        | `CANCELLATION`     | nothing in the payload marks a night cancelled or moved                                                                                          | —     |
| `HUXLEYS`             | `PRICE`            | most shows sell through Eventim and print no price at all — one of eleven sampled pages carried one                                              | —     |
| `INSEL`               | `PRICE`            | the venue names no prices anywhere; only an Eintritt-frei note on the free Sunday matinées                                                       | —     |
| `INSEL`               | `GENRE`            | the venue publishes no genre                                                                                                                     | —     |
| `INSEL`               | `CANCELLATION`     | a dropped show is removed from the CMS rather than flagged                                                                                       | —     |
| `INSEL`               | `PER_EVENT_PAGE`   | every event points at the programme page and takes its identity from its date plus its title                                                     | —     |
| `INSEL`               | `EVENT_TYPE`       | the venue publishes no category, so a title that is an event name rather than an act is minted as a concert                                      | —     |
| `INSEL`               | `ARTISTS`          | a support act billed without a colon reads as prose, so only a colon or a line-leading support marker is followed                                | —     |
| `KATER`               | `EVENT_TYPE`       | the club has no category field; only an unambiguous title keyword overrides the party default                                                    | —     |
| `KATER`               | `PER_EVENT_PAGE`   | the per-event page carries nothing the homepage listing lacks                                                                                    | —     |
| `KLUNKERKRANICH`      | `EVENT_TYPE`       | the venue publishes no category, so every night is stored as a party — which mislabels the occasional concert                                    | —     |
| `KLUNKERKRANICH`      | `DOORS_TIME`       | the venue states when the roof opens, not when a show starts                                                                                     | —     |
| `KLUNKERKRANICH`      | `GENRE`            | nothing on the site names a genre                                                                                                                | —     |
| `KLUNKERKRANICH`      | `TICKET_URL`       | entry is paid at the door; an occasional advance-RSVP link is written into a blurb rather than published as a field                              | —     |
| `KLUNKERKRANICH`      | `SOLD_OUT`         | nothing flags a night sold out                                                                                                                   | —     |
| `KLUNKERKRANICH`      | `CANCELLATION`     | nothing flags a night cancelled                                                                                                                  | —     |
| `LARK`                | `START_TIME`       | the venue renders its one time as Doors and publishes no separate start time                                                                     | —     |
| `LOGE`                | `EVENT_TYPE`       | the venue has no category field; a live-music venue, so an unmarked title defaults to a concert                                                  | —     |
| `LOGE`                | `ARTISTS`          | a title without a + separator can be a band or an event name, so no act is derived from one                                                      | —     |
| `MAAYA`               | `PER_EVENT_PAGE`   | the programme is one hand-built section of the WordPress home page                                                                               | —     |
| `MAAYA`               | `DESCRIPTION`      | the programme is one hand-built section of the home page and carries no detail text                                                              | —     |
| `MAAYA`               | `PRICE`            | the venue publishes an entry note in words and no numeric price                                                                                  | —     |
| `MAAYA`               | `ARTISTS`          | there is no lineup field, and the titles are series and party names rather than acts                                                             | —     |
| `MAAYA`               | `DOORS_TIME`       | the venue publishes no doors time                                                                                                                | —     |
| `MAAYA`               | `GENRE`            | the venue publishes no genre                                                                                                                     | —     |
| `MAXXIM`              | `EVENT_TYPE`       | the club publishes no categories; every night is a DJ dance party                                                                                | —     |
| `MIGAS`               | `PRICE`            | entry arrangements are not stated on the site at all                                                                                             | —     |
| `MIGAS`               | `TICKET_URL`       | entry arrangements are not stated on the site at all                                                                                             | —     |
| `MIGAS`               | `DOORS_TIME`       | the listing carries no door time                                                                                                                 | —     |
| `MIGAS`               | `SOLD_OUT`         | the listing carries no sold-out badge                                                                                                            | —     |
| `MIGAS`               | `CANCELLATION`     | the listing carries no cancellation badge                                                                                                        | —     |
| `MIGAS`               | `PAGINATION`       | the listing pages at ten events, with the rest behind a Load More button that POSTs to `admin-ajax.php`                                          | —     |
| `MONARCH`             | `PER_EVENT_PAGE`   | the site is hand-coded PHP with no per-event URLs                                                                                                | —     |
| `MONSTER_RONSONS`     | `DOORS_TIME`       | the venue states one time per night, which is taken as the start                                                                                 | —     |
| `MONSTER_RONSONS`     | `PRICE`            | the price lives in prose and is often a time-banded tariff, which the model has no field for                                                     | —     |
| `MONSTER_RONSONS`     | `GENRE`            | the venue publishes no genre                                                                                                                     | —     |
| `MONSTER_RONSONS`     | `ARTISTS`          | the venue bills no lineup beyond the host named in the title                                                                                     | —     |
| `MORPHINE`            | `GENRE`            | the venue publishes no genre                                                                                                                     | —     |
| `MORPHINE`            | `SOLD_OUT`         | the venue flags nothing sold out                                                                                                                 | —     |
| `MORPHINE`            | `CANCELLATION`     | a dropped night is removed from the listing rather than flagged                                                                                  | —     |
| `MORPHINE`            | `TICKET_URL`       | the advance-sale button posts to PayPal rather than linking anywhere                                                                             | —     |
| `MORPHINE`            | `PRICE`            | nearly every night is priced as a sliding scale or donation range, which the model has no field for, so the wording is kept verbatim as the note | —     |
| `MS_HOPPETOSSE`       | `START_TIME`       | the venue never publishes one; a from-HH:mm marker on the act line is that act's set time                                                        | —     |
| `MS_HOPPETOSSE`       | `EVENT_TYPE`       | the venue publishes no category of its own; every listing is a club night                                                                        | —     |
| `MS_HOPPETOSSE`       | `PER_EVENT_PAGE`   | the programme page is the source for every night                                                                                                 | —     |
| `NEUE_ZUKUNFT`        | `PER_EVENT_PAGE`   | the calendar widget exposes no per-event URLs                                                                                                    | —     |
| `OHM`                 | `PER_EVENT_PAGE`   | the venue's whole programme is one page                                                                                                          | —     |
| `OHM`                 | `IMAGE`            | the programme page carries no per-event image                                                                                                    | —     |
| `OHM`                 | `PRICE`            | the programme page carries no price                                                                                                              | —     |
| `OHM`                 | `TICKET_URL`       | the programme page links no ticket shop                                                                                                          | —     |
| `OHM`                 | `EVENT_TYPE`       | the venue publishes no categories; every night is a DJ programme                                                                                 | —     |
| `PANKE`               | `PER_EVENT_PAGE`   | the venue expands each event's full text inline and publishes no page per event                                                                  | —     |
| `PANKE`               | `EVENT_TYPE`       | the venue publishes no category, and its titles are series names rather than formats                                                             | —     |
| `PETER_EDEL`          | `EVENT_TYPE`       | the venue publishes no event category at all, across a programme spanning concerts, comedy, readings and dance teas                              | —     |
| `PETER_EDEL`          | `ARTISTS`          | without a category nothing confirms that a title is a performer rather than a format, so an act is taken only when a support act is billed       | —     |
| `PETER_EDEL`          | `GENRE`            | the venue publishes no genre                                                                                                                     | —     |
| `PETER_EDEL`          | `PER_EVENT_PAGE`   | the title links straight to the ticket shop                                                                                                      | —     |
| `RENATE`              | `EVENT_TYPE`       | the club states no category; its `.cat-btn` names the spaces in use, not a kind of event                                                         | —     |
| `RENATE`              | `PER_EVENT_PAGE`   | every night points at the programme page                                                                                                         | —     |
| `RITTER_BUTZKE`       | `EVENT_TYPE`       | the club publishes no categories; every night is a DJ programme                                                                                  | —     |
| `ROADRUNNER`          | `PER_EVENT_PAGE`   | the whole programme lives on one hand-coded page                                                                                                 | —     |
| `ROADRUNNER`          | `EVENT_TYPE`       | the retro programme carries no category field; a live-music venue, so an unmarked title defaults to a concert                                    | —     |
| `SAALCHEN`            | `GENRE`            | the venue publishes no genre field of its own                                                                                                    | —     |
| `SILENT_GREEN`        | `PRICE`            | the venue names no prices anywhere — an event either links out to a ticket shop or says nothing                                                  | —     |
| `SILENT_GREEN`        | `GENRE`            | the venue publishes no genre                                                                                                                     | —     |
| `SO36`                | `PRICE_BOX_OFFICE` | the shop publishes the presale price as microdata and does not expose a box-office price structurally                                            | —     |
| `SO36`                | `SOLD_OUT`         | the JSON-LD offer reports `SoldOut` for the external shops most events sell through, even when those shops still have tickets, so it is not read | —     |
| `SODA`                | `DOORS_TIME`       | the Einlass info box states an age limit, not a doors time                                                                                       | —     |
| `SODA`                | `ARTISTS`          | the JSON-LD performer is the placeholder Unbekannt on every night                                                                                | —     |
| `SODA`                | `PROMOTERS`        | the JSON-LD `organizer` is the venue itself on every night                                                                                       | —     |
| `SONNENRAUM`          | `START_TIME`       | the venue never publishes one; a from-HH:mm marker on the act line is that act's set time                                                        | —     |
| `SONNENRAUM`          | `EVENT_TYPE`       | the venue publishes no category of its own; every listing is a club night                                                                        | —     |
| `SONNENRAUM`          | `PER_EVENT_PAGE`   | the programme page is the source for every night                                                                                                 | —     |
| `SUPAMOLLY`           | `PRICE`            | the venue publishes no prices                                                                                                                    | —     |
| `SUPAMOLLY`           | `TICKET_URL`       | the venue runs no ticket shop                                                                                                                    | —     |
| `TRESOR`              | `DOORS_TIME`       | the venue states no doors or start time; the night's opening set is the only clock it gives, and that is stored as the start                     | —     |
| `TRESOR`              | `EVENT_TYPE`       | the club states no category; every listing is a club night                                                                                       | —     |
| `VOID_CLUB`           | `START_TIME`       | the venue publishes no times; every night stores a bare date                                                                                     | —     |
| `VOID_CLUB`           | `DOORS_TIME`       | the venue publishes no times; every night stores a bare date                                                                                     | —     |
| `VOID_CLUB`           | `PRICE`            | the venue publishes no prices                                                                                                                    | —     |
| `VOID_CLUB`           | `DESCRIPTION`      | the venue publishes no per-event text                                                                                                            | —     |
| `VOID_CLUB`           | `PER_EVENT_PAGE`   | every night points at the programme page                                                                                                         | —     |
| `VOID_CLUB`           | `EVENT_TYPE`       | the club states no category; `.void-event-genre` names the music and `.void-event-venue` the rooms in use, neither of which is a kind of event   | —     |
| `WILD_AT_HEART`       | `PER_EVENT_PAGE`   | the whole programme is one hand-coded page                                                                                                       | —     |
| `WILD_AT_HEART`       | `EVENT_TYPE`       | the retro page has no category field; a live-music venue, so an unmarked title defaults to a concert                                             | —     |
| `WILD_AT_HEART`       | `START_TIME`       | the listing states a time only inside a banner (Beginn 21:00, ab 14 Uhr), and most rows carry no banner; those are read, the rest store no time  | —     |
| `WUHLHEIDE`           | `CANCELLATION`     | the venue publishes no cancellations; its one badge, Ausverkauft, is a sold-out flag                                                             | —     |
| `ZENNER`              | `PRICE`            | the venue publishes no prices                                                                                                                    | —     |
| `ZENNER`              | `DOORS_TIME`       | the venue publishes no doors times                                                                                                               | —     |
| `ZENNER`              | `SOLD_OUT`         | the venue publishes no sold-out state                                                                                                            | —     |
| `ZENNER`              | `PER_EVENT_PAGE`   | the venue publishes no per-event pages                                                                                                           | —     |

## Sources with nothing declared

These publish everything the model stores, as of the last review:

`ALTE_KANTINE`, `ASTRA`, `BERGHAIN`, `COLUMBIA_THEATER`, `GRETCHEN`, `HOLE44`, `JUNCTION_BAR`, `LIDO`, `MADAME_CLAUDE`, `MATRIX`, `MAX_SCHMELING_HALLE`, `METROPOL`, `MIKROPOL`, `MODUS`, `PRIVATCLUB`, `QUASIMODO`, `SCHOKOLADEN`, `TEMPODROM`, `THEATER_IM_DELPHI`, `UBER_ARENA`, `UBER_EATS_MUSIC_HALL`, `UFO_IM_VELODROM`, `URANIA`, `URBAN_SPREE`, `VELODROM`, `ZITADELLE`
