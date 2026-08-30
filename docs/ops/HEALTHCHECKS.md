# Dead-man's switches on healthchecks.io

How the external alerting works, how to add a check when an environment appears, and how to prove one actually fires.

## The short version

```sh
sudo -u postgres /usr/local/bin/walg check     # on the node: pings on success, exits 1 on any failure
sudo grep -c '^HEALTHCHECK_URL=' /etc/wal-g/credentials.env   # expect exactly 1, never 2
```

- **Two checks per environment**, `walg-<environment>` and `site-<environment>`. One account, one project, one channel.
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

## What is watched

| Check                | Watches                                                 | Pinged by                                                             | Issue |
| -------------------- | ------------------------------------------------------- | --------------------------------------------------------------------- | ----- |
| `walg-<environment>` | PostgreSQL backups are healthy                          | `walg check`, hourly via `walg-check.timer`, on success               | #518  |
| `site-<environment>` | DNS, TLS, the ingress and the application, from outside | `.github/workflows/site-probe.yml`, every 15 minutes, on success only | #271  |

`site-staging` is not a check anybody should create. Staging has no public `A` record and no public
80/443, so nothing outside can probe it. `site-production` is the only one of that row that can
exist before go-live.

**The site probe is built and deliberately dormant.** It skips, and says so in its job summary, while
`HEALTHCHECKS_PING_URL` is unset. That is the state today, because there is nothing public to probe.
Staging is not on the internet by design ([PLATFORM_SETUP](PLATFORM_SETUP.md) §4a), and production is
[#285](https://github.com/enorm-labs/event-junkie/issues/285). Setting the secret is the whole
activation, and the workflow needs no change.

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

## Creating a check for the site probe

| Field        | Value                | Why exactly this                                                                                                                                        |
| ------------ | -------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Name**     | `site-<environment>` | Same per-environment rule as the backup check, and for the same reason                                                                                  |
| **Schedule** | Simple               | As above                                                                                                                                                |
| **Period**   | `15m`                | Matches the workflow's cron. The probe is cheap and the thing it watches is the serving path, so latency matters here in a way it does not for a backup |
| **Grace**    | `30m` or more        | **The load-bearing number** — see below                                                                                                                 |
| **Channel**  | the shared one       | See above                                                                                                                                               |

Then add the ping URL as the **`HEALTHCHECKS_PING_URL`** repository secret. Nothing else activates it.

### Why the grace is double the period, and must not be tightened to match it

**GitHub's scheduler is not punctual, and it skips runs outright.** During the 2026-08-06 Actions
incident it dropped roughly 85% of webhooks. Scheduled workflows are also delayed under load as a matter
of routine, not of incident. A grace equal to the period would therefore alarm on GitHub's timekeeping
rather than on the site.

At `15m`/`30m` a single missed run is absorbed and two are not. A genuine outage is therefore reported
within about 45 minutes, and a late run is not reported at all. **That asymmetry is the whole point.**
This check exists to catch the case where nothing else can report. A channel that cries wolf about its
own scheduler gets muted long before the outage it was for.

Contrast the backup check's `26h`/`2h`, where the period tracks an assertion the check itself makes about
data age. Here the period tracks a cron and the grace absorbs the platform. Different reasoning, and
worth not copying one onto the other.

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

The site probe is already an availability measurement, not only an alert. It resolves the hostname,
fetches the site over the internet, and asserts the status and the body. A record of those outcomes
answers "how much of the month was the site up". That is a different question from "is it down now". An alert tells you about the outage you are having. A number tells you whether the
platform is getting better or worse (#271).

**The window is a rolling 30 days, and a figure per calendar month.** The rolling number is the
operational one. The monthly one is the trend, and it is the reason the history has to outlive the
probe's own.

### healthchecks.io keeps about a day of it, so it cannot be the record

The free plan keeps **100 log entries per check**. The site probe pings every 15 minutes, which is 96
pings a day. So its history covers about **25 hours**. That is enough to see the outage you are in and
far too little for a monthly figure.

The long-term store is therefore OpenObserve, which already retains metrics in the Object Storage
bucket under a retention policy (ADR-015). The probe writes its outcome there as a metric, beside the
rest.

**Neither half is live yet, and each waits on something different:**

| Part                     | Waits on                                                                           |
| ------------------------ | ---------------------------------------------------------------------------------- |
| The probe running at all | `HEALTHCHECKS_PING_URL`, which nobody has set. **Nothing else blocks it any more** |
| Writing the metric       | OpenObserve on production, which does not run there at all (#880)                  |

Until both are true there is no figure, and this section describes the design rather than a thing you
can read today.

**The first row stopped being blocked on 2026-08-30.** It used to wait on a public site. Production
now serves `prod-check.event-junkie.de` over a real certificate, and the `SITE_URL` repository
variable points the workflow at that name. So the probe is one secret away from running, and the
section below is how to create the check that produces it.

**This does not re-open the LEGAL.md §14 assessment.** That assessment holds because the ping to
healthchecks.io carries no body. The metric goes to OpenObserve, which is ours, so the ping stays a
bare `GET` to an opaque UUID. Sending the number to healthchecks.io instead **would** re-open it.

### It is measured, not published

Availability is for operators. There is no public status page and no uptime badge, and that is a
decision rather than an omission. Publishing a number is a transparency commitment to venues and
visitors, and it is worth making deliberately rather than discovering it when somebody asks.

**Revisit at launch** (#285). A figure nobody reads yet is a poor basis for a public promise.

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
