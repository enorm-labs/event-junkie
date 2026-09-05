# Plausibility Check

Read the events the public site publishes for the next days, check each row for what cannot be right, compare a sample against the venue's own page, and
write a report. **Read-only, outside-in, and it files nothing**: the site's API is the input, the venue's page is the reference, and the report is the whole
deliverable. A person reads it and decides what becomes an issue.

This is the sibling of [`/data-quality-audit`](data-quality-audit.prompt.md), which reads the database from the inside and needs one running locally. This
one needs a URL and a network, so it runs against staging or production, and it runs from a schedule.

## Important

- **Two halves, and the cheap one runs first.** The plausibility checks in Step 3 need nothing but the rows the API returns — no fetch, no model, no
  judgement. The source comparison in Step 4 fetches a venue's page, and is rationed. Most findings come from Step 3, and Step 4 is pointed at what Step 3
  flagged rather than at everything.
- **Step 4 scrapes venue websites, from wherever this runs.** ADR-007's politeness rules apply exactly as they do to the importer, and the importer's own
  User-Agent is the one to send: `Mozilla/5.0 (compatible; EventJunkie/1.0; +https://github.com/enorm-labs/event-junkie)` (`SCRAPER_USER_AGENT` in
  `ScraperHttpClientConfig.kt`). One request per event, one second between requests to the same host, no retries, and a sample cap. Fetch only a `sourceUrl`
  the site itself publishes — a page the importer already fetched under its robots check — and never crawl from it.
- **A finding that matches [`docs/data-quality/ACCEPTED_LIMITATIONS.md`](../../docs/data-quality/ACCEPTED_LIMITATIONS.md) is KNOWN, not NEW.** The table is
  keyed by source slug and aspect, and the aspects are `LimitedAspect` in `AcceptedLimitation.kt` — the same names Step 3 keys its checks by. The API shows the venue, not the source: match by venue name, and say so when a venue has more than one source. Open importer defects are
  the second register: `grep -i '<venue>' build/BACKLOG.md`, or `gh issue list --label importer --search '<venue>'`.
- **Never write.** Not to the database, not to the tracker, not to the tree. Issue drafts go in the report, in the shape of the 🔍 Importer / data defect
  form, and a person files them with [`/new-issue`](new-issue.prompt.md) after reading the evidence.
- **A site that cannot be reached is a finding, not an empty report.** If `/api/meta` does not answer, say that and stop. A clean report from a dead origin is
  the worst output this prompt can produce.
- `git` and `gh` non-interactively (`git --no-pager …`); see AGENTS.md.

## Arguments

```text
/plausibility-check [origin] [--days N] [--sample N] [--unattended]
```

- **`origin`** — the site to check, scheme included. Defaults to `$SITE_URL`, then to `https://event-junkie.de`. Staging is reachable only through WireGuard
  (`docs/ops/CLUSTER_ACCESS.md`), so a local run against it needs the tunnel up first.
- **`--days N`** — the window, counted from today in `Europe/Berlin`. Default `2`: today and tomorrow. The site's day is Berlin's, and a runner's clock is
  UTC, so always `TZ=Europe/Berlin date +%F` rather than `date +%F`.
- **`--sample N`** — how many source pages Step 4 may fetch. Default `20`. This is the cost ceiling on the venues' side, and the reason the default is small.
- **`--unattended`** — the runner mode; see below.

## Step 1 — Establish the origin and the window

```sh
ORIGIN="${1:-${SITE_URL:-https://event-junkie.de}}"
curl -fsS --max-time 20 "$ORIGIN/api/meta"                      # version and commit; a failure here ends the run
FROM="$(TZ=Europe/Berlin date +%F)"
TO="$(TZ=Europe/Berlin date -d "+$((DAYS-1)) day" +%F)"           # GNU date; on macOS: date -v+1d +%F
```

Record the version the site reports. A finding is only reproducible against the build that produced it, and the importer's version is what decides which
scraper code was running.

## Step 2 — Pull the window

The list endpoint pages, and defaults to twenty rows:

```sh
curl -fsS "$ORIGIN/api/events?from=$FROM&to=$TO&size=200&page=0"   # PageResponseEventSummaryResponse: content, totalPages, totalElements
```

Loop over `totalPages` and save every row to one JSON file under `temp/`. Then fetch the detail for each slug — `GET /api/events/{slug}` carries `sourceUrl`,
`ticketUrl`, `description` and `lineup`, and the summary does not. These are requests to our own API and need no throttling beyond running them sequentially.

Report the shape before checking anything: rows in the window, rows per venue, and the count of venues with zero rows against the 86 the site registers. **A
venue that always has events and has none tonight is itself a finding** — a source that failed silently looks exactly like a quiet night.

## Step 3 — Plausibility checks on the rows alone

Run every check below over the saved JSON with `jq`, and for each one report the count, the command, and up to five slugs with their site URL
(`$ORIGIN/en/events/<slug>`). Break every count down by venue: a problem concentrated at one venue is that venue's scraper.

