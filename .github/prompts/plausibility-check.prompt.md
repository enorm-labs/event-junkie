# Plausibility Check

Read the events the public site publishes for the next days, check each row for what cannot be right, compare a sample against the venue's own page, and
write a report. **Read-only, outside-in, and it files nothing**: the API is the input, the venue's page is the reference, the report is the deliverable, and a
person decides what becomes an issue. The sibling of [`/data-quality-audit`](data-quality-audit.prompt.md), which reads the database from inside.

## Important

- **Two halves; the cheap one runs first.** Step 3 needs only the rows the API returns. Step 4 fetches venue pages, is rationed, and is pointed at what
  Step 3 flagged.
- **Step 4 scrapes venue websites.** ADR-007's politeness rules apply: the importer's User-Agent
  `Mozilla/5.0 (compatible; EventJunkie/1.0; +https://github.com/enorm-labs/event-junkie)`, one request per event, one second between requests to a host,
  no retries, a sample cap. Fetch only a `sourceUrl` the site publishes; never crawl from it.
- **A finding that matches [`ACCEPTED_LIMITATIONS.md`](../../docs/data-quality/ACCEPTED_LIMITATIONS.md) is KNOWN, not NEW.** The table is keyed by source slug
  and aspect (`LimitedAspect` in `AcceptedLimitation.kt`, the same names Step 3 uses); the API shows the venue, so match by venue name and say so when a venue
  has more than one source. Open importer defects are the second register: `gh issue list --label importer --search '<venue>'`.
- **Never write** — not the database, the tracker or the tree. Issue drafts go in the report in the 🔍 Importer / data defect form's shape.
- **A site that cannot be reached is a finding, not an empty report.** If `/api/meta` does not answer, say so and stop.
- `git --no-pager`, `gh` non-interactive.

## Arguments

```text
/plausibility-check [origin] [--days N] [--sample N] [--unattended]
```

- **`origin`** — scheme included; defaults to `$SITE_URL`, then `https://event-junkie.de`. Staging needs the WireGuard tunnel (`docs/ops/CLUSTER_ACCESS.md`).
- **`--days N`** — the window from today in `Europe/Berlin` (default `2`). A runner's clock is UTC: always `TZ=Europe/Berlin date +%F`.
- **`--sample N`** — source pages Step 4 may fetch (default `20`); the cost ceiling on the venues' side.
- **`--unattended`** — the runner mode; see below.

## Step 1 — Establish the origin and the window

```sh
ORIGIN="${1:-${SITE_URL:-https://event-junkie.de}}"
curl -fsS --max-time 20 "$ORIGIN/api/meta"                      # version and commit; a failure here ends the run
FROM="$(TZ=Europe/Berlin date +%F)"
TO="$(TZ=Europe/Berlin date -d "+$((DAYS-1)) day" +%F)"           # GNU date; on macOS: date -v+1d +%F
```

Record the version: a finding is reproducible only against the build that produced it.

## Step 2 — Pull the window

The list endpoint pages, and defaults to twenty rows:

```sh
curl -fsS "$ORIGIN/api/events?from=$FROM&to=$TO&size=200&page=0"   # PageResponseEventSummaryResponse: content, totalPages, totalElements
```

Loop over `totalPages` into one JSON file under `temp/`, then `GET /api/events/{slug}` per row for `sourceUrl`, `ticketUrl`, `description`, `lineup`. Our own
API; sequential is throttling enough. Pull the default listing too (`GET /api/events?size=200`, no dates) and keep rows dated before `$FROM` — the site's
_Running since_ rows, which the last Step 3 check tests.

Report the shape first: rows in the window, rows per venue, venues with zero rows against the count the site registers. **A venue that always has events and
has none tonight is itself a finding** — a source that failed silently looks exactly like a quiet night.

## Step 3 — Plausibility checks on the rows alone

Run every check below over the saved JSON with `jq`, and for each one report the count, the command, and up to five slugs with their site URL
(`$ORIGIN/en/events/<slug>`). Break every count down by venue: a problem concentrated at one venue is that venue's scraper.

**Checks are keyed by `LimitedAspect`**, so KNOWN is a lookup: a hit is KNOWN when the table has a row for that venue's source and that aspect. `—` in the
aspect column has no limitation that can excuse it. The bracketed number is the [`/data-quality-audit`](data-quality-audit.prompt.md) category.

