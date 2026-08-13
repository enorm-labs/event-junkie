# Data Quality Audit

Audit the `events` database for data-quality issues and oddities: missing or wrong data, duplicates, typos, mis-parsed artist/promoter names, missing event
types and genres, and referential problems. This is a **read-only investigation** — inspect and report, never mutate. Produce a prioritized report; propose
fixes but do not apply them unless the user explicitly asks.

## Scope & intent

The data is scraped from Berlin venue websites by the importers in `events-importer` (see `AGENTS.md` for the domain model). The goal is to surface
**actionable** quality problems — parsing bugs, normalization gaps, and oddities worth a human's attention — and to distinguish them from limitations already
documented and accepted.

Before reporting anything, check whether the finding is already known. Two places record that: the open issues, mirrored in `build/BACKLOG.md` (defects already
queued for repair — `grep` it, or `gh issue list --label importer`) and the KDoc of the importer and scrapers under `scraper/<venue>/` (limitations the venue's parser accepts deliberately). A finding matching either —
artist-less concerts at Badehaus/Privatclub, `eventType` defaulting to `OTHER`, first-page-only pagination — must be labelled **known/accepted** and separated
from genuinely new ones. Don't re-litigate accepted trade-offs.

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

- `event.event_type`: `CONCERT`, `FESTIVAL`, `PARTY`, `QUIZ`, `CLUB_NIGHT`, `SHOW`, `SCREENING`, `EXHIBITION`, `READING`, `OTHER`
- `event.status`: `SCHEDULED`, `RELOCATED`, `CANCELLED`, `POSTPONED`
- `event_artist.role`: `HEADLINER`, `SUPPORT`, `DJ`

## What to check

Work through these categories. For each, run SQL, then judge whether hits are real problems or noise. Break findings down **per venue / per source** where
useful — a problem concentrated at one venue usually points at that importer.

### 1. Missing / required data

- Events with no artists (`event` with no `event_artist` row), broken down by venue and `event_type`. Several venues accept this deliberately — check the
  importer's KDoc before flagging as new.
- `NULL`/empty `title`, `slug`, `source_id`, `event_date`, `venue_id`.
- Events with no genre at all: both `event.genre IS NULL` and no `event_genre_tag` rows.
- Missing `event_type` signal: rows defaulting to `OTHER` (per venue — which sources never set a type?).
- Missing structured fields that are usually recoverable: no `start_time`/`doors_time`, no price fields _and_ no `price_note`, no `image_url`, no `ticket_url`/
  `source_url`.
- `venue` rows missing `district`, `latitude`/`longitude`, or `website_url`.
- Whitespace-only or placeholder text values (e.g. `''`, `'-'`, `'TBA'`, `'N/A'`, `'null'`) in name/title fields.

### 2. Duplicates & entity fragmentation

- Artists / promoters / genre_tags that are almost certainly the same entity under different names:
  same `slug` prefix, case-only differences, punctuation/spacing variants, trailing `Live`/tour suffixes, ALL-CAPS vs mixed case. (Slugs are case-insensitive so
  exact-slug dupes shouldn't exist, but _near_-duplicate slugs do — that's fragmentation.)
- Group by `lower(regexp_replace(name, '[^a-z0-9]', '', 'gi'))` to surface names that normalize to the same token but have distinct rows.
- Events that look like the same real-world event under different `source_id`s (same venue + date + similar title) — the importers dedupe by `source_id`, so
  cross-source or re-listed duplicates slip through.
- Orphan `artist`/`promoter`/`genre_tag` rows referenced by zero events (dead rows from renames/reparsing).

### 3. Mis-parsed artist & promoter names

- Non-artist strings sitting in `artist.name`: event-format words (`Quiz`, `Karaoke`, `Open Mic`,
  `Festival`, `Special`, `Tour`, `Support`, `Live`, `Warm Up`, `Aftershow`, `w/`, `presents`, `vs`), standalone symbols, pure numbers, or very long strings (a
  whole title parsed as one artist).
- Residual ALL-CAPS artist names — `canonicalArtistName`'s de-shouting is casing-only and its `ACRONYMS` set is curated, so a genuine all-caps name that is not
  in it gets title-cased and a new stylised one slips through until added.
- Artist/promoter names with leftover HTML entities (`&amp;`, `&#039;`), stray encoding (`Ã¤`, `â€™`), leading/trailing punctuation or whitespace, doubled
  spaces.
- Promoter names that are actually venue names, generic labels (`Presents`, `Konzert`), or descriptors that should have been stripped/merged.
- Suspiciously short (1–2 char) or suspiciously long name values in any of `artist`, `promoter`, `venue`, `genre_tag`.

### 4. Event type & genre correctness

- `event_type` / `status` / `event_artist.role` values outside the valid enum sets above.
- `genre_tag.name` values that aren't really genres (event-format labels, series names, freeform fragments) that leaked past `GenreNormalizer`'s stop-list —
  cross-check against the `NON_GENRE_TOKENS` stop-list's intent.
- Genre tags that are near-duplicates of each other (`Drum & Bass` vs `Drum and Bass` vs `DnB`).
- Mismatch between raw `event.genre` text and the linked `event_genre_tag` rows (raw genre present but no tags extracted, or tags present that don't relate to
  the raw text).
- Type heuristic sanity: titles containing `Quiz`/`Karaoke`/`Party` mapped to a surprising `event_type`, or festivals (multi-day, `Festival` in title) typed as
  `CONCERT`.
- Keyword-driven type sanity (these types are inferred from title keywords in `EventTypeMapping`):
  a `SCREENING` whose title has no screening cue, a `READING`/`EXHIBITION` that looks like a gig, or — conversely — a reading/exhibition/screening keyword that
  landed in `OTHER`/`CONCERT` because its venue doesn't run the title classifier. Watch for keyword false positives (e.g. a musical `Songslam`
  mistyped `READING`, or `\bkino\b`/`slam` matching a substring of a band name).