**The checks are keyed by the aspects the limitations table uses** — `LimitedAspect` in `AcceptedLimitation.kt`, which is also what the per-source coverage
series from #472 tracks through `TrackedField`. That makes KNOWN a lookup rather than a judgement: a hit is KNOWN when the table has a row for that venue's
source and that aspect, and NEW otherwise. A check with `—` in the aspect column has no limitation that can excuse it. The number in brackets is the
category in [`/data-quality-audit`](data-quality-audit.prompt.md), so a finding here and one there can be read as the same defect seen from both ends.

| Aspect                               | Check                                                                                                                                       | What it usually means                                                                                                   |
| ------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| `START_TIME` / `DOORS_TIME`          | No `startTime` **and** no `doorsTime` [5]                                                                                                   | The page publishes a time the parser did not read                                                                       |
| `DOORS_TIME`                         | `doorsTime` later than `startTime` [5]                                                                                                      | The two fields swapped, or a time parsed from the wrong label                                                           |
| `PRICE`                              | No price, `free` false, no `priceNote` [1]                                                                                                  | A price the page shows in prose, or in an image                                                                         |
| `PRICE_NOTE`                         | A `priceNote` holding an amount (`12 €`, `AK 15`) and no `pricePresale` or `priceBoxOffice` [5]                                             | The note caught what the price fields should                                                                            |
| `PRICE_PRESALE` / `PRICE_BOX_OFFICE` | `pricePresale` higher than `priceBoxOffice`, or one of the two present where the page shows both [5]                                        | The two labels swapped, or one price read into the wrong column                                                         |
| `SUBTITLE`                           | `subtitle` equal to the title, or holding a date, a time, a price or a line-up [1]                                                          | The subtitle selector caught a neighbouring element                                                                     |
| `ARTISTS`                            | `eventType` is `CONCERT` with an empty `lineup` [1]                                                                                         | A title the artist parser could not split                                                                               |
| `ARTISTS`                            | A `lineup` name that is not a performer — a series, a campaign, a format word (`Quiz`, `Karaoke`, `Open Mic`), a whole title, the venue [3] | `headlinersFromTitle` minted the billing as an act; the name belongs in `NON_ARTIST_NAMES`. #1110 is the worked example |
| `ARTISTS`                            | A `lineup` name that still carries a join — `+`, `&`, `w/`, `vs`, `feat.`, `presents`, `b2b` [3]                                            | One row where the page names two or more acts                                                                           |
| `ARTISTS`                            | A `lineup` name in ALL CAPS, or with `&amp;`, `Ã¤`, `â€™` or doubled spaces [3]                                                             | De-shouting is casing-only and `ACRONYMS` is curated, so a new stylised name gets through until it is added             |
| `PROMOTERS`                          | A promoter named after the venue, or a generic label (`Presents`, `Konzert`, `Live`) [3]                                                    | The promoter selector caught a heading                                                                                  |
| `EVENT_TYPE`                         | `OTHER` with a keyword title (`Quiz`, `Karaoke`, `Lesung`, `Kino`, `Party`), or a `READING` / `SCREENING` that reads as a gig [4]           | The venue's parser runs no title classifier, or a keyword matched inside a name                                         |
| `GENRE`                              | No `genre` and no `genreTags` on a `CONCERT` or `CLUB_NIGHT` [1]                                                                            | A `GENRE` row, or `GenreNormalizer` dropped every token                                                                 |
| `TICKET_URL`                         | No `ticketUrl`, and `free` false [1]                                                                                                        | A `TICKET_URL` row, or the ticket link moved                                                                            |
| `IMAGE`                              | `imageUrl` null and `imageWithheld` false [1]                                                                                               | An `IMAGE` row, or the image selector drifted                                                                           |
| `DESCRIPTION`                        | `description` that reads as boilerplate — cookie, newsletter, Impressum [1]                                                                 | The description selector drifted to the page chrome                                                                     |
| `PER_EVENT_PAGE`                     | `sourceUrl` is the programme page rather than a page per event; **no `sourceUrl` at all is always a defect** [6]                            | Declared for the listing-only venues; anywhere else the link selector broke                                             |
| `EVENT_DATE`                         | `eventDate` outside `[FROM, TO]`, or a venue whose dates the table declares derived [5]                                                     | An API defect, or a generated date that Step 4 must confirm against the page                                            |
| `CANCELLATION`                       | `status` is `CANCELLED` or `POSTPONED` [5]                                                                                                  | Not a defect — list them, because Step 4 confirms the page still says so                                                |
| `SOLD_OUT`                           | `soldOut` true at a venue with a `SOLD_OUT` row [5]                                                                                         | A flag no parser sets was set                                                                                           |
| `PAGINATION`                         | A venue with rows today and none tomorrow, when its programme runs past [1]                                                                 | First page only — confirm against the programme page in Step 4                                                          |
| —                                    | Title empty, a placeholder (`TBA`, `TBC`, `-`), ALL CAPS, or the venue's own name [1]                                                       | A listing scraped as an event, or a title taken from the wrong element                                                  |
| —                                    | Two rows at one venue with one date and near-identical titles [2]                                                                           | Cross-source duplicates, or a listing and its detail page both imported                                                 |
| —                                    | Every row of one venue sharing one `startTime` [5]                                                                                          | The parser collapsed to a default                                                                                       |

