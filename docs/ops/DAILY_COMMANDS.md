# Daily commands

The things you actually type, in one place. **Every one of these is explained somewhere else** — this page is the index, not the reasoning, and it links to the
reasoning rather than restating it.

Aliases for all of it: `scripts/shell-aliases.sh`, see §Aliases.

**Two environments, and the difference matters more than the commands do.** Staging is not on the public internet at all; production is running but **dark** —
the domain resolves to nothing until `publish_dns` is flipped. Both are tunnel-only for anything administrative.

## Get in

```sh
sudo wg-quick up ~/.wireguard/staging.conf        # staging   -> 10.10.1.1
sudo wg-quick up ~/.wireguard/production.conf     # production -> 10.10.0.1
sudo wg show                                       # look for "latest handshake"
```

Both can be up at once — separate keypairs on separate subnets (`10.10.1.x` and `10.10.0.x`), which is why the ranges were chosen that way.

**`latest handshake` is the only line that proves it worked.** The interface appears and routes are added either way. No handshake usually means outbound
UDP/51820 is blocked, not that the node is broken — [CLUSTER_ACCESS.md](CLUSTER_ACCESS.md) §1.

```sh
sudo wg-quick down ~/.wireguard/staging.conf
```

## Cluster

Both contexts are in `~/.kube/config`; **staging is the default**, deliberately.

```sh
kubectl config get-contexts
kubectl config use-context event-junkie-staging

kubectl --context event-junkie-staging get pods -A
kubectl --context event-junkie-production get pods -A

k9s --context event-junkie-staging
```

> **Pass `--context` explicitly for anything that writes.** Both clusters are one `use-context` away, and [`deploy/AGENTS.md`](../../deploy/AGENTS.md) forbids
> `helm install/upgrade/uninstall` and Flux commands against anything but `k3d-*` unless asked. Merging the kubeconfigs made that rule rest entirely on
> discipline.

## Flux

```sh
flux --context event-junkie-staging get all -A
flux --context event-junkie-staging get all -A --status-selector ready=false

flux --context event-junkie-staging reconcile kustomization flux-system --with-source
flux --context event-junkie-staging reconcile helmrelease event-junkie -n flux-system --with-source
```

**`--status-selector ready=false` misses `Unknown`**, which is what an in-progress install reports. A release still installing looks identical to a healthy one
through that filter — check the full listing when something has just changed.

## The site

```sh
curl -I -k --resolve 'staging.event-junkie.de:443:10.10.1.1' https://staging.event-junkie.de/
curl -sk --resolve 'staging.event-junkie.de:443:10.10.1.1' 'https://staging.event-junkie.de/api/events?size=1'
```

**`-k` is correct here and must not be "fixed".** Staging issues from Let's Encrypt's _staging_ CA so the production rate limit is not burned; the warning is the
design working. [CLUSTER_ACCESS.md](CLUSTER_ACCESS.md) §6.

**Do not port-forward the site.** It skips TLS, the ingress rules and the middlewares — the things being tested. That is the opposite of the OpenObserve advice
below, and both are deliberate.

Production serves nothing publicly yet. Through its tunnel, once the application is installed:

```sh
curl -I -k --resolve 'event-junkie.de:443:10.10.0.1' https://event-junkie.de/
```

## Is one venue importing?

The question `/next-importer` and every "did that scraper break?" ends in. **Two commands, no `psql`, no `sudo`** — and the second answers the better half,
because a row in `event_source` is not the same claim as an event a visitor can see.

```sh
ej-venue quasimodo
```

Or by hand. The source row — status, retry budget, when it last succeeded, how many events that run wrote, and the error if there is one:

```sh
kubectl --context event-junkie-staging -n event-junkie port-forward svc/event-junkie-importer 8081:8081
curl -s localhost:8081/api/admin/event-sources/quasimodo | jq
```

And what the site would actually serve for it:

```sh
curl -sk --resolve 'staging.event-junkie.de:443:10.10.1.1' \
  'https://staging.event-junkie.de/api/events?venue=quasimodo&size=3'
```

**The two numbers should agree**, and when they do not that is the finding: `lastEventCount` is what the run wrote, `totalElements` is what survives as a future
event. A run that wrote 29 and a site that shows 0 is a venue publishing only past dates, which no infrastructure check sees.

