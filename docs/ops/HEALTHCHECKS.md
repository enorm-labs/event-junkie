# Dead-man's switches on healthchecks.io

How the external alerting works, how to add a check when an environment appears, and how to prove one actually fires.

> **Every ping URL on this page is a credential.** Anyone holding one can ping the check and suppress its alarm — which is worse than a leaked read-only token,
> because the failure it causes is _silence_. They live in `/etc/wal-g/credentials.env` on the node and nowhere else: not in this repository, not in an issue,
> not in a pull request, not in a values file. There is deliberately no table of URLs below.

## Why an external service at all

Everything else this project monitors runs inside the cluster it is monitoring. That is fine for a metric and useless for an outage: **a dead-man's switch on
infrastructure cannot report that infrastructure's death.** A pod that is meant to alert when the node stops is a pod that stops when the node stops.

So the arrangement is inverted. The node **pings out on success**, and the absence of a ping is what raises the alarm — which means the alerting works precisely
when the thing it watches does not. healthchecks.io is deliberately off Hetzner for the same reason
([#271](https://github.com/enorm-labs/event-junkie/issues/271), [#518](https://github.com/enorm-labs/event-junkie/issues/518)).

**One account, one project, one notification channel** for all of these. A second notification stack is the avoidable mistake: it is one more thing to configure
correctly, and its own failure is silent by construction.

## What is watched

| Check                | Watches                        | Pinged by                                               | Issue |
| -------------------- | ------------------------------ | ------------------------------------------------------- | ----- |
| `walg-<environment>` | PostgreSQL backups are healthy | `walg check`, hourly via `walg-check.timer`, on success | #518  |

Planned, not built: a site probe proving DNS, TLS, the ingress and the application answer from outside (#271). It belongs on this account and this channel.

### What the backup check actually asserts

`walg check` is deliberately **not** "did the last job error". It asserts three things and exits non-zero if any is false
([BACKUPS.md](BACKUPS.md) §6):

1. there is a base backup at all;
2. the newest is younger than **26 hours**;
3. `/var/lib/postgresql` is below **85%** — a stalled `archive_command` does not stop backups, it fills `pg_wal`, and the volume is 10 GB.

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

**26h is configured, and it is the conservative reading.** It comes from what `walg check` asserts about _base-backup age_ — the argument being that a shorter
period would alert on a state the check itself considers fine.

The other reading is that those are two different clocks. The check pings **hourly**, and the period only governs how long _silence_ is tolerated — and silence
means "the check did not run, or did not pass", which is worth knowing regardless of what it would have said. On the configured values, a node that wedges or
starts failing its assertions is reported after **26h + 2h ≈ 28 hours**. At `1h`/`2h` it would be about three, and the grace still absorbs a reboot.

Neither is wrong. **28 hours is well inside the window that matters for a backup** — the thing being protected is a restore that has not been needed yet, not a
serving path — and a longer period is the one that cannot produce a false alarm. Change it deliberately, and change it in both places: here and the check.

## Wiring a node to its check

`HEALTHCHECK_URL` lives in `/etc/wal-g/credentials.env`, alongside the S3 key, at mode `0640` `root:postgres`. That file is written by hand and **is not in
`user_data`**, because `user_data` is state — see [CLUSTER_BOOTSTRAP.md](CLUSTER_BOOTSTRAP.md) §8b for the whole reasoning and the rest of the file's contents.

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

**Exactly one line matters.** Appending a second `HEALTHCHECK_URL=` does not fail — the file is sourced, so the last assignment silently wins, and a stale first
line looks entirely correct in a `grep`. The count is the cheap way to notice.

The final command pings immediately on success, so the check goes green within seconds rather than at the top of the next hour. Do not wait for the timer to
tell you whether you typed the URL correctly.

### If you have not wired it, `walg check` says so

```
warning: HEALTHCHECK_URL is unset in /etc/wal-g/credentials.env — this check passes into a void.
         Nothing off this host learns that the backups are healthy, and nothing
         learns when they stop. See CLUSTER_BOOTSTRAP.md §8b (#518).
```

That line exists because the two states are otherwise **indistinguishable from outside**: a dead-man's switch that is not wired up reports exactly what a
healthy one does — nothing. A node printing that warning is not monitored, however green its timers look.

## Proving it fires

**An alert nobody has seen fire is the same class of belief as an untested backup**, which is the argument this whole page rests on. Induce the failure rather
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
  turns a drill into the outage it was rehearsing, and the next `walg-basebackup` is what would find out.
- **Record the date the notification actually arrived**, in the go-live checklist (#284). That date, not the configuration, is what makes this real.

### Drill log

| Date       | Environment | Induced       | Notification arrived |
| ---------- | ----------- | ------------- | -------------------- |
| 2026-08-19 | staging     | disk past 85% | yes                  |

## The two ways this quietly stops working

- **A node rebuild.** `/etc/wal-g/credentials.env` dies with the disk, exactly like the S3 key, so a rebuilt node comes back with both timers enabled, wal-g
  installed, `archive_mode = on`, and every archive failing — and no ping. Nothing about the node looks wrong. **Paste the same URL back rather than creating a
  new check**, or the history that makes "late" mean something starts over. CLUSTER_BOOTSTRAP.md's rebuild checklist carries this.
- **A new environment with no check.** Provisioning a node does not create one, and the node will happily run un-monitored. Adding an environment means adding a
  check; the warning above is what makes that visible on the node itself.

## Privacy

**The ping is a bare HTTPS `GET` to an opaque random UUID**, with no body and no query string. It carries no personal data, no database contents and nothing
identifying a visitor. What it reveals to healthchecks.io is the **server's** public IP and the timing of the pings — an address of ours, not of a data subject.

The assessment recorded in [LEGAL.md](../LEGAL.md) §14 is therefore that this is **not** an Art. 28 processor relationship and needs no DPA and no entry in the
privacy notice. **Re-open that assessment the moment a ping gains a body**, because healthchecks.io's `/fail` and `/log` endpoints accept one, and a payload is
where the reasoning stops holding.