Category 6 of the audit, referential integrity, is the one this prompt cannot reach: join rows and orphans are invisible from the API, and the audit is
where they are found.

Do not stop at the table. A row that looks wrong in a way the table does not name is still a finding — say what is implausible about it and show the row.

**The line-up deserves its own pass, because a wrong artist is public twice.** A name that is not a performer becomes an event line _and_ an artist page,
`$ORIGIN/en/artists/<slug>`, and that page is what turned up `Kein Bock auf Nazis` on production (#1110, fixed by #1113 with one entry in
`NON_ARTIST_NAMES`). For every `lineup` name in the window, ask whether a person or a band could be called that, and read it against `isNonArtistName` and
`NON_ARTIST_NAMES` in `ArtistNameMapping.kt` before calling it NEW. A name with one gig at one venue whose title is the name is the #1110 shape. A `CONCERT`
whose page says the acts are unannounced must have an empty line-up, and a line-up of one where the page bills three is a split the parser missed.

## Step 4 — Compare a sample against the source

**Choose the sample deliberately, and say how.** First every row Step 3 flagged that has a `sourceUrl`, then fill up to `--sample` round-robin across venues so
no single host takes more than a handful of requests. Prefer a venue whose source is one page per event over one whose `sourceUrl` is the whole programme:
the second kind takes one fetch per venue, not per event, and reuse it.

One fetch per event, and the politeness rules from above:

```sh
curl -sS --max-time 20 -o "temp/source-<slug>.html" -w '%{http_code}' \
     -A 'Mozilla/5.0 (compatible; EventJunkie/1.0; +https://github.com/enorm-labs/event-junkie)' "$SOURCE_URL"
sleep 1                                                            # between requests to the same host
```

A `403` or `429` from a host ends fetching from that host for this run; say so, and do not try a different path or header. A `404` is a finding of its own:
the site lists an event whose page is gone, which is often a cancellation the importer has not seen yet.

Reduce the page to text (`python3 -c 'import html.parser…'`, or `sed 's/<[^>]*>//g'` on a simple page) and read it for the fields the row carries. The page
is in German or English or both, so look for both: _Einlass_ / _Doors_, _Beginn_ / _Start_, _ausverkauft_ / _sold out_, _abgesagt_ / _cancelled_,
_verschoben_ / _postponed_, a `€` amount, the date in any of the formats `DateParsingExtensions.kt` accepts, and **the acts**: the billing in the
heading, a line-up section, _Support:_, _feat._, _w/_, _+_. Then classify:

- **MATCH** — the page says what the row says.
- **DIFFERS** — quote the page and the row, side by side. Name the aspect. A different date or time, a cancellation the row does not carry, a sold-out the row does not carry,
  a price the row lacks or contradicts, a headliner the page does not name, a support act the page names and the row lacks, or a name the page presents
  as a series or a night rather than an act.
- **NOT COMPARABLE** — the page is rendered by JavaScript, answered with a consent wall, or the field is simply absent from the text. Say which.
- **SOURCE GONE** — `404` or a redirect to the programme.

**The page is the reference, and it is not always right either.** A page that shows last year's date on a recurring event is a known venue-side pattern.
When the page and the row disagree and the page looks wrong, say that too — the finding is the disagreement, and the reader decides.

## Step 5 — Write the report

Locally, write it to `temp/plausibility-<YYYY-MM-DD>.md` and run `scripts/format-markdown.sh temp/plausibility-<YYYY-MM-DD>.md` on it, because the report
ends up pasted into issues and unformatted tables are the tell. Unattended, the final message is the report (below).

## Running unattended

[`agent-plausibility.yml`](../workflows/agent-plausibility.yml) invokes this prompt as `/plausibility-check --unattended` nightly, and the report lands in the
job summary and the `agent-report` artifact. Two things make this workload different from the rest of the family:

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

- **The relationship to #474.** That issue builds the same comparison inside the importer, as admin endpoints with a model behind them, and it is blocked on
  #473 deciding the model. This prompt needs neither, because the judgement runs in the agent rather than in the application. What it produces over a few
  weeks is also the measurement #474 asks for before anyone trusts its output: which sources drift, how often, and in which field.
- **A finding that recurs every night is a scraper defect**, whatever the limitations table says. A venue that started publishing start times after its row
  was declared is the case the table cannot see, and the comparison can.
- **Why the sample is small.** Twenty fetches spread across up to 86 hosts, one each, is a rounding error next to the importer's own nightly traffic. Two
  hundred is not, and it is the venues' bandwidth. Raise `--sample` for one run when a venue needs a closer look, not in the workflow.
