# Next Importer

Take **one** venue from the 🔨 Ready list in [EVENT_DATA_SOURCES.md](../../docs/EVENT_DATA_SOURCES.md) all the way to an open pull request: scaffold the
importer, smoke-test it against the live site and the database, fix what's fixable, document what isn't, update the source inventory, and ship it.

One invocation = one venue = one PR. Repeat the command to do the next one, or run it under `/loop` to work through the backlog unattended. The loop counter is
the document itself: a venue is done when its row has moved out of 🔨 Ready, so the command is safe to re-run, resume after an interruption, and start from a
fresh context every time.

**Arguments**: `$ARGUMENTS` may name a venue to pick (e.g. `Tresor`). Without arguments, pick the next one yourself per step 1.

## Important

- Run git commands with the pager disabled (`git --no-pager …`) — see AGENTS.md.
- This skill commits and pushes via [`/open-pr`](open-pr.prompt.md), which carries the user's explicit permission for that. Nothing here merges a PR — review
  stays with the user.
- **Stop after one venue.** Do not start the next one in the same run, even if everything went smoothly.
- **Never ship an importer you haven't seen produce correct data.** If the gate in step 5 doesn't pass, open no PR and stop the loop — a red or hallucinated
  importer merged unattended is worse than an unimplemented one.

## Steps

### 1. Pick the venue

```bash
git --no-pager status --short          # must be clean
git checkout main && git pull
```

A dirty tree means an earlier run left work behind — stop and report instead of sweeping it into this PR.

Read the **🔨 Ready to implement** table. Take the requested venue, or else the topmost row whose `EventSource` enum value does not yet exist in
`events-importer/src/main/kotlin/de/norm/events/scraper/EventSource.kt` (rows are already priority-sorted; High before Medium before Low).

Two special cases in that table: some rows explicitly **share one importer** with another row (e.g. MS Hoppetosse ↔ Club der Visionaere, Ufo im Velodrom ↔
Max-Schmeling-Halle via the VELOMAX listing). When you pick one of those, implement the shared importer and move **both** rows in step 6 — as
`Kantine am Berghain` already does under ✅ Imported.

Announce which venue you picked and why before continuing.

> The previous venue's PR is probably still open, so `main` won't have it. That's expected. Conflicts will be limited to the `dev-seed.http` header list and
> the two `EVENT_DATA_SOURCES.md` rows, and are resolved at merge time.

### 2. Scaffold

Run [`/scaffold-importer`](scaffold-importer.prompt.md) with the venue name and the URL from the table. It owns recon (robots.txt, JSON-API-first), the enum
value, the scraper/importer classes, fixtures, unit tests, and the `dev-seed.http` block. Follow it fully — don't shortcut its checklist.

If recon concludes the site is unscrapable as built (JS-only with no API, hard cookie wall), skip to step 7 **Blocked**.

### 3. Fast feedback

```bash
./gradlew :events-importer:test --tests '*<Venue>*'
./gradlew :events-importer:ktlintFormat :events-importer:ktlintCheck :events-importer:detekt
```

Fixture-based tests are the cheap loop — get them green before touching the database.

### 4. Smoke-test against reality (repeat, max 3 rounds)

Run [`/importer-smoke`](importer-smoke.prompt.md) for the new source. Restart the importer first (`scripts/dev-env.sh down && scripts/dev-env.sh up`) so it runs
your new code.

Each round: read the verdict, fix the defect, re-run the unit tests, re-run the smoke test. When you fix a parsing bug, **also add or extend a fixture test that
would have caught it** — that's what stops the regression from coming back.

After 3 rounds without a PASS, stop iterating and go to step 7. Repeated identical failures mean the approach is wrong, not that another round will help.

### 5. Gate — all of these before shipping

- [ ] New scraper + importer tests green
- [ ] `/verify` clean (ktlint, detekt, build, Kover, frontend checks)
- [ ] Smoke test PASS: events > 0, dates and titles match the live listing, `source_id` correctly prefixed
- [ ] `diff-snapshot` shows no other source losing events
- [ ] Soft findings written down — a repairable defect as an issue (🔍 Importer / data defect, after a `build/BACKLOG.md` duplicate check), an accepted
      limitation in the importer/scraper KDoc; a field the venue
      doesn't publish is not a bug (see `/importer-smoke` step 6)

Any unchecked box → step 7, no PR.

### 6. Update the inventory

In `docs/EVENT_DATA_SOURCES.md`:

- Move the venue's row (both rows, for a shared importer) from **🔨 Ready to implement** to **✅ Imported**, keeping alphabetical order, and rewrite the last
  column as the _Comment_ the Imported table uses: platform + parsing quirks, ≤ 50 chars, not the "why/what it needs" phrasing.
- Update the counts in the status table at the top (Imported +1, Ready −1) **and** the "N importer classes cover M sources" line under the Imported table.
- Check the `dev-seed.http` header comment lists the new source alphabetically (`/scaffold-importer` step 7 covers this — verify it happened).

### 7. Ship, or stop

**PASS** → run [`/open-pr`](open-pr.prompt.md). Conventional Commits scope `importer` or `scraper`; the Testing section must state the smoke-test numbers (event
count, date range, what you compared against the live site). Then report the PR URL and stop.

**Blocked** (site unscrapable) → move the row to **⛔ Blocked / deferred** with the reason and what it would need (e.g. "Headless browser"), update the counts,
and open a docs-only PR. Then stop.

**Not fixable in 3 rounds** → revert nothing; explain precisely where it stands (what parses, what doesn't, what you tried), open an issue recording it, leave the
branch uncommitted, and stop the loop so the user can look. Do not open a PR for a half-working importer.

**Infrastructure broken** (docker won't start, port 8081 taken, build red on `main` before your change) → stop immediately and report. Don't try to work around
it.

### 8. Hand back

Print: venue implemented, PR URL (or why not), smoke-test numbers, anything documented as a known issue, and how many rows remain in 🔨 Ready. Then **stop** —
the next venue is the next invocation.