| Aspect                               | Check                                                                                                                                                                      | What it usually means                                                                                                            |
| ------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| `START_TIME` / `DOORS_TIME`          | No `startTime` **and** no `doorsTime` [5]                                                                                                                                  | The page publishes a time the parser did not read                                                                                |
| `DOORS_TIME`                         | `doorsTime` later than `startTime` [5]                                                                                                                                     | The two fields swapped, or a time parsed from the wrong label                                                                    |
| `PRICE`                              | No price, `free` false, no `priceNote` [1]                                                                                                                                 | A price the page shows in prose, or in an image                                                                                  |
| `PRICE_NOTE`                         | A `priceNote` holding an amount (`12 €`, `AK 15`) and no `pricePresale` or `priceBoxOffice` [5]                                                                            | The note caught what the price fields should                                                                                     |
| `PRICE_PRESALE` / `PRICE_BOX_OFFICE` | `pricePresale` higher than `priceBoxOffice`, or one of the two present where the page shows both [5]                                                                       | The two labels swapped, or one price read into the wrong column                                                                  |
| `SUBTITLE`                           | `subtitle` equal to the title, or holding a date, a time, a price or a line-up [1]                                                                                         | The subtitle selector caught a neighbouring element                                                                              |
| `ARTISTS`                            | `eventType` is `CONCERT` with an empty `lineup` [1]                                                                                                                        | A title the artist parser could not split                                                                                        |
| `ARTISTS`                            | A `lineup` name that is not a performer — a series, a campaign, a format word (`Quiz`, `Karaoke`, `Open Mic`), a whole title, the venue [3]                                | `headlinersFromTitle` minted the billing as an act; the name belongs in `NON_ARTIST_NAMES`. #1110 is the worked example          |
| `ARTISTS`                            | A `lineup` name equal to the event title, or the title equal to the name plus the subtitle, after lowercasing and stripping punctuation [3]                                | The whole billing minted as the act. Every series-as-artist case so far — #1110, #1135, SO36, Morphine Raum — had this signature |
| `ARTISTS`                            | A headliner with exactly one event in the whole catalogue whose title is its name: `GET /api/events?artist=<slug>&from=2020-01-01&to=<today + 1 year>` returns one row [3] | The name is the night, not the act. A touring act also has one gig here, so read it with the row above and the page, never alone |
| `ARTISTS`                            | A `lineup` name that still carries a join — `+`, `&`, `w/`, `vs`, `feat.`, `presents`, `b2b` [3]                                                                           | One row where the page names two or more acts                                                                                    |
| `ARTISTS`                            | A `lineup` name in ALL CAPS, or with `&amp;`, `Ã¤`, `â€™` or doubled spaces [3]                                                                                            | De-shouting is casing-only and `ACRONYMS` is curated, so a new stylised name gets through until it is added                      |
| `PROMOTERS`                          | A promoter named after the venue, or a generic label (`Presents`, `Konzert`, `Live`) [3]                                                                                   | The promoter selector caught a heading                                                                                           |
| `PROMOTERS`                          | A promoter whose page (`/promoters/<slug>`) names it differently from the venue's credit, or whose website link does not open [3]                                          | A pin in `PromoterNormalizer` or a row in `docs/promoters/REVIEWED.tsv` went stale; open one promoter page per sample            |
| `EVENT_TYPE`                         | `OTHER` with a keyword title (`Quiz`, `Karaoke`, `Lesung`, `Kino`, `Party`), or a `READING` / `SCREENING` that reads as a gig [4]                                          | The venue's parser runs no title classifier, or a keyword matched inside a name                                                  |
| `GENRE`                              | No `genre` and no `genreTags` on a `CONCERT` or `CLUB_NIGHT` [1]                                                                                                           | A `GENRE` row, or `GenreNormalizer` dropped every token                                                                          |
| `TICKET_URL`                         | No `ticketUrl`, and `free` false [1]                                                                                                                                       | A `TICKET_URL` row, or the ticket link moved                                                                                     |
| `IMAGE`                              | `imageUrl` null and `imageWithheld` false [1]                                                                                                                              | An `IMAGE` row, or the image selector drifted                                                                                    |
| `DESCRIPTION`                        | `description` that reads as boilerplate — cookie, newsletter, Impressum [1]                                                                                                | The description selector drifted to the page chrome                                                                              |
| `PER_EVENT_PAGE`                     | `sourceUrl` is the programme page rather than a page per event; **no `sourceUrl` at all is always a defect** [6]                                                           | Declared for the listing-only venues; anywhere else the link selector broke                                                      |
| `EVENT_DATE`                         | `eventDate` outside `[FROM, TO]`, or a venue whose dates the table declares derived [5]                                                                                    | An API defect, or a generated date that Step 4 must confirm against the page                                                     |
| `CANCELLATION`                       | `status` is `CANCELLED`, `POSTPONED`, or `RELOCATED` — an origin row, not a concert here (#1551) [5]                                                                       | Not a defect — list them, because Step 4 confirms the page still says so                                                         |
| `SOLD_OUT`                           | `soldOut` true at a venue with a `SOLD_OUT` row [5]                                                                                                                        | A flag no parser sets was set                                                                                                    |
| `PAGINATION`                         | A venue with rows today and none tomorrow, when its programme runs past [1]                                                                                                | First page only — confirm against the programme page in Step 4                                                                   |
| —                                    | Title empty, a placeholder (`TBA`, `TBC`, `-`), ALL CAPS, or the venue's own name [1]                                                                                      | A listing scraped as an event, or a title taken from the wrong element                                                           |
| —                                    | Two rows at one venue with one date and near-identical titles [2]; a `RELOCATED` row is skipped — it is the show's trace at the house it left                              | Cross-source duplicates, or a listing and its detail page both imported                                                          |
| —                                    | Every row of one venue sharing one `startTime` [5]                                                                                                                         | The parser collapsed to a default                                                                                                |
| —                                    | A default-listing row dated before today that is over: `endDate` past, or today with `endTime` behind the Berlin clock, or no end and outside the #299 grace               | The site says _Running since_ about a night that is over: the BFF window or `isPastEvent`, not a scraper                         |

Referential integrity (audit category 6) is invisible from the API. A row wrong in a way the table does not name is still a finding — show it.

**The line-up gets its own pass, because a wrong artist is public twice** — the event line and `$ORIGIN/en/artists/<slug>` (#1110, `Kein Bock auf Nazis`).
For every `lineup` name ask whether a person or band could be called that, and read it against `isNonArtistName` and `NON_ARTIST_NAMES` in
`ArtistNameMapping.kt` before calling it NEW. One gig at one venue whose title is the name is the #1110 shape; a `CONCERT` whose page says the acts are
unannounced must have an empty line-up.

## Step 4 — Compare a sample against the source

**Choose the sample deliberately, and say how**: every Step 3 hit with a `sourceUrl` first, then round-robin across venues up to `--sample`. A programme-page
`sourceUrl` is one fetch per venue, reused.

```sh
curl -sS --max-time 20 -o "temp/source-<slug>.html" -w '%{http_code}' \
     -A 'Mozilla/5.0 (compatible; EventJunkie/1.0; +https://github.com/enorm-labs/event-junkie)' "$SOURCE_URL"
sleep 1                                                            # between requests to the same host
```

**A venue with no rows is the one most worth a fetch.** Take its listing URL from `events-importer/src/main/kotlin/de/norm/events/scraper/<venue>/` — one
fetch, counted against `--sample`, and say the URL came from the tree. The only fetch allowed that the site did not publish.

A `403` or `429` ends fetching from that host for the run; no other path or header. A `404` is a finding: often a cancellation the importer has not seen.

Reduce the page to text and read it in German and English: _Einlass_ / _Doors_, _Beginn_ / _Start_, _ausverkauft_ / _sold out_, _abgesagt_ / _cancelled_,
_verschoben_ / _postponed_, a `€` amount, the date, and the acts (heading billing, line-up section, _Support:_, _feat._, _w/_, _+_). Classify:

- **MATCH** — the page says what the row says.
- **DIFFERS** — quote the page and the row side by side, and name the aspect.
- **NOT COMPARABLE** — the page is rendered by JavaScript, answered with a consent wall, or the field is simply absent from the text. Say which.
- **SOURCE GONE** — `404` or a redirect to the programme.

**The page is the reference, and not always right** (last year's date on a recurring event is a known pattern). The finding is the disagreement; say when
the page looks wrong too.

## Step 5 — Write the report

Locally, `temp/plausibility-<YYYY-MM-DD>.md`, then `scripts/format-markdown.sh` on it by name. Unattended, the final message is the report and the
formatter is not run (no `node_modules` on the runner).

## Running unattended

[`agent-plausibility.yml`](../workflows/agent-plausibility.yml) invokes this prompt as `/plausibility-check --unattended` nightly, and the report lands in the
job summary, the `agent-report` artifact, and as a comment on the month's `Plausibility check — nightly reports` issue, which a second job posts after
this one ends (#1499). Two things make this workload different from the rest of the family:

- **It has no `--dry-run`, because every run is one.** The prompt writes nothing anywhere, so there is no pull request to withhold and nothing irreversible to
  guard. The only cost it can incur is on the venues' side, and `--sample` is the ceiling on that.
- **It files nothing, and that is the point rather than a restriction.** A finding here is a comparison between two texts, and a comparison can be confidently
  wrong. The report is what a person checks before anything becomes an issue, and an agent filing plausibility findings on a schedule is how a tracker fills
  with events that were fine.

**Your final message is the report, and there is no second turn.** The run ends the moment you stop calling tools, so a closing line like _"I'll write the
report once the last page arrives"_ ends it with that sentence as the whole deliverable — and the job still reports success. Finish the fetches, then write
the Output section below as your last message.

**Every count carries the command that produced it.** A row reading `No start time: 0` with nothing behind it is an assertion. Show the `jq` filter and its
output, so a reviewer or the next run can re-run it and get the same number. A zero needs its evidence as much as any other number, and a number you did not
produce with a command is a guess.

**An unreachable origin is reported as unreachable**, with the `curl` output, and the run ends there. Never let it read as a clean night.

## Output

```markdown
# Plausibility check — <origin>, <FROM> to <TO>

Site version `<version>` (`<commitShort>`), checked <timestamp UTC>.

## Shape

| Rows | Venues with rows | Venues with none | Source pages fetched | MATCH | DIFFERS | NOT COMPARABLE | SOURCE GONE |

Venues with no rows in the window: <list, with whether that is usual for the weekday>.

## 🔴 Wrong on the site
<Per finding: event title, site URL, source URL, what the site says, what the page says (quoted), KNOWN/NEW with the issue or limitation row.>
<A non-performer stored as an artist belongs here, with the artist page URL: it is wrong on two pages.>

## 🟠 Missing where the source publishes it
<Same shape. A start time the page shows, a price in prose, a sold-out badge.>

## 🟢 Cosmetic and unusual
<Same shape. ALL-CAPS titles, boilerplate descriptions, a duplicate pair.>

## Plausibility checks — the numbers
<One row per check in Step 3, keyed by aspect: count, KNOWN/NEW split, the jq command, up to five slugs.>

## Source comparison — the sample
<One row per fetched page: venue, event, status code, verdict, one line of evidence.>

## Drafts for issues — not filed
<One draft per underlying defect, grouped by scraper rather than per event, in the 🔍 Importer / data defect form's shape: scraper, the source text, what we
store, the likely code path, whether the fix needs a --full re-seed. Only for NEW findings. Name the aspect. When the finding is a limitation to declare rather than
a defect to fix, say so instead: the AcceptedLimitation for that source — aspect and one-sentence reason — the way /data-quality-audit does.>>

## What this run could not check
<Hosts that answered 403/429, JS-rendered pages, venues with more than one source, anything the window did not cover.>
```

## Notes

- **#474** builds the same comparison inside the importer and is blocked on #473; what this produces over weeks is the measurement #474 asks for first.
- **A finding that recurs every night is a scraper defect**, whatever the limitations table says — a venue that started publishing start times after its row
  was declared is the case the table cannot see.
- **The sample is small on purpose**: twenty fetches across up to 86 hosts is a rounding error next to the importer's traffic; two hundred is the venues'
  bandwidth. Raise `--sample` for one run, not in the workflow.