### 5. Dates, times & prices

- `event_date` in the far past (stale listings) or implausibly far future — the usual cause is year inference on a year-less date. Bucket by how far from today
  (`2026-07-07`).
- `start_time` earlier than `doors_time` (doors should be ≤ start).
- Negative or absurd prices; `price_presale`/`price_box_office` with `free = true`; `price_currency`
  other than `EUR`; `sold_out = true` for a venue whose importer KDoc says it cannot detect sold-out at all (SO36).
- Many events from one `event_source` sharing the exact same date/time (parsing collapsed to a default).

### 6. Referential & consistency integrity

- Orphaned events (`event_source_id IS NULL`) — expected only for manually-created events; a scraped batch going NULL is a bug.
- Join rows pointing at non-existent parents (FKs should prevent this, but verify), duplicate
  `billing_order` within one event, or an event with multiple `HEADLINER` rows where that's unexpected.
- `event_source` health: rows in `FAILED`/stuck `RUNNING` status, `last_error` populated,
  `retry_count` at/over `max_retries`, `enabled = true` but never imported (`last_import_at IS NULL`), or `last_event_count = 0` on a source that should return
  events.
- Slug integrity: `slug` not matching a slugified form of `name`/`title`, or colliding-after-normalization slugs.

## Output

Write the report to `docs/data-quality/audit-<YYYY-MM-DD>.md` (create the directory if needed; use today's date). Structure it as:

1. **Summary** — total rows per table, and a one-line-per-category verdict (clean / N issues).
2. **Findings**, grouped by category and ordered by severity:
    - 🔴 **wrong or missing user-visible data** · 🟠 **data-quality / noise** · 🟢 **cosmetic / edge case**.
    - Each finding: what it is, the SQL that found it, the **count**, 3–5 **sample rows**, the likely **root cause** (which importer / normalizer), and whether
      it's **NEW** or **KNOWN/accepted** (citing the issue number or the KDoc that records it).
3. **Recommended actions** — for NEW findings, point at the specific normalizer or scraper to fix (`canonicalArtistName`, `canonicalPromoterName`,
   `GenreNormalizer`, `isNonArtistName`,
   `stripArtistSuffix`, per-venue parser). If it is an accepted limitation to document rather than fix, suggest the KDoc it belongs in; if it is repairable,
   suggest an issue using the 🔍 Importer / data defect form.

Keep the report skimmable and every claim backed by a query result. Do not apply fixes, edit importer code, or modify the database as part of the audit —
reporting is the deliverable. If the user wants a fix afterward, that's a separate, explicitly-requested step.
