# Data Quality Audit

Audit the `events` database for data-quality issues and oddities: missing or wrong data, duplicates, typos, mis-parsed artist/promoter names, missing event
types and genres, and referential problems. This is a **read-only investigation** — inspect and report, never mutate. Produce a prioritized report; propose
fixes but do not apply them unless the user explicitly asks.

## Scope & intent

The data is scraped by the importers in `events-importer`. Surface **actionable** problems — parsing bugs, normalisation gaps, oddities worth a human's
attention — and separate them from limitations already accepted. Two registers say what is known:

- **[`ACCEPTED_LIMITATIONS.md`](../../docs/data-quality/ACCEPTED_LIMITATIONS.md)** — one row per source and aspect (#715), generated from `AcceptedLimitations.kt`
  and asserted by `AcceptedLimitationsTest`. A lookup on (source, aspect): the aspects are `LimitedAspect`, every check below names the one it maps to, and a
  check marked `—` has no limitation that can excuse it. **Read this instead of grepping scraper KDoc**; the KDoc explains how a parser copes, not what the
  source withholds. A source absent from the table publishes everything, so a gap there is a finding.
- **The open issues** — `build/BACKLOG.md`, or `gh issue list --label importer`.

A finding matching either is **known/accepted**, reported separately, and not re-litigated.

## Connecting to the database

Dev PostgreSQL runs via Docker Compose (`compose.yaml`). The tables live in the `events` schema (not `public`). Default local connection:

- host `localhost`, port `56298` (override: `POSTGRES_HOST_PORT`), database `event_junkie`, user `admin`, password `admin`, schema `events`.

```bash
PGPASSWORD=admin psql -h localhost -p 56298 -U admin -d event_junkie \
  -c "SET search_path TO events; <QUERY>"
```

First verify connectivity and that the DB is seeded (`SELECT count(*) FROM event;`). If the container isn't running or the DB is empty, say so and stop —
suggest the user start the importer (`./gradlew :events-importer:bootRun`) and trigger imports so there's data to audit. If the port is taken and remapped, ask
for the actual port or read it from the running container (`docker compose ps`).

Prefer batching related checks into a single `psql` invocation. Always `SET search_path TO events;`. Quote every count with a few concrete sample rows (id,
title/name, venue) so findings are verifiable — never report a bare number.

## Reference: schema & enums

Tables (all in schema `events`): `venue`, `artist`, `promoter`, `event`, `event_source`,
`event_artist` (join, role + billing_order), `event_promoter` (join), `genre_tag`,
`event_genre_tag` (join). Full DDL: `events-importer/src/main/resources/db/migration/V001__create_initial_schema.sql`.

Valid enum values (stored as `TEXT`; anything else is a bug — parsers fall back on unknowns):

- `event.event_type`: `CONCERT`, `FESTIVAL`, `PARTY`, `QUIZ`, `SHOW`, `SCREENING`, `EXHIBITION`, `READING`, `OTHER`
- `event.status`: `SCHEDULED`, `RELOCATED`, `CANCELLED`, `POSTPONED`
- `event_artist.role`: `HEADLINER`, `SUPPORT`, `DJ`

## What to check

Work through these categories. For each, run SQL, then judge whether hits are real problems or noise. Break findings down **per venue / per source** where
useful — a problem concentrated at one venue usually points at that importer. The tag opening each check is its `LimitedAspect`, and the same tags key
[`/plausibility-check`](plausibility-check.prompt.md), so a finding from either prompt names the same row of the limitations table.

### 1. Missing / required data

- `ARTISTS` — Events with no artists (`event` with no `event_artist` row), broken down by venue and `event_type`. Several venues declare this, so check
  the table before flagging as new.
- `—` — `NULL`/empty `title`, `slug`, `source_id`, `event_date`, `venue_id`.
- `GENRE` — Events with no genre at all: both `event.genre IS NULL` and no `event_genre_tag` rows.
- `EVENT_TYPE` — Missing `event_type` signal: rows defaulting to `OTHER` (per venue — which sources never set a type?).
- `START_TIME` / `DOORS_TIME`, `PRICE`, `IMAGE`, `TICKET_URL` — Missing structured fields that are usually recoverable: no `start_time`/`doors_time`, no
  price fields _and_ no `price_note`, no `image_url`, no `ticket_url`.
- `DESCRIPTION` — Events with no `description` at a source with no `DESCRIPTION` row, and descriptions that are boilerplate (cookie, newsletter, Impressum)
  or identical across every event of one source — the selector drifted to the page chrome.
- `SUBTITLE` — A `subtitle` equal to the title, or holding what belongs in another column: a date, a time, a price, a line-up.
- `PER_EVENT_PAGE` — `source_url` that is the programme page rather than a page per event. Declared for the listing-only venues; a `NULL` `source_url` is
  always a defect.
- `—` — `venue` rows missing `district`, `latitude`/`longitude`, or `website_url`.
- `—` — Whitespace-only or placeholder text values (e.g. `''`, `'-'`, `'TBA'`, `'N/A'`, `'null'`) in name/title fields.

### 2. Duplicates & entity fragmentation

- `—` — Artists / promoters / genre_tags that are almost certainly the same entity under different names:
  same `slug` prefix, case-only differences, punctuation/spacing variants, trailing `Live`/tour suffixes, ALL-CAPS vs mixed case. (Slugs are case-insensitive so
  exact-slug dupes shouldn't exist, but _near_-duplicate slugs do — that's fragmentation.)
- `—` — Group by `lower(regexp_replace(name, '[^a-z0-9]', '', 'gi'))` to surface names that normalize to the same token but have distinct rows.
- `—` — Events that look like the same real-world event under different `source_id`s (same venue + date + similar title) — the importers dedupe by `source_id`, so
  cross-source or re-listed duplicates slip through.
- `—` — Orphan `artist`/`promoter`/`genre_tag` rows referenced by zero events (dead rows from renames/reparsing).

### 3. Mis-parsed artist & promoter names

- `ARTISTS` — Non-artist strings sitting in `artist.name`: event-format words (`Quiz`, `Karaoke`, `Open Mic`,
  `Festival`, `Special`, `Tour`, `Support`, `Live`, `Warm Up`, `Aftershow`, `w/`, `presents`, `vs`), standalone symbols, pure numbers, or very long strings (a
  whole title parsed as one artist).
- `ARTISTS` — An artist with exactly one event whose `name` equals that event's `title`, case-insensitively — the series-as-artist signature every
  `NON_ARTIST_NAMES` entry so far has shared (#1110, #1135). A real act with one Berlin date also matches, so this ranks candidates rather than
  convicting them; the venue's page settles each one. The importer keeps this list itself since #1145: `event_artist.title_derived` says the name was
  read off the title, and `GET /api/admin/data-quality/worklist?issue=titleDerivedSingletons` (name equals title) and `…=titleDerivedUnmatched` (no
  MusicBrainz match) are the same query with that flag, per source. Read those first; the SQL below is the fallback for rows imported before the flag.

    ```sql
    SELECT a.id, a.name, min(e.title) AS title, min(v.name) AS venue
    FROM artist a JOIN event_artist ea ON ea.artist_id = a.id JOIN event e ON e.id = ea.event_id JOIN venue v ON v.id = e.venue_id
    GROUP BY a.id, a.name HAVING count(*) = 1 AND lower(a.name) = lower(min(e.title));
    ```

- `ARTISTS` — Residual ALL-CAPS artist names — `canonicalArtistName`'s de-shouting is casing-only and its `ACRONYMS` set is curated, so a genuine all-caps name that is not
  in it gets title-cased and a new stylised one slips through until added.
- `ARTISTS` / `PROMOTERS` — Artist/promoter names with leftover HTML entities (`&amp;`, `&#039;`), stray encoding (`Ã¤`, `â€™`), leading/trailing punctuation or whitespace, doubled
  spaces.
- `PROMOTERS` — Promoter rows nobody has reviewed: `reviewed_at IS NULL` (#1336). Every row a person read is stamped, so this list is exactly what the
  imports minted since, and each one is a name to check against the venue's credit, `docs/promoters/REVIEWED.tsv` and `PromoterNormalizer`.
  `scripts/promoter-duplicates.py --unreviewed` prints the same rows with their events. Report the count and the slugs; do not guess at descriptors — a
  reviewed row with an odd name was read and kept on purpose (`Frack & Spitzenhöschen` is a show name).
- `PROMOTERS` — A promoter whose `slug` is not the slug of its own `name`: the next import resolves the credit by the name's slug, finds no row and mints a
  second one beside it (V025, #1343). `SlugGenerator.slugify` lower-cases, strips accents and hyphenates; the SQL below ranks candidates and a name with
  an accent it does not fold (`é`, `ø`) is a false positive to confirm against the class, not a finding.

    ```sql
    SELECT slug, name FROM promoter
    WHERE slug <> regexp_replace(regexp_replace(replace(translate(lower(name), 'äöü', 'aou'), 'ß', 'ss'), '[^a-z0-9]+', '-', 'g'), '(^-|-$)', '', 'g');
    ```

- `PROMOTERS` — Coverage, as numbers rather than findings: rows with a `website_url`, rows with a `description`, rows with `reviewed_at`, out of the total.
  A drop against the last audit means a merge or a re-mint lost something.
- `—` — Suspiciously short (1–2 char) or suspiciously long name values in any of `artist`, `promoter`, `venue`, `genre_tag`.

### 4. Event type & genre correctness

- `—` — `event_type` / `status` / `event_artist.role` values outside the valid enum sets above.
- `GENRE` — `genre_tag.name` values that aren't really genres (event-format labels, series names, freeform fragments) that leaked past `GenreNormalizer`'s stop-list —
  cross-check against the `NON_GENRE_TOKENS` stop-list's intent.
- `GENRE` — Genre tags that are near-duplicates of each other (`Drum & Bass` vs `Drum and Bass` vs `DnB`).
- `GENRE` — Mismatch between raw `event.genre` text and the linked `event_genre_tag` rows (raw genre present but no tags extracted, or tags present that don't relate to
  the raw text).
- `EVENT_TYPE` — Type heuristic sanity: titles containing `Quiz`/`Karaoke`/`Party` mapped to a surprising `event_type`, or festivals (multi-day, `Festival` in title) typed as
  `CONCERT`.
- `EVENT_TYPE` — Keyword-driven type sanity (these types are inferred from title keywords in `EventTypeMapping`):
  a `SCREENING` whose title has no screening cue, a `READING`/`EXHIBITION` that looks like a gig, or — conversely — a reading/exhibition/screening keyword that
  landed in `OTHER`/`CONCERT` because its venue doesn't run the title classifier. Watch for keyword false positives (e.g. a musical `Songslam`
  mistyped `READING`, or `\bkino\b`/`slam` matching a substring of a band name).

### 5. Dates, times & prices

- `EVENT_DATE` — `event_date` in the far past (stale listings) or implausibly far future — the usual cause is year inference on a year-less date, and a
  venue declaring `EVENT_DATE` has dates that are derived rather than announced. Bucket by how far from today
  (`2026-07-07`).
- `DOORS_TIME` — `start_time` earlier than `doors_time` (doors should be ≤ start).
- `PRICE_PRESALE` / `PRICE_BOX_OFFICE` — Negative or absurd prices; `price_presale`/`price_box_office` with `free = true`; `price_currency` other than
  `EUR`.
- `PRICE_NOTE` — A `price_note` holding an amount (`12 €`, `AK 15`) while both price columns are `NULL` — the note caught what the columns should.
- `SOLD_OUT` — `sold_out = true` for a venue declaring `SOLD_OUT` in the limitations table — a flag no parser sets should never be true in the data.
- `CANCELLATION` — `status` other than `SCHEDULED` at a venue declaring `CANCELLATION`, for the same reason; and a source that has never stored a
  `CANCELLED` row is worth a line, because a parser that reads no cancellation cue shows a cancelled night as on.
- `—` — Many events from one `event_source` sharing the exact same date/time (parsing collapsed to a default).

### 6. Referential & consistency integrity

- `—` — Orphaned events (`event_source_id IS NULL`) — expected only for manually-created events; a scraped batch going NULL is a bug.
- `—` — Join rows pointing at non-existent parents (FKs should prevent this, but verify), duplicate
  `billing_order` within one event, or an event with multiple `HEADLINER` rows where that's unexpected.
- `PAGINATION`, or `—` — `event_source` health: rows in `FAILED`/stuck `RUNNING` status, `last_error` populated,
  `retry_count` at/over `max_retries`, `enabled = true` but never imported (`last_import_at IS NULL`), or `last_event_count = 0` on a source that should return
  events.
- `—` — Slug integrity: `slug` not matching a slugified form of `name`/`title`, or colliding-after-normalization slugs.

## Output

Write the report to `temp/data-quality-<YYYY-MM-DD>.md`, then run `scripts/format-markdown.sh` on it by name. Never commit it. Structure it as:

1. **Summary** — total rows per table, and a one-line-per-category verdict (clean / N issues).
2. **Findings**, grouped by category and ordered by severity:
    - 🔴 **wrong or missing user-visible data** · 🟠 **data-quality / noise** · 🟢 **cosmetic / edge case**.
    - Each finding: what it is, the SQL that found it, the **count**, 3–5 **sample rows**, the likely **root cause** (which importer / normalizer), and whether
      it's **NEW** or **KNOWN/accepted** (citing the issue number, or the source and aspect of the limitations-table row that covers it). Every finding
      names its aspect, `—` included.
3. **Recommended actions** — for NEW findings, point at the specific normalizer or scraper to fix (`canonicalArtistName`, `canonicalPromoterName`,
   `GenreNormalizer`, `isNonArtistName`,
   `stripArtistSuffix`, per-venue parser). If it is an accepted limitation to document rather than fix, suggest the `AcceptedLimitation` to add to that
   venue's `*_LIMITATIONS` declaration — the `LimitedAspect` and a one-sentence reason — rather than a paragraph of KDoc; if it is repairable, suggest an issue using the
   🔍 Importer / data defect form.

Keep the report skimmable and every claim backed by a query result. Do not apply fixes, edit importer code, or modify the database as part of the audit —
reporting is the deliverable. If the user wants a fix afterward, that's a separate, explicitly-requested step.

[`/plausibility-check`](plausibility-check.prompt.md) is the outside-in sibling: it reads the running site's API and the venues' own pages rather than the
database, needs a URL rather than a local PostgreSQL, and runs nightly from `agent-plausibility.yml`. Findings it reports about a row are the same defects
this audit finds in the table, seen from the other end, and both prompts key their checks by the same `LimitedAspect` names.
