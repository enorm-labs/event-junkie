# Traffic

How many people used the public site over the days OpenObserve keeps, and whether a crawler is posing as them. **Read-only**: the logs are the input,
a short answer in the terminal is the deliverable, and the only change it may propose is a new entry in `RENDERERS` in `scripts/o2-query.sh`.

## Important

- **Needs the WireGuard tunnel and the production kubeconfig context.** `scripts/ej.sh status` says whether the tunnel is up. If it is down, say so and
  stop. Bringing it up takes `sudo`, which the operator runs.
- **Every number is an estimate, never a visitor count.** No log carries an IP or any other visitor key. Say "about 250 sessions", never "250
  visitors".
- **Do not propose counting unique visitors, and do not propose storing daily totals.** The first is new processing under
  [Privacy & GDPR](../../AGENTS.md#privacy--gdpr--re-check-when-infrastructure-or-features-change). The operator decided against the second: 14 days
  of retention is enough, and no all-time total is wanted.
- **Today is a partial day.** Say up to which hour (UTC) it runs. Never compare it with a full day without saying so.
- **One window is not a trend.** Compare whole weeks, Monday to Sunday where the window allows, before you say "up" or "down".
- `git --no-pager`, `gh` non-interactive.

## Arguments

```text
/traffic [production|staging] [--hours N]
```

- **environment**: defaults to `production`. Staging has no visitors; use it only to test the script.
- **`--hours N`**: the window, ending now. Defaults to `336`, the 14 days kept.

## Step 1 — The counts

```sh
scripts/o2-query.sh <env> traffic --hours "$HOURS" > temp/traffic-<env>.json
jq '.totals' temp/traffic-<env>.json
jq -r '.days[] | [.day, .frontend.page_loads, .bff.sessions, .bff.night_sessions, .bff.lists, .frontend.in_burst] | @tsv' temp/traffic-<env>.json
```

It takes about 90 seconds. [OPENOBSERVE.md § Traffic](../../docs/ops/OPENOBSERVE.md#traffic-requests-not-visitors) says what each column counts. The
two to report:

| Column                | What it estimates                                                                                        |
| --------------------- | -------------------------------------------------------------------------------------------------------- |
| `frontend.page_loads` | First pages of a visit, from browser user agents. A crawler that sends a browser agent stays in          |
| `bff.sessions`        | Starts of the SPA, less the crawlers in `RENDERERS`. Later pages of a visit never reach nginx, only this |

The two should move together. Neither is a floor or a ceiling for the other.

## Step 2 — Is a crawler posing as people?

Read `night_sessions`: 00:00 to 04:00 UTC is 02:00 to 06:00 in Berlin. Before 2026-10-16 it ran at about 10 to 40 a night. Run Step 2 when one of these
holds:

- `night_sessions` is above about 60 on two nights in a row.
- `bff.sessions` jumps to a new level and stays there by day and by night.
- `bff.sessions` rises while `frontend.page_loads` does not.

```sh
scripts/o2-query.sh <env> renderers --hours 24 | jq -c '.agents[:8][]'
```

A row with a high `share`, `subtracted: false` and more than about 100 `detail_pages` is a crawler that runs the SPA and is counted as sessions. Propose
it for `RENDERERS` with its `delay` as the window, written `Name:low:high`. Check the result: rerun Step 1 with the entry added locally. `night_sessions`
must fall, and daytime sessions must stay above `page_loads`' level on a normal day. Do not commit or open a PR unless the operator asks.

A row whose `agent` is a browser user agent is a crawler that hides its name. It cannot go into `RENDERERS`, because the list matches by name. Report it.

## Step 3 — The answer

In the terminal, in this order. Write no file other than the JSON in `temp/`.

1. The headline: sessions and page loads for the window, and the window's dates.
2. A table of the days: `page_loads` and `sessions`. Mark today as partial.
3. The week-on-week change, from whole weeks only.
4. Anything that distorts the numbers: a burst day (`in_burst`, usually the Sunday DAST run), a renderer found in Step 2, a deployment gap.
5. One next action, or "nothing to do".
