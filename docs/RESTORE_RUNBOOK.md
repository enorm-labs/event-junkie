# Restoring the database

**Read this top to bottom. Do not skip to the commands.** The design and the reasoning are [BACKUPS.md](BACKUPS.md); this is the procedure, and it is written on
the assumption that you are reading it because something has already gone wrong.

**First run of the drill against this document: 2026-08-18, staging — passed.** Sections 4 and 5 have been executed against a real cluster. **Section 6 has
not** — it says so where it matters.

## Stop. Three things before you touch anything

1. **Do not restore in place first.** Almost every situation is better served by restoring into a **scratch cluster on port 5433** and looking, which costs
   nothing and risks nothing. The live database is evidence until you decide otherwise. §4.
2. **Do not stop the live cluster to "keep things from getting worse".** A running PostgreSQL keeps archiving WAL, and that WAL is what shortens the window you
   can recover to. Stopping it freezes the damage _and_ the recovery point.
3. **Write down the time now**, and the time you first noticed. Point-in-time recovery is only as good as your estimate of when things were still right, and
   that estimate degrades fast once you start working.

> **If the volume is full**, none of the above applies and you have a different emergency: PostgreSQL will stop accepting writes and archiving will already have
> failed. Go to §8, first row.

## 1. Which restore do you need

| What happened                                                                      | Go to                                                         | Live database      |
| ---------------------------------------------------------------------------------- | ------------------------------------------------------------- | ------------------ |
| "Did this row ever look different?" — you need to _inspect_ the past               | §4                                                            | untouched          |
| A table or some rows were deleted or mangled, and the rest of the database is fine | §4, then copy the data back                                   | untouched          |
| A bad migration or a bulk operation corrupted a lot, and you know roughly when     | §5                                                            | untouched until §6 |
| The database is gone, corrupt, or the volume is lost                               | §6                                                            | replaced           |
| The node is gone but the volume is intact                                          | **Not a restore.** CLUSTER_BOOTSTRAP.md § _Rebuilding a node_ | adopted            |
| `tofu destroy` took the environment                                                | §6, after re-applying the infrastructure                      | rebuilt            |

**The middle two are the common ones**, and both start the same way: restore beside the live database, then decide.

## 2. Get in

```sh
sudo wg-quick up ~/.wireguard/staging.conf      # nothing is reachable without the tunnel
ssh -i ~/.ssh/id_ed25519_hetzner ops@10.10.1.1
```

Full detail, including what to do when the handshake does not happen, is [CLUSTER_ACCESS.md](CLUSTER_ACCESS.md).

## 3. Confirm there is something to restore from

**Do this before planning anything.** If the answer here is bad, it changes what the next hour looks like.

```sh
sudo -u postgres /usr/local/bin/walg check
# ok: newest 2026-08-18T09:58:28Z, disk 2%

sudo -u postgres bash -c 'set -a; . /etc/wal-g/wal-g.env; . /etc/wal-g/credentials.env; set +a
  wal-g backup-list --detail'

sudo -u postgres psql -x -c 'select archived_count, failed_count, last_archived_wal from pg_stat_archiver'
```

Three things to read off, in this order:

- **`walg check` fails with "no base backup at all"** → there is nothing to restore. Most likely `/etc/wal-g/credentials.env` is missing after a rebuild
  (BACKUPS.md §5). Nothing below will work until §8b of CLUSTER_BOOTSTRAP.md has been done.
- **`failed_count` is non-zero and rising** → archiving is broken _now_. Your recovery point is wherever it broke, not the present. Fix it before you plan a
  point-in-time target, or you will aim at a time no WAL exists for.
- **The newest backup's `finish_time`** → everything after this comes from WAL replay. If WAL archiving has been broken since before that, the newest backup is
  all you have.

## 4. Restore beside the live database — the safe default

This is the one to reach for unless §1 sent you to §6. It never touches live `PGDATA` and never reads the volume — only the bucket.

### 4.1 Fetch

```sh
sudo rm -rf /var/lib/postgresql/drill
sudo -u postgres bash -c 'set -a; . /etc/wal-g/wal-g.env; . /etc/wal-g/credentials.env; set +a
  time wal-g backup-fetch /var/lib/postgresql/drill LATEST'
```

