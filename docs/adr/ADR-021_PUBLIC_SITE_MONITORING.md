# ADR-021: The public site is watched by an external monitor, with a daily probe beside it

## Status

**Accepted (2026-08-31) — a Better Stack uptime monitor polls the site every three minutes, and
`site-probe.yml` stays as a daily dead-man's switch. Both run off Hetzner.**

**Implemented in [#889](https://github.com/enorm-labs/event-junkie/issues/889)**, which measured the failure
that forced this. The monitor is created by hand in the Better Stack console, so this repository holds the
probe and the reasoning, and not the monitor.

Supersedes nothing. It replaces a number rather than a decision.
[ADR-015](ADR-015_OBSERVABILITY_STACK.md) chose OpenObserve for what happens inside the cluster, and decided
nothing about watching the site from outside. [ADR-018](ADR-018_PROBE_SEMANTICS.md) decided what a Kubernetes
probe may assert, which is a different question asked inside the same cluster. Neither covers the case where
the cluster is gone.

## Context

### The site probe asked GitHub for a 15-minute cron, and did not get one

`site-probe.yml` ran every fifteen minutes on paper from the day it was written. The `site-production` check
on healthchecks.io was armed on 2026-08-30, with a 15-minute period and a 30-minute grace. **It alarmed
within hours, and the site was healthy throughout.** There were no pod restarts, the node sat at 25% CPU, and
ten requests in sequence returned `200` with the expected body.

The probe was measured again on 2026-08-31, over a fresh five-day window:

| `7,22,37,52 * * * *` | Measured                    |
| -------------------- | --------------------------- |
| Asked for            | 15 min                      |
| Shortest actual      | 29 min                      |
| **Median actual**    | **129 min**                 |
| Longest actual       | 678 min                     |
| Gaps over the grace  | **37 of 38**                |
| Runs delivered       | **39 of 480 requested, 8%** |

The second measurement matters more than the first. One day of bad numbers is an Actions incident. Five days
is a policy. GitHub documents that it throttles scheduled workflows on shared runners, and this repository
sees it on exactly one workflow. Every other schedule here is daily or weekly.

### The daily schedules are honoured, and that is the number this decision needs

| Workflow                         | Cron         | Median gap | Worst gap |
| -------------------------------- | ------------ | ---------- | --------- |
| `dependency-check-scheduled.yml` | `17 3 * * *` | 24.0 h     | 34.2 h    |
| `image-scan-scheduled.yml`       | `41 4 * * *` | 25.5 h     | 34.3 h    |

So GitHub holds back the high-frequency cron and delivers the daily ones. Any new cadence must come from
this table, not from a guess. **Reading the cron and doubling it is what produced the flapping.**

### What a candidate had to satisfy

1. **It must not run on Hetzner.** A monitor on the infrastructure it watches cannot report that
   infrastructure's death. This is the argument the whole alerting design rests on.
2. **It must not route through the in-cluster Signal bridge**, or both layers die together.
3. **It must assert content, not only reachability.** A 200 that serves the wrong page is still an outage.
4. **It must report an outage fast enough to act on.**
5. **It should produce an availability figure.** [HEALTHCHECKS.md](../ops/HEALTHCHECKS.md) needs one, and
   healthchecks.io keeps about 25 hours of history on the free plan.

## Candidate options

**1. Widen the grace and change nothing else.** Set the period and the grace to three hours each. This was
applied on 2026-08-30 as a holding position, and it does stop the flapping. It costs a detection latency of
up to six hours. An availability monitor that takes a quarter of a day to notice an outage is an alarm for a
dead box. It fails requirement 4 and it fails requirement 5.

**2. Move to an external HTTP uptime monitor, and delete the probe.** Fetching a URL and asserting the result
is what those services do natively, at one to five minute intervals, with no cron to throttle. It satisfies
every requirement. It costs the assertions, which move out of git and into a form in somebody's console.

**3. Run the same probe on a scheduler that honours its schedule.** A small host off Hetzner keeps the
assertions in code and meets requirement 4. It adds a host to own, patch, pay for and monitor. The liveness
question then applies to that host, one layer further out.

## Decision

**Options 2 and 3 answer different halves of the problem, so take option 2 and keep the part of the probe
that already works.**

|                          | Fast path                             | Slow path                                 |
| ------------------------ | ------------------------------------- | ----------------------------------------- |
| **Mechanism**            | Better Stack uptime monitor           | `site-probe.yml`, then healthchecks.io    |
| **Cadence**              | 3 min                                 | daily                                     |
| **Shape**                | Active poll, alerts on a failure      | Conditional ping, silence is the alarm    |
| **Reports an outage in** | about 6 min                           | up to 48 h                                |
| **What it is for**       | Alerting, and the availability figure | Assertions in git, on an independent path |

**The reason that settled it: a monitor whose own liveness is less reliable than the thing it watches is
worse than no monitor.** It teaches the reader to ignore it, and the day it means something nobody believes
it. The 15-minute cron had exactly that shape. The site was up for every one of the 28 intervals that
breached the grace.

**Better Stack, and the domicile question was weighed rather than skipped.** Better Stack, Inc. is a Delaware
corporation, and it says it processes personal data primarily in the European Union. An EU-incorporated
vendor was preferred at first and then rejected on the evidence. The monitor fetches a public page, so it
receives no personal data and processes nothing on our behalf. There is no Art. 28 relationship to place in
any jurisdiction, and the domicile therefore buys a paragraph rather than a protection. Two of the
EU-incorporated candidates run on Hetzner itself, which fails requirement 1 outright. See
[LEGAL.md](../LEGAL.md) §14.

**The free plan was checked against the form, not against the pricing page, and it differs.** The check frequency
floor is 3 minutes rather than the 30 seconds advertised. Certificate and domain expiry warnings are upgrade-only.
E-mail is the only free alert channel. None of that changes the decision, because 3 minutes satisfies requirement 4
and e-mail is the channel this project already uses.

**The daily probe pings a check at a 24-hour period and a 24-hour grace.** Both numbers come from the second
table above. 48 hours of tolerance against a 34-hour worst case leaves headroom, which is what the old
15/30 pair did not have.

## Consequences

### Positive

- **An outage is reported in minutes rather than hours**, and the report is trustworthy enough to act on.
- **The availability figure stops waiting on the cluster.** The monitor keeps its own uptime history, so
  [#880](https://github.com/enorm-labs/event-junkie/issues/880) is now a long-term retention dependency
  rather than the source of any figure at all.
- **Two independent paths watch the site.** They share no host, no scheduler and no notification channel.
- **A bad certificate is caught from outside.** The monitor verifies TLS, so an expired or untrusted certificate fails
  the check. Advance warning of an expiry stays with `ej-certificate-expiry`, because Better Stack gates that behind a
  paid plan.

### Negative

- **`SITE_URL` now lives in two places.** The repository variable drives the probe, and the monitor's own URL
  field drives the fast path. At go-live both change, and forgetting one leaves a probe pointed at a
  rehearsal hostname that still resolves. The go-live checklist carries the pair.
- **Half the assertions left git**, and the duplication is real. The URL and the body string now exist in a console
  form as well as in `site-probe.yml`. **The daily probe closes this by comparing them.** It reads the monitor over the
  Better Stack API and asserts ten fields against the workflow, `SITE_URL` and `EXPECTED_CONTENT` included. Drift turns
  the workflow red and leaves the healthchecks.io check green, because a moved console is not an outage.
- **The slow path watches the site, not the fast path.** Nothing detects Better Stack going quiet except
  Better Stack. What the second path buys is independence of fate, and not surveillance of the first.
- **A free tier is a dependency with no contract.** The plan can change, and the account can lapse.

## When to revisit

**At go-live** ([#285](https://github.com/enorm-labs/event-junkie/issues/285)), when the probe stops pointing
at a rehearsal hostname and the availability figure gets its first real month.

**If the free tier stops carrying keyword checks**, which is the feature requirement 3 depends on.

## References

- [#889](https://github.com/enorm-labs/event-junkie/issues/889) — the measurement and the three options ·
  [#271](https://github.com/enorm-labs/event-junkie/issues/271) — the alerting design, and the closure this
  corrects · [#880](https://github.com/enorm-labs/event-junkie/issues/880) ·
  [#877](https://github.com/enorm-labs/event-junkie/issues/877) ·
  [#285](https://github.com/enorm-labs/event-junkie/issues/285)
- `.github/workflows/site-probe.yml` — the slow path, and the measurement in its schedule block
- [docs/ops/HEALTHCHECKS.md](../ops/HEALTHCHECKS.md) — how to create both, and how to prove each one fires
- [docs/ops/PLATFORM_SETUP.md](../ops/PLATFORM_SETUP.md) §4 — the two-layer argument this sits inside
- [ADR-015](ADR-015_OBSERVABILITY_STACK.md) (inside the cluster) · [ADR-018](ADR-018_PROBE_SEMANTICS.md) (inside the pod)
- [GitHub Actions — schedule](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule)
