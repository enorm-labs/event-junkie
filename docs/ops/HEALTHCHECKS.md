# Watching from outside the cluster

How the external alerting works, how to add a check or a monitor when an environment appears, and how to prove each one
actually fires.

**Two mechanisms live here, and they are not interchangeable.** healthchecks.io holds the dead-man's switches, which
alarm on silence. Better Stack polls the public site and alarms on a failure. [ADR-021](../adr/ADR-021_PUBLIC_SITE_MONITORING.md)
records why the site needs both and the backups need only one.

## The short version

```sh
sudo -u postgres /usr/local/bin/walg check     # on the node: pings on success, exits 1 on any failure
sudo grep -c '^HEALTHCHECK_URL=' /etc/wal-g/credentials.env   # expect exactly 1, never 2
```

- **Two healthchecks.io checks per environment**, `walg-<environment>` and `site-<environment>`. One account, one project, one channel.
- **One Better Stack monitor per public environment**, `site-<environment>`. It polls every three minutes and alerts in about six.
- **The node pings out on success.** Silence is the alarm, so the alerting survives the thing it watches.
- **A ping URL is a credential.** It lives on the node and nowhere else.
- **A check nobody saw fire proves nothing.** Induce the disk assertion — §Proving it fires.

> **Every ping URL on this page is a credential.** Anyone holding one can ping the check and suppress its alarm. That is worse than a leaked read-only token,
> because the failure it causes is _silence_. They live in `/etc/wal-g/credentials.env` on the node and nowhere else. Not in this repository, not in
> an issue, not in a pull request, not in a values file. There is deliberately no table of URLs below.

## Why an external service at all

Everything else this project monitors runs inside the cluster it is monitoring. That is fine for a metric and useless for an outage: **a dead-man's switch on
infrastructure cannot report that infrastructure's death.** A pod that is meant to alert when the node stops is a pod that stops when the node stops.