`LATEST` takes the newest base backup. To start from an older one, name it from `backup-list` instead — you want that when the newest backup already contains
the damage.

> **Check the free space first if the database is large.** The scratch copy lands on the same 10 GB volume as the live one: `df -h /var/lib/postgresql`.

### 4.2 Give it a configuration of its own

**A base backup contains no configuration.** Debian keeps `postgresql.conf` in `/etc/postgresql/18/main`, outside `PGDATA`, so the restored directory has none
and `pg_ctl` fails with `could not access the server configuration file`.

**Do not solve that by pointing at the live cluster's config.** It carries `data_directory`, `external_pid_file`, and — the one that matters —
**`archive_mode = on` with the same `WALG_S3_PREFIX`**, so the scratch cluster would start pushing WAL into the very prefix it is restoring from.

Install this once as `/var/lib/postgresql/drill-start.sh`, owned by `postgres`, mode 0755. The optional argument is what makes §5 the same script as this one:

```sh
#!/bin/bash
set -euo pipefail
D=/var/lib/postgresql/drill
TARGET="${1:-}"

cat > "$D/postgresql.conf" <<CONF
data_directory = '$D'
hba_file = '$D/pg_hba.conf'
ident_file = '$D/pg_ident.conf'
port = 5433
listen_addresses = 'localhost'
unix_socket_directories = '/tmp'
archive_mode = off
restore_command = '/usr/local/bin/walg fetch %f %p'
CONF

if [[ -n "$TARGET" ]]; then
    cat >> "$D/postgresql.conf" <<CONF
recovery_target_time = '$TARGET'
recovery_target_action = 'promote'
CONF
fi

printf 'local all all trust\nhost all all 127.0.0.1/32 trust\n' > "$D/pg_hba.conf"
: > "$D/pg_ident.conf"
: > "$D/postgresql.auto.conf"   # read last, so a restored copy would override everything above
touch "$D/recovery.signal"
chmod 0700 "$D"

/usr/lib/postgresql/18/bin/pg_ctl -D "$D" -l /tmp/drill.log -w -t 120 start
```

### 4.3 Start it and look

```sh
sudo -u postgres /var/lib/postgresql/drill-start.sh

sudo -u postgres psql -h /tmp -p 5433 -d events \
  -c 'select (select count(*) from events.event) ev, (select count(*) from events.artist) ar, (select count(*) from events.venue) ve'
```

The tables are `events.event` and `events.artist` — **singular, in the `events` schema** ([ADR-004](adr/ADR-004_DEDICATED_DATABASE_SCHEMA.md)), not `public` and
not plural.

`ERROR: Archive '00000001.history' does not exist` in `/tmp/drill.log` is **normal noise**, not a failure — a timeline that has never failed over has no history
file, and `archive recovery complete` follows it.

### 4.4 Copy what you need back

For a lost table or a bounded set of rows, this is the whole recovery, and it is far less risky than §6:

```sh
sudo -u postgres pg_dump -h /tmp -p 5433 -d events -t events.<table> --data-only > /tmp/recovered.sql
# read it before loading it
sudo -u postgres psql -d events -f /tmp/recovered.sql
```

**Think about foreign keys and about what has changed since.** Restoring rows into a live database that has moved on can violate constraints or resurrect
records something else has legitimately deleted. `--data-only` into a staging copy first, if there is any doubt.

Then go to §7.

## 5. Point-in-time recovery

Same as §4, with a target. This is the answer to a bad migration, which is the disaster this system is most likely to actually have.

**Pick the target with care.** It must be a moment when things were still right, and it applies to the _whole database_ — everything committed after it is gone
from the restored copy. Err earlier: you can always replay further forward by restoring again with a later target, and you cannot un-lose a transaction.

```sh
TARGET='2026-08-18 10:00:34.114865+00'      # UTC, and before the damage

sudo rm -rf /var/lib/postgresql/drill
sudo -u postgres bash -c 'set -a; . /etc/wal-g/wal-g.env; . /etc/wal-g/credentials.env; set +a
  wal-g backup-fetch /var/lib/postgresql/drill LATEST'
sudo -u postgres /var/lib/postgresql/drill-start.sh "$TARGET"
```

