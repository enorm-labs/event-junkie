# Importer Smoke Test

Verify one importer against the **live** site and the real database: register its source, run an import, and judge whether the data that landed is correct — and
whether anything else regressed. This is the runtime counterpart to the snapshot tests, scoped to a single source (unlike
[`/data-quality-audit`](data-quality-audit.prompt.md), which sweeps the whole database).

**Arguments**: `$ARGUMENTS` is the event source slug to smoke-test (e.g. `tresor`), optionally followed by `--full`. Without a slug, ask which source. The slug
is derived from the source **name** (e.g. "Astra Kulturhaus" → `astra-kulturhaus`).

## Important

- All mechanics live in `scripts/dev-env.sh` — use it rather than hand-rolling docker/curl/psql commands. Run `scripts/dev-env.sh` with no arguments for the
  command list.
- **Restart the importer after any code change.** `bootRun` does not hot-reload; a smoke test against a stale JVM tests the previous version of your scraper.
  After editing Kotlin: `scripts/dev-env.sh down && scripts/dev-env.sh up`.
- **Be polite to the venue** (ADR-007 scraping ethics). Import the source under test, not everything. Re-triggering the same source a handful of times while
  debugging is fine; looping it dozens of times is not.
- The default scope is **targeted**: seed and import only this source against the existing database. Use `--full` (wipe + re-seed everything) only when the
  change touches shared code — `scraper/*.kt` helpers (`ArtistNameMapping`, `EventTypeMapping`, `EventFieldMapping`, `ScrapingExtensions`,
  `DateParsingExtensions`, `AbstractTwoPageWebsiteImporter`, upsert/association services) or a Flyway migration. Those affect every importer, so every importer
  has to be re-run.

## Steps

### 1. Bring the environment up

```bash
scripts/dev-env.sh status
scripts/dev-env.sh up          # starts Postgres via bootRun + waits for /actuator/health
```

If the database is down or in an unknown state, `scripts/dev-env.sh db-reset` first. Scheduling is disabled by default in `up`, so nothing imports behind your
back — that is deliberate; don't re-enable it for a smoke test.

For `--full`: `db-reset` → `up` → `seed-all` (runs `http/importer/dev-seed.http` via ijhttp; scrapes every venue, takes minutes), then skip to step 4.

### 2. Take a baseline snapshot

```bash
scripts/dev-env.sh snapshot build/dev-env/before.tsv
```

Per-source event counts before your change lands. This is the regression guard in step 5.

### 3. Register the source

If the source already exists (a re-run), skip to step 4. Otherwise mirror the block you added to `http/importer/dev-seed.http` into two scratchpad JSON files
and register them:

```bash
# venue.json   → the POST /api/admin/venues body (name, address, city, postalCode, district, lat/lon, websiteUrl, description)
# source.json  → the POST /api/admin/event-sources body (name, url, sourceType, enabled, importIntervalMinutes, maxRetries)
#                venueId is injected by the script — leave it out
scripts/dev-env.sh seed-one <scratchpad>/venue.json <scratchpad>/source.json
```

It prints the slug to use below. Both POSTs are re-run safe: an existing venue/source is reused with a warning.

### 4. Import and inspect

```bash
scripts/dev-env.sh import <slug>       # triggers, then polls until SUCCESS/FAILED
scripts/dev-env.sh check <slug>        # summary · field coverage · types · suspicious rows · sample
```

On `FAILED`, read `lastError` and then the stack trace in `build/dev-env/importer.log`.

### 5. Regression check

```bash
scripts/dev-env.sh snapshot build/dev-env/after.tsv
scripts/dev-env.sh diff-snapshot build/dev-env/before.tsv build/dev-env/after.tsv
```

In targeted mode only the new source should move. Any other source marked `REGRESSION`/`GONE` means shared code changed behaviour — investigate before shipping.
(In `--full` mode every source is re-imported, so compare against a snapshot from the previous full run and treat a drop >20% at any source as a finding, not a
count that happens to differ because a venue published a new programme.)

### 6. Judge the data — this is the part that matters

`check` output is evidence, not a verdict. **Open the live listing page and compare the first ~3 events by title and date against the sample rows.** A scraper
that parses *something* plausible from the wrong container passes every automated check and is still wrong.

**Hard fails — do not ship:**

- 0 events imported, or status `FAILED`.
- Every event in the past, or dates that don't match the site (a common year-inference bug on year-less German dates).
- Event count far below what the listing shows (silently dropped events — check the WARN lines in `build/dev-env/importer.log`).
- Placeholder or duplicated titles, HTML fragments or navigation text stored as titles, or `source_id` not prefixed with `<enum-value-lowercased>:`.
- Titles/dates don't match the live page.
- Another source lost events.

**Soft findings — ship, but write them down.** Where depends on what kind of finding it is:

1. **A gap that is ours and worth repairing** → **an issue**, using the 🔍 Importer / data defect form (or `/new-issue`). Check `build/BACKLOG.md` for a
   duplicate first — several of these defects are cross-cutting and already filed. We lose or mangle data the source *did* publish, or our model has nowhere to put
   it: a mis-split lineup, a field dropped because it has no column, an entity that won't merge with its counterpart from another venue. If it is a one-line
   fix, just make it instead.
2. **A limitation we accept** → the importer's or scraper's KDoc, next to the code that causes it. This is also where a field the site simply doesn't publish
   goes (no prices, no doors time, no images, a `TBA` lineup), and `event_type = OTHER` where the site gives no usable signal. The importer stored everything
   that was there, so there is nothing to action.

Read the venue's existing KDoc before flagging anything — most of what a first look turns up is already recorded there as a deliberate decision, artist-less
concerts included.

### 7. Report

State the verdict as **PASS**, **FIX** (with the specific defect and where it likely lives), or **BLOCKED** (site can't be scraped as built). Quote concrete
numbers and sample rows — never a bare "looks good". If you documented soft findings, say which file you wrote them to.

## Checklist

- [ ] Importer restarted after the last code change (not testing a stale JVM)
- [ ] Baseline snapshot taken before importing
- [ ] Import reached `SUCCESS` with a plausible `lastEventCount`
- [ ] `check <slug>` reviewed: dates, times, prices, types, artists, `source_id` prefix
- [ ] Sample rows compared against the live listing page
- [ ] `diff-snapshot` shows no other source losing events
- [ ] Soft findings recorded — repairable defects as issues (after a `build/BACKLOG.md` duplicate check), accepted limitations in the importer/scraper KDoc