**The importer needs a port-forward and the site must not have one.** No Ingress path names the importer and nothing in its namespace may reach it ([#416](https://github.com/enorm-labs/event-junkie/issues/416)) — port-forward works only because node-originated traffic is not subject to NetworkPolicy in k3s. The site goes through the ingress for the opposite reason: TLS, routing and the middlewares are part of what is being checked.

**This replaces the older recipe** of `scp`-ing a `.sql` file to the node and running `sudo -u postgres psql`. That still works and is below, but it needs the
superuser shell for a read-only question and it stops at the database.

## Database

```sh
# staging — PostgreSQL is on the k3s node
ssh -f -N -i ~/.ssh/id_ed25519_hetzner -L 15432:localhost:5432 ops@10.10.1.1
PGPASSWORD="$(kubectl --context event-junkie-staging get secret events-db -n event-junkie \
  -o jsonpath='{.data.password}' | base64 -d)" psql -h 127.0.0.1 -p 15432 -U events -d events
```

Production's database is a **separate node** with no public inbound at all, reached through the k3s node:

```sh
ssh -f -N -i ~/.ssh/id_ed25519_hetzner -L 15433:10.0.1.20:5432 ops@10.10.0.1
PGPASSWORD="$(kubectl --context event-junkie-production get secret events-db -n event-junkie \
  -o jsonpath='{.data.password}' | base64 -d)" psql -h 127.0.0.1 -p 15433 -U events -d events
```

A superuser shell, for anything `CREATE ROLE`-shaped — the forward above connects as `events`, which cannot:

```sh
ssh -i ~/.ssh/id_ed25519_hetzner ops@10.10.1.1 'sudo -u postgres psql -d events'
```

## OpenObserve

```sh
kubectl --context event-junkie-staging -n observability \
  port-forward svc/openobserve-openobserve-standalone 5080:5080
# then http://localhost:5080/ — root credentials from the password manager
```

**A port-forward is right here**, unlike for the site: nothing about OpenObserve is under test, it is an operator console, so the shortest path is correct.

```sh
cd deploy/dashboards && ./apply.sh            # push the dashboard
cd deploy/dashboards && ./apply.sh --check    # do the panels return data?
cd deploy/alerts && ./apply.sh                # push the alert rules
cd deploy/alerts && ./apply.sh --check        # can each rule fire, and would any fire now?
```

Operating it, including the stream-count trap that causes ingestion to stop: [OPENOBSERVE.md](OPENOBSERVE.md).

## Backups

```sh
ssh -i ~/.ssh/id_ed25519_hetzner ops@10.10.1.1 'sudo -u postgres walg check'
ssh -i ~/.ssh/id_ed25519_hetzner -J ops@10.10.0.1 ops@10.0.1.20 'sudo -u postgres walg check'
```

Expect `ok: newest <timestamp>, disk N%`. **`systemctl status` is not the check** — the timers can be green while every archive fails.
[BACKUPS.md](BACKUPS.md), [HEALTHCHECKS.md](HEALTHCHECKS.md).

## Infrastructure

```sh
cd infra && ./check-capacity.sh --probe staging      # orders a server and deletes it
cd infra && ./check-capacity.sh --all                # advertised inventory
```

**Only `--probe` answers the question.** The advertisement has disagreed with the order path three times out of four, in both directions.

Never `tofu plan/apply/destroy/import` on your own initiative — [`infra/AGENTS.md`](../../infra/AGENTS.md) opens with that, and it is the file to read before
touching anything there.

## Aliases

`scripts/shell-aliases.sh` — source it from `~/.zshrc`:

```sh
echo 'source ~/repos/event-junkie/scripts/shell-aliases.sh' >> ~/.zshrc
```

It is a file in the repository rather than a block to paste, for one reason: **a cheatsheet drifts from reality silently, and a sourced file drifts loudly** —
it is reviewed in PRs, linted by ShellCheck in `pre-commit`, and a wrong path fails in your terminal instead of quietly reading wrong on a page.

What it defines:

|                                    |                                                                   |
| ---------------------------------- | ----------------------------------------------------------------- |
| `ej-up` / `ej-up-prod` / `ej-down` | tunnels, with a handshake check rather than a hopeful "done"      |
| `ejk` / `ejkp`                     | `kubectl` with `--context` already pinned to staging / production |
| `ejf` / `ejfp`                     | the same for `flux`                                               |
| `ej-site` / `ej-api`               | curl the staging site and API with the right `--resolve` and `-k` |
| `ej-venue <slug>`                  | one venue end to end: the source row, then what the site serves   |
| `ej-db` / `ej-db-prod`             | open the tunnel _and_ a `psql`, then close it again               |
| `ej-o2`                            | the OpenObserve port-forward                                      |
| `ej-backups` / `ej-backups-prod`   | `walg check` on the right node                                    |
| `ej-status`                        | one screen: both tunnels, both clusters, anything not Ready       |

**No alias wraps `tofu`, `helm upgrade`, or anything that writes to production.** Those want the friction.