Confirm it stopped where you meant:

```sh
sudo grep -E 'recovery stopping|last completed transaction' /tmp/drill.log
# recovery stopping before commit of transaction 1914, time 2026-08-18 10:00:34.165545+00
```

**If it says `recovery stopping` at a time later than your target**, the target fell inside a transaction and PostgreSQL stopped at the next boundary. That is
correct behaviour. **If the cluster is still in recovery** (`select pg_is_in_recovery()` returns `t`), `recovery_target_action = 'promote'` did not fire —
usually because the target is beyond the end of the archived WAL.

Then either copy data back (§4.4) or promote this copy to be the real database (§6).

## 6. Replacing the live database — the one that is actually dangerous

> **This has never been performed, on any environment.** §4 and §5 are rehearsed quarterly; this is not, because rehearsing it means destroying a working
> database. Treat every step as unverified, read it through before starting, and prefer §4.4 whenever the damage is bounded enough to copy back.

**Take a copy of the broken database first.** It is evidence, it is the only thing that can tell you what happened, and once it is gone the question of _why_
usually goes with it.

```sh
sudo -u postgres pg_dump -d events -Fc -f /var/lib/postgresql/broken-$(date -u +%Y%m%dT%H%M%SZ).dump
# if it will not dump, at least: sudo systemctl stop postgresql@18-main && sudo cp -a /var/lib/postgresql/18/main /var/lib/postgresql/18/main.broken
```

Then, in order:

```sh
# 1. Stop everything that writes. The applications first, or they will reconnect mid-restore.
kubectl --context event-junkie-staging -n event-junkie scale deploy --all --replicas=0

# 2. Stop PostgreSQL and move the old cluster aside — move, do not delete.
sudo systemctl stop postgresql@18-main
sudo mv /var/lib/postgresql/18/main /var/lib/postgresql/18/main.broken

# 3. Restore into place, with the target if this is a PITR.
sudo -u postgres bash -c 'set -a; . /etc/wal-g/wal-g.env; . /etc/wal-g/credentials.env; set +a
  wal-g backup-fetch /var/lib/postgresql/18/main LATEST'

# 4. Recovery settings go in postgresql.auto.conf — the real cluster keeps Debian's config in /etc.
sudo -u postgres tee /var/lib/postgresql/18/main/postgresql.auto.conf >/dev/null <<'CONF'
restore_command = '/usr/local/bin/walg fetch %f %p'
recovery_target_action = 'promote'
CONF
# for a PITR, add: recovery_target_time = '<target>'
sudo -u postgres touch /var/lib/postgresql/18/main/recovery.signal

# 5. Start, and watch it come up before believing it.
sudo systemctl start postgresql@18-main
sudo journalctl -u postgresql@18-main -n 40 --no-pager
sudo -u postgres psql -tAc 'select pg_is_in_recovery()'      # must be f
```

**Four things that will bite here, and the third is the one that surprises people:**

- **`archive_mode` is still on**, and once promoted this cluster is on a **new timeline**. That is correct and wanted — it means the new history is archived too
  — but the bucket now holds two timelines, and a future restore has to know which one it wants. Note the timeline in your incident record.
- **Take a fresh base backup immediately after promoting** (§7). Until you do, recovery depends on replaying across a timeline switch, which is a harder thing
  to be confident about at 03:00.
- **The `events` role's password comes back as it was when the backup was taken.** If it has been rotated since, the `events-db` Secret in the cluster no longer
  matches and every application will fail authentication — reporting it, unhelpfully, the same way it reports a missing role. Fix with
  `ALTER ROLE events PASSWORD …` to match the Secret, or re-create both. CLUSTER_BOOTSTRAP.md §8.
- **`main.broken` is on the same 10 GB volume.** It will not fit twice for long. Keep it until the incident is understood, then remove it deliberately — and
  check `df` before you start, not after.

## 7. Afterwards — none of this is optional