So the arrangement is inverted. The node **pings out on success**, and the absence of a ping is what raises the alarm. The alerting therefore works precisely
when the thing it watches does not. healthchecks.io is deliberately off Hetzner for the same reason
([#271](https://github.com/enorm-labs/event-junkie/issues/271), [#518](https://github.com/enorm-labs/event-junkie/issues/518)).

**One account, one project, one notification channel** for all of these. A second notification stack is the avoidable mistake: it is one more thing to configure
correctly, and its own failure is silent by construction.

**Better Stack is a second stack, and it is the deliberate exception.** The rule above is about notification paths that
duplicate each other. This one does not duplicate. healthchecks.io can only alarm on silence, so it can never report an
outage faster than the period it waits out. [ADR-021](../adr/ADR-021_PUBLIC_SITE_MONITORING.md) has the argument.
**Point it at the same destination as the healthchecks.io channel.** Two senders and one inbox keeps the cost of the
exception to configuration, and does not add a second place to look.

## What is watched

| Check                | Watches                                                 | Pinged by                                                      | Issue |
| -------------------- | ------------------------------------------------------- | -------------------------------------------------------------- | ----- |
| `walg-<environment>` | PostgreSQL backups are healthy                          | `walg check`, hourly via `walg-check.timer`, on success        | #518  |
| `site-<environment>` | DNS, TLS, the ingress and the application, from outside | `.github/workflows/site-probe.yml`, **daily**, on success only | #889  |

**A third row is not a healthchecks.io check at all.** The Better Stack monitor watches the same things as
`site-<environment>`, every three minutes instead of once a day. It alerts on a failure rather than on silence. It is
the path that reports a real outage. The daily check is the path that does not share a fate with it.

`site-staging` is not a check anybody should create. Staging has no public `A` record and no public
80/443, so nothing outside can probe it. `site-production` is the only one of that row that can
exist before go-live.

**The site probe is armed.** `HEALTHCHECKS_PING_URL` was set on 2026-08-30, and `SITE_URL` points the workflow at
`prod-check.event-junkie.de`. The probe still skips when the secret is absent, which is the state any new environment
starts in. Staging is not on the internet by design ([PLATFORM_SETUP](PLATFORM_SETUP.md) §4a), so it never gets one.

A silent skip would be the wrong shape here, because this repository was bitten twice by one.
So the skip is loud in the Actions tab, rather than a green tick that means nothing.

### What the site probe actually asserts

Like `walg check`, it is conditional rather than a heartbeat, and for the same reason: **an
unconditional ping proves only that the pinger ran.** Before pinging it fetches the site over the public
internet and asserts

1. the request completes at all. Curl's exit code maps to a named cause. DNS failure, connection
   refused, timeout and a bad certificate each get their own alert text.
2. the status is **200**.
3. the body contains the product name. A 200 serving the wrong page is still an outage.

Only then does it ping. **On a definite failure it pings `/fail`** rather than waiting out the grace
period, which is faster. Silence still covers the case that cannot ping at all, namely GitHub being
unable to run the workflow.

**It runs on GitHub, and that is the point.** An alerting path that runs on the node it monitors cannot
report that node's death. That is the argument this document opens with, one layer out. Whatever else
moves, this must not move into the cluster.

### What the backup check actually asserts

`walg check` is deliberately **not** "did the last job error". It asserts three things and exits non-zero if any is false
([BACKUPS.md](BACKUPS.md) §6):

1. there is a base backup at all.
2. the newest is younger than **26 hours**.
3. `/var/lib/postgresql` is below **85%**. A stalled `archive_command` does not stop backups. It fills `pg_wal`, and the volume is 10 GB.

Only when all three pass does it ping. That conditionality is the whole design: an unconditional heartbeat proves only that the heartbeat ran.

## Creating a check for a new environment

| Field        | Value                | Why exactly this                                                                                                   |
| ------------ | -------------------- | ------------------------------------------------------------------------------------------------------------------ |
| **Name**     | `walg-<environment>` | Per environment. A shared check goes green while one of two nodes has been archiving nothing for a week            |
| **Schedule** | Simple               | Period plus grace; the ping cadence is a timer, not a cron expression worth restating                              |
| **Period**   | `26h`                | Matches the bound `walg check` asserts on base-backup age — see the note below, because it is not the only reading |
| **Grace**    | `2h`                 | `walg-check.timer` runs hourly, so a missed run is a reboot and two is a signal                                    |
| **Channel**  | the shared one       | See above                                                                                                          |

### The period is a detection-latency choice, and it is worth understanding before changing it

**26h is configured, and it is the conservative reading.** It comes from what `walg check` asserts about _base-backup age_. The argument is that a shorter period
would alert on a state the check itself considers fine.

The other reading is that those are two different clocks. The check pings **hourly**, and the period only governs how long _silence_ is tolerated. Silence means
"the check did not run, or did not pass", which is worth knowing either way. On the configured values, a node that wedges or starts failing
its assertions is reported after **26h + 2h ≈ 28 hours**. At `1h`/`2h` it would be about three, and the grace still absorbs a reboot.

Neither is wrong. **28 hours is well inside the window that matters for a backup.** The thing being protected is a restore nobody needed yet, not a serving path.
And a longer period is the one that cannot produce a false alarm. Change it deliberately, and change it in both places: here and the check.

## Watching the site: two paths, created separately

The site needs both. Create the monitor first, because it is the one that reports a real outage.

### 1. The Better Stack monitor — the fast path

**Set _Alert us when_ to `URL doesn't contain keyword` first.** The monitor type defaults to
`URL becomes unavailable`, and the keyword field stays hidden and disabled until you change it. Nothing on the page
says the field is one dropdown away, so it reads as a missing feature.

**The keyword type does not replace the availability check.** It raises an incident when the page does not contain the
keyword **or** when the URL becomes unavailable. So one monitor still covers DNS failure, a bad certificate, ingress
misrouting and a non-200.

| Field                           | Value                                             | Why exactly this                                                                                                           |
| ------------------------------- | ------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------- |
| **Alert us when**               | `URL doesn't contain keyword`                     | **Do this first.** The keyword field is hidden under every other type                                                      |
| **Keyword to find on the page** | `Event Junkie`                                    | The product name, not a layout detail. A redesign must not alert, a wrong page must. Matching is case insensitive          |
| **URL**                         | what production actually serves                   | `prod-check.event-junkie.de` today. The apex at go-live — change it in both places                                         |
| **Pronounceable monitor name**  | `site-<environment>`                              | Defaults to the hostname. Rename it to match the healthchecks.io check                                                     |
| **Check frequency**             | `3 minutes`                                       | The floor on the free plan. Everything faster is disabled. An outage reaches somebody in about six minutes                 |
| **Confirmation period**         | `3 minutes`                                       | Defaults to immediate. One blip must never alert, or the alert gets muted. This is the probe's `--retry 3`, in their words |
| **HTTP method**                 | `GET`                                             | As the probe does                                                                                                          |
| **Request timeout**             | `30 seconds`                                      |                                                                                                                            |
| **SSL/TLS verification**        | `On`                                              | Free. An expired or untrusted certificate then fails the check                                                             |
| **Regions**                     | Europe                                            |                                                                                                                            |
| **Notify**                      | E-mail, to the same address as the shared channel | **Never the in-cluster Signal bridge**, or both layers die together                                                        |

**Two things the free plan does not give, and neither is a gap worth paying for today.**

- **Advance warning of certificate expiry.** _SSL expiration_ and _Domain expiration_ are upgrade-only. Leave both at
  "Don't check". The certificate is still watched, because _SSL/TLS verification_ is on and an expired certificate
  fails the check. What is missing is the warning **before** it expires, and `ej-certificate-expiry` in OpenObserve is
  the rule that does that.
- **Call, SMS and push.** E-mail is the only free channel, and it is the one this project uses.

**No drill proved that the alert reaches a person. That is the open question.** _Notify the primary responder_ reads
the primary on-call schedule. **On-call scheduling is a Responder feature.** The free plan does not carry it, and the
console warns that there is no one to notify. So where the e-mail lands stays a guess until somebody receives one.

**A monitor that alerts nobody is the exact failure this whole page exists to prevent**, and it is indistinguishable
from a working one. Only the drill below settles it. Run it before ticking any go-live row.

### The console is checked against the repository, once a day

The settings above live in a form, and a form is not reviewed. So `site-probe.yml` reads the monitor back over the
Better Stack API and asserts ten of its fields, `SITE_URL` and the keyword included. **Drift turns the workflow red and
leaves the check green.** The site is up, and the console moved. Those are different facts, and they are reported
differently.

`BETTERSTACK_API_TOKEN` is the repository secret that permits it. **Use an Uptime API token, scoped to the team, not a
global one.** The API offers no read-only scope, so the token that reads a monitor can also delete it. It is therefore
used for exactly one `GET`, in a job that checks out no code. The probe says so in its job summary when the secret is
absent, rather than passing quietly.

The monitor's id is `4876693`, and `BETTERSTACK_MONITOR_ID` overrides it for a new environment.

**Proven on 2026-08-31, because a check nobody saw fire proves nothing.** The drill changed one expected value in the
workflow, from a 180-second check frequency to 300. The probe step stayed green and pinged as usual. The assertion step
failed with `Monitor 4876693: check_frequency is '180', this repository says '300'`. That is the split this design
wants: the site was up, and only the comparison failed. The change was then reverted.

### 2. The healthchecks.io check — the slow path

| Field        | Value                | Why exactly this                                                                                                  |
| ------------ | -------------------- | ----------------------------------------------------------------------------------------------------------------- |
| **Name**     | `site-<environment>` | The same name as the monitor above, on purpose. They watch the same thing                                         |
| **Schedule** | Simple               | As above                                                                                                          |
| **Period**   | `24h`                | **Not the workflow's cron.** It tracks what GitHub actually does, which is not what the cron asks for — see below |
| **Grace**    | `24h`                | 48 hours of tolerance against a 34-hour worst observed gap. **The load-bearing number** — see below               |
| **Channel**  | the shared one       | See above                                                                                                         |

Then add the ping URL as the **`HEALTHCHECKS_PING_URL`** repository secret. Nothing else activates it.

**Run `gh workflow run site-probe.yml` after you change either number.** The check then goes green within seconds,
rather than a day later. Do not wait for the schedule to tell you whether you typed the period correctly.

### The cron is a request, and GitHub refuses it

`site-probe.yml` asked for a run every 15 minutes. **It did not get one.** Measured first over 30 scheduled runs on
2026-08-30, and again over five days to 2026-08-31:

| Interval between scheduled runs | Value                       |
| ------------------------------- | --------------------------- |
| Asked for                       | 15 min                      |
| Shortest actual                 | 29 min                      |
| **Median actual**               | **129 min**                 |
| Longest actual                  | 678 min                     |
| Gaps longer than 30 min         | 37 of 38                    |
| Runs delivered                  | **39 of 480 requested, 8%** |

This page prescribed `15m`/`30m` before, on the reasoning that a grace of double the period absorbs one
missed run. **The reasoning is sound. The numbers were 4 to 20 times too small.** The check went live on
2026-08-30 and alarmed within hours. The site was healthy for all of that time: no pod restarts, the
node at 25% CPU, and ten `200` responses in sequence.

**The second measurement is the one that decided it.** A single bad day is an Actions incident. Five days is a policy.

### The daily schedules are honoured, and that is where `24h` comes from

The same repository, measured the same way:

| Workflow                         | Cron         | Median gap | Worst gap |
| -------------------------------- | ------------ | ---------- | --------- |
| `dependency-check-scheduled.yml` | `17 3 * * *` | 24.0 h     | 34.2 h    |
| `image-scan-scheduled.yml`       | `41 4 * * *` | 25.5 h     | 34.3 h    |

GitHub holds back the high-frequency cron and delivers the daily ones. So the probe now runs daily, and its check sits
at `24h`/`24h`. That is 48 hours of tolerance against a 34-hour worst case.

**Derive both numbers from this table, never from the cron line.** Reading the cron and doubling it is what produced
the flapping.

### The daily check is no longer the thing that reports an outage

A 48-hour tolerance is an alarm for a dead server, and it is not availability monitoring. That is why
[ADR-021](../adr/ADR-021_PUBLIC_SITE_MONITORING.md) put a Better Stack monitor in front of it. **A monitor with less
reliable liveness than the thing it watches teaches you to ignore it.** That was the real defect in the 15-minute
cron. The site was up for every one of the intervals that breached the old grace.

The daily check keeps its place for one reason. It runs on different infrastructure, on a different schedule, and it
alarms in a different direction. **It watches the site, and not the monitor.** Nothing detects Better Stack going quiet
except Better Stack. What the second path buys is independence of fate.

**None of this applies to the backup checks.** `walg-<environment>` pings from a systemd timer on a node
we control, for a job that pings on success. That is the shape healthchecks.io is for, and its `26h`/`2h`
reasoning stands. The site probe borrowed the shape for a job it does not fit.

## Wiring a node to its check

`HEALTHCHECK_URL` lives in `/etc/wal-g/credentials.env`, alongside the S3 key, at mode `0640` `root:postgres`. That file is written by hand and **is not in
`user_data`**, because `user_data` is state. [CLUSTER_BOOTSTRAP.md](CLUSTER_BOOTSTRAP.md) §8b has the whole reasoning and the rest of the file's contents.

```sh
# Appends without printing the file. `tee -a` preserves the existing mode and group.
ssh -i ~/.ssh/id_ed25519_hetzner ops@<tunnel-address> \
  "printf 'HEALTHCHECK_URL=%s\n' 'https://hc-ping.com/<uuid>' | sudo tee -a /etc/wal-g/credentials.env >/dev/null"
```

Then confirm three things, none of which prints a secret:

```sh
ssh -i ~/.ssh/id_ed25519_hetzner ops@<tunnel-address> \
  'sudo grep -c "^HEALTHCHECK_URL=" /etc/wal-g/credentials.env;  # expect exactly 1, not 2
   sudo stat -c "%a %U:%G" /etc/wal-g/credentials.env;           # expect 640 root:postgres
   sudo -u postgres /usr/local/bin/walg check'                    # expect: ok: newest <ts>, disk NN%
```

**Exactly one line matters.** Appending a second `HEALTHCHECK_URL=` does not fail. The file is sourced, so the last assignment silently wins, and a stale first
line looks entirely correct in a `grep`. The count is the cheap way to notice.

The final command pings immediately on success, so the check goes green within seconds rather than at the top of the next hour. Do not wait for the timer to
tell you whether you typed the URL correctly.

### If you have not wired it, `walg check` says so

```
warning: HEALTHCHECK_URL is unset in /etc/wal-g/credentials.env — this check passes into a void.
         Nothing off this host learns that the backups are healthy, and nothing
         learns when they stop. See CLUSTER_BOOTSTRAP.md §8b (#518).
```

That line exists because the two states are otherwise **indistinguishable from outside**. A dead-man's switch that is not wired up reports exactly what a
healthy one does, which is nothing. A node printing that warning is not monitored, however green its timers look.

## Proving it fires

**An alert nobody ever saw fire is the same class of belief as an untested backup**, which is the argument this whole page rests on. Induce the failure rather
than trusting the wiring.

The disk assertion is the cheapest to induce: it needs no backup deleted and it undoes itself.

```sh
ssh -i ~/.ssh/id_ed25519_hetzner ops@<tunnel-address> \
  'sudo fallocate -l $(( $(df --output=avail -B1 /var/lib/postgresql | tail -1) * 90 / 100 )) /var/lib/postgresql/ZZ-drill && \
   sudo -u postgres walg check; sudo rm -f /var/lib/postgresql/ZZ-drill'
# expect: "/var/lib/postgresql is NN% full", exit 1, and NO ping — the check goes late, then red
```

Three things that catch people out:

- **The notification arrives after the grace period, not immediately.** That is what a dead-man's switch _is_, and it is the part most likely to be mistaken for
  the alert not working. Shorten the grace to a few minutes for the drill and put it back afterwards.
- **Delete the drill file.** The command above removes it, but confirm — `sudo test -e /var/lib/postgresql/ZZ-drill`. Leaving a file sized at 90% of the volume
  turns a drill into the outage it was rehearsing. The next `walg-basebackup` is what would find out.
- **Record the date the notification actually arrived**, in the go-live checklist (#284). That date, not the configuration, is what makes this real.

### Drill log

| Date       | Environment | Induced          | Notification arrived            |
| ---------- | ----------- | ---------------- | ------------------------------- |
| 2026-08-19 | staging     | disk past 85%    | yes                             |
| 2026-08-21 | production  | explicit `/fail` | yes — within seconds, 06:06 UTC |

**The two rows induced different things, and the second is the weaker drill.** The disk assertion makes the check go _silent_, so the notification comes from
healthchecks.io noticing an absence after the grace period. That is what a dead-man's switch actually is, and what a real backup failure looks like. An
explicit `/fail` is a signal we send, so it alerts immediately and never exercises the timeout at all.

`/fail` still proves the half that is easiest to get wrong: that a check exists, is wired to a channel, and reaches a human. It does not prove the grace period
is survivable or that silence is noticed. **Production's timeout path is therefore still unrehearsed**, and the disk drill above is how to close that.

Production's sequence, for the record. It was fired from the database node itself rather than a laptop, so it also proves that node's egress reaches
`hc-ping.com`. That matters, because the node has no public inbound at all:

```
06:04:39   base backup written
06:06:46   curl "$HEALTHCHECK_URL/fail"  ->  HTTP 200 OK        alert delivered
06:07:19   systemctl start walg-check.service  ->  ok: newest 2026-08-21T06:04:39Z, disk 2%
```

**The recovery was deliberately run through `walg-check.service`, not a bare `curl`** — the same path the hourly timer uses. A clear that only works when a
human types it is not a clear. The point of the drill is that the mechanism recovers on its own.

No request body was sent, so the §Privacy assessment below is unaffected.

## Availability, as a number

An alert tells you about the outage you are having. A number tells you whether the platform is getting better or worse
([#271](https://github.com/enorm-labs/event-junkie/issues/271)). **The window is a rolling 30 days, and a figure per
calendar month.** The rolling number is the operational one. The monthly one is the trend, and it is the reason the
history has to outlive any one tool's own.

### The Better Stack monitor is the source, and the daily probe is not

A check that runs once a day cannot measure availability. It samples 1 point where the monitor samples 480. So the
daily probe answers "is it dead" and never "how much of the month was it up".

**The monitor produces the figure directly.** It polls every three minutes, records each outcome, and reports uptime
per period in its own console. So the figure stopped waiting on anything inside the cluster.

### healthchecks.io could never have been the record

The free plan keeps **100 log entries per check**. That covered about 25 hours when the probe pinged every 15 minutes,
and it covers about 100 days now that it pings daily. Neither is a monthly figure. The check records that a ping
arrived, and not what the site was doing between pings.

### What is still open

| Part                                  | Waits on                                                        |
| ------------------------------------- | --------------------------------------------------------------- |
| The figure existing at all            | **Nothing. Create the monitor**                                 |
| Retention past the free plan's window | Confirm the plan's history window in the console, and see below |

**The free plan's uptime-history window is not documented, and the number in circulation is the wrong one.** Better
Stack's pricing page states 3-day retention for **logs, traces and web events**, which are the Telemetry product.
It states nothing for monitor and incident history. Comparison articles repeat the 3-day figure as though it covered
uptime, and that is a conflation rather than a source.

**Settle it by measurement on 2026-09-04, not by reading.** The monitor started on 2026-08-31, so four days later this
either still reports that first day or it does not:

```sh
curl -sS -H "Authorization: Bearer $TOKEN" \
  "https://uptime.betterstack.com/api/v2/monitors/4876693/sla?from=2026-08-31&to=2026-08-31"
```

Record the answer here. If the window is shorter than a rolling 30 days, the long-term store is OpenObserve. OpenObserve already retains metrics in the Object
Storage bucket under a retention policy ([ADR-015](../adr/ADR-015_OBSERVABILITY_STACK.md)). That path needs
OpenObserve on production, which does not run there at all
([#880](https://github.com/enorm-labs/event-junkie/issues/880)).

**#880 therefore changed shape.** It used to block the figure. It now only blocks keeping the figure for longer than
the monitor keeps it.

**This does not re-open the LEGAL.md §14 assessment**, and §14 records the reasoning for both services. The
healthchecks.io ping stays a bare `GET` to an opaque UUID with no body. The monitor fetches a public page and receives
no personal data. Sending a number to either as a payload **would** re-open it.

### It is measured, not published

Availability is for operators. There is no public status page and no uptime badge, and that is a
decision rather than an omission. Publishing a number is a transparency commitment to venues and
visitors, and it is worth making deliberately rather than discovering it when somebody asks.

**Revisit at launch** (#285), and the go-live checklist carries the row. A figure nobody reads yet is a poor basis for
a public promise.

**Better Stack makes publishing a one-line change, which is the reason to decide it on purpose.** The monitor offers a
README badge that renders live uptime:

```markdown
[![Better Stack Badge](https://uptime.betterstack.com/status-badges/v3/monitor/2wivp.svg)](https://uptime.betterstack.com/?utm_source=status_badge)
```

Pasting that into a public README publishes the number. **It is a commitment to venues and visitors, not a
decoration**, and it also tells every reader which vendor watches the site. Decide it at launch, and record the
decision either way.

## The two ways this quietly stops working

- **A node rebuild.** `/etc/wal-g/credentials.env` dies with the disk, exactly like the S3 key. A rebuilt node comes back with both timers enabled, wal-g
  installed, `archive_mode = on`, every archive failing, and no ping. Nothing about the node looks wrong. **Paste the same URL back rather than creating a
  new check**, or the history that makes "late" mean something starts over. CLUSTER_BOOTSTRAP.md's rebuild checklist carries this.
- **A new environment with no check.** Provisioning a node does not create one, and the node will happily run un-monitored. Adding an environment means adding a
  check. The warning above is what makes that visible on the node itself.

## Privacy

**The ping is a bare HTTPS `GET` to an opaque random UUID**, with no body and no query string. It carries no personal data, no database contents and nothing
identifying a visitor. What it reveals to healthchecks.io is the **server's** public IP and the timing of the pings. That is an address of ours, not of a data subject.

[LEGAL.md](../LEGAL.md) §14 therefore records that this is **not** an Art. 28 processor relationship. It needs no DPA and no entry in the privacy notice.
**Re-open that assessment the moment a ping gains a body.** The `/fail` and `/log` endpoints accept one, and a payload is where the
reasoning stops holding.