```sh
# 1. Bring the applications back and confirm they can actually connect.
kubectl --context event-junkie-staging -n event-junkie scale deploy --all --replicas=1
kubectl --context event-junkie-staging -n event-junkie logs -l app.kubernetes.io/name=events-bff --tail=20

# 2. Prove archiving resumed. This is the step people skip.
sudo -u postgres psql -x -c 'select archived_count, failed_count, last_archived_wal from pg_stat_archiver'

# 3. A fresh base backup, so the next restore does not depend on a timeline switch.
sudo systemctl start walg-basebackup
sudo -u postgres /usr/local/bin/walg check

# 4. Clean up any scratch cluster — see the trap below before deleting anything.
sudo -u postgres /usr/lib/postgresql/18/bin/pg_ctl -D /var/lib/postgresql/drill -w stop
sudo ss -lnt | grep -q ':5433' && echo 'STILL BOUND - do not delete the directory' || echo free
sudo rm -rf /var/lib/postgresql/drill /tmp/drill.log
```

**Then write down what happened**: what was lost, what the recovery point ended up being, how long it took, and which step of this document was wrong. The last
one is the most valuable — three errors in this procedure were found the first time it was run, and they were only found by running it.

## 8. Traps, in the order they bite

|                                                             |                                                                                                                                                                                                                                                                                                                                                                                                                           |
| ----------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **The volume is full and the database has stopped**         | A failing `archive_command` accumulates WAL; `PGDATA` is 10 GB. Fix the archive first — usually the credential — then let the backlog drain. **Never delete from `pg_wal` by hand**: removing an unarchived segment is how a recoverable incident becomes an unrecoverable one. `sudo -u postgres psql -c 'select * from pg_stat_archiver'` names the failing segment                                                     |
| **`walg check`: "no base backup at all"**                   | `/etc/wal-g/credentials.env` is missing — it is not in `user_data` by design, so a node rebuild destroys it. CLUSTER_BOOTSTRAP.md §8b                                                                                                                                                                                                                                                                                     |
| **`pg_ctl: command not found`**                             | It is not on `postgres`'s `PATH`. Use `/usr/lib/postgresql/18/bin/pg_ctl`. This fails in the worst way: if the stop goes unchecked and you `rm -rf` anyway, the postmaster keeps running with no data directory and keeps port 5433, so the **next** restore fails with `Address already in use` — an error saying nothing about the cause. Recover with `kill -TERM $(pgrep -f 'postgres -D /var/lib/postgresql/drill')` |
| **`could not access the server configuration file`**        | The base backup has no config; Debian keeps it in `/etc`. §4.2 — and do not point it at the live config                                                                                                                                                                                                                                                                                                                   |
| **`Address already in use` on 5433**                        | A previous scratch cluster is still running, probably with its data directory already deleted. See above                                                                                                                                                                                                                                                                                                                  |
| **A restore lists fine and will not replay**                | A base backup was removed while a later delta still needed it — `wal-g delete before` without `FIND_FULL`, or a bucket lifecycle rule expiring at exactly the retention window instead of five days past it. BACKUPS.md §4                                                                                                                                                                                                |
| **`ERROR: Archive '00000001.history' does not exist`**      | Benign. A timeline that never failed over has no history file                                                                                                                                                                                                                                                                                                                                                             |
| **Everything authenticates as failed after a full restore** | The role's password came back as it was at backup time. PostgreSQL reports a wrong password and a missing role identically. §6                                                                                                                                                                                                                                                                                            |
| **`recovery stopping` later than the target**               | The target fell inside a transaction; PostgreSQL stops at the next boundary. Correct behaviour                                                                                                                                                                                                                                                                                                                            |
| **The tunnel stops working mid-incident**                   | If the node was replaced, its WireGuard server key changed. Update `PublicKey =`. CLUSTER_BOOTSTRAP.md § _Rebuilding a node_                                                                                                                                                                                                                                                                                              |

---

**See also** — [BACKUPS.md](BACKUPS.md) · [CLUSTER_ACCESS.md](CLUSTER_ACCESS.md) · [CLUSTER_BOOTSTRAP.md](CLUSTER_BOOTSTRAP.md)
