# Bootstrapping a cluster

From nothing to a reconciling environment. **Once per cluster**, from a laptop, in this order.

This is the one-time bring-up. **Connecting to a cluster that already exists is [CLUSTER_ACCESS.md](CLUSTER_ACCESS.md)** — that is the one you want most days.
What happens on every commit afterwards is [RELEASING.md](RELEASING.md); why it is shaped this way is [ADR-016](../adr/ADR-016_GITOPS_DELIVERY.md) and
[PLATFORM_SETUP](PLATFORM_SETUP.md); the long-form detail behind steps 1–8 is [infra/README.md](../../infra/README.md).

**First run: 2026-08-13, staging — carried all the way through to an issued certificate.** Every command below has been executed against a real cluster, and the
traps at the bottom are the ones that actually cost time rather than the ones worth imagining. Where a step says "verify", it is because that verification
caught something.

---

## Before you start

```sh
brew install opentofu wireguard-tools fluxcd/tap/flux kubectl helm jq   # the tool set
cd infra && ./check-capacity.sh staging                                  # is the hardware orderable?
```

You also need: a Hetzner API token and S3 credentials (both via `direnv` in `infra/`), an SSH keypair whose public half is in `terraform.tfvars`, and a GitHub
PAT for step 9.

> **A green capacity check means "worth trying", not "will work".** Hetzner has refused orders for hardware it was advertising at that moment. See
> `check-capacity.sh`'s header.

## 1 · Your WireGuard keypair — _before_ the apply

The public half is an input to the apply; the server's half is generated on the node at first boot.

```sh
umask 077 && mkdir -p ~/.wireguard
wg genkey > ~/.wireguard/staging.key     # private — never leaves this machine
wg pubkey < ~/.wireguard/staging.key     # public  — paste into terraform.tfvars → wireguard_peers
```

## 2 · Apply the infrastructure

`admin_cidrs` goes on the command line **for this run only** — the tunnel does not exist yet, so without it the node boots unreachable.

```sh
cd infra/environments/staging
ADMIN="[\"$(curl -s https://ifconfig.me)/32\",\"$(dig +short myip.opendns.com @resolver1.opendns.com | tail -1)/32\"]"
tofu apply -var "admin_cidrs=$ADMIN"     # 8: network, subnet, firewall, 2 Primary IPs, server, PGDATA volume, its attachment
```

Two addresses, not one: behind an HTTP proxy `ifconfig.me` reports the proxy's egress while SSH and WireGuard arrive from elsewhere. The `dig` goes over
unproxied UDP.

## 3 · Wait for cloud-init, then check it properly

Give it **3–4 minutes** (207 s on the first real run). Port 22 is not open the moment `apply` returns.

```sh
IP=$(tofu output -raw k3s_ipv4)
ssh -i ~/.ssh/id_ed25519_hetzner ops@"$IP" 'sudo tail -5 /var/log/cloud-init-output.log; systemctl is-active k3s postgresql wg-quick@wg0'
```

Expect `cloud-init finished after …` and three × `active`. **Do not use `cloud-init status`** — see traps.

## 4 · Collect the server's public key and write your client config

```sh
ssh -i ~/.ssh/id_ed25519_hetzner ops@"$IP" sudo cat /etc/wireguard/public.key    # the one value only the node has

umask 077
cat > ~/.wireguard/staging.conf <<EOF
[Interface]
PrivateKey = $(cat ~/.wireguard/staging.key)
Address    = 10.10.1.2/32
[Peer]
PublicKey  = <the key printed above>
Endpoint   = $IP:51820
AllowedIPs = 10.10.1.0/24
PersistentKeepalive = 25
EOF
```

## 5 · Bring the tunnel up and verify the handshake

```sh
sudo wg-quick up ~/.wireguard/staging.conf
sudo wg show                                          # must print `latest handshake` — nothing else proves it
ssh -i ~/.ssh/id_ed25519_hetzner ops@10.10.1.1        # the node, over the tunnel
```

No handshake almost always means outbound UDP/51820 is blocked. **Test from a phone hotspot before suspecting the node.**

## 6 · Close the door

Only once step 5 works.

```sh
tofu apply                     # no -var: admin_cidrs falls back to [] — 0 to add, 1 to change
```

```sh
nc -z -G 5 "$IP" 22 || echo "22 unreachable — correct"      # verify from outside the tunnel
nc -z -G 5 "$IP" 6443 || echo "6443 unreachable — correct"
```

Only `51820/udp` remains open, and WireGuard never answers an unauthenticated packet.

## 7 · Kubeconfig

**On your laptop.** The `ssh` fetches a file; it is not somewhere to stand.

```sh
mkdir -p ~/.kube
ssh -i ~/.ssh/id_ed25519_hetzner ops@10.10.1.1 sudo cat /etc/rancher/k3s/k3s.yaml \
  | sed 's|127.0.0.1|10.10.1.1|' > ~/.kube/event-junkie-staging      # 10.10.1.1 is one of the cert's SANs
chmod 600 ~/.kube/event-junkie-staging

export KUBECONFIG=~/.kube/event-junkie-staging
kubectl config rename-context default event-junkie-staging           # k3s names everything `default`
kubectl --context event-junkie-staging get nodes                     # Ready
```

Add `10.10.1.1  staging.event-junkie.de` to `/etc/hosts` — the name has no public record by design.

## 8 · The database, and the hand-made secrets — _before_ Flux

Nothing creates these: `postgres.sh` stops at "a server is running", and the chart never templates a password. Do them now, or the first reconcile installs a
crash-looping importer.

```sh
PGPASS="$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 40)"    # generated, never typed or printed

# role + database. SQL over stdin so the password never lands in the node's process list.
printf "CREATE ROLE events WITH LOGIN PASSWORD '%s';\nCREATE DATABASE events OWNER events;\n" "$PGPASS" \
  | ssh -i ~/.ssh/id_ed25519_hetzner ops@10.10.1.1 'sudo -u postgres psql -v ON_ERROR_STOP=1'

# The `events-db` Secret is NOT created here any more: it is committed encrypted and Flux
# decrypts it (SECRETS.md). That inverts this step — the git copy is the source of truth, so an
# EXISTING environment's role password must MATCH it rather than be freshly generated:
#
#   PGPASS="$(sops --decrypt deploy/clusters/staging/secrets/events-db.yaml \
#              | yq '.data.password' | base64 -d)"
#
# Use the generated value above only for a genuinely new environment, and encrypt it into git in
# the same sitting. A role and a Secret that disagree is a CrashLoopBackOff whose cause is two
# files apart.
kubectl --context event-junkie-staging create namespace event-junkie

# staging only — production solves HTTP-01 and holds no Hetzner token at all.
# In `cert-manager`, NOT the release namespace: a ClusterIssuer resolves secret refs there.
kubectl --context event-junkie-staging create namespace cert-manager
kubectl --context event-junkie-staging create secret generic hetzner -n cert-manager \
  --from-literal=token="<an hcloud API token with read+write>"

# Flux's own namespace exists only after `flux bootstrap` in §9, so this one comes AFTER it —
# it is listed here to keep every hand-made object in one place. Nothing in the repository
# creates it: the token is `contents: write` on a public repo, so encrypting it into git would
# publish its ciphertext permanently (SECRETS.md). #565.
kubectl --context event-junkie-staging create secret generic github-dispatch -n flux-system \
  --from-literal=token="<the event-junkie-staging-github-dispatch PAT>"
```

**Verify before Flux depends on it** — this also exercises the `pg_hba` private-network rule:

```sh
printf '%s\n' "$PGPASS" | ssh -i ~/.ssh/id_ed25519_hetzner ops@10.10.1.1 \
  'read -r p; PGPASSWORD="$p" psql -h 10.1.1.10 -U events -d events -tAc "SELECT current_user, current_database()"'
unset PGPASS                                                          # expect: events|events
```

`events-db` is now committed encrypted and restored by Flux ([SECRETS.md](SECRETS.md)). **Six hand-made objects are left, and this list was two of them until 2026-08-21**, when a rebuild followed it and would have brought the cluster back with #271's entire observability stack dead and no obvious cause:

| Secret                    | Namespace       | Recreated from                                   | In this document                                  |
| ------------------------- | --------------- | ------------------------------------------------ | ------------------------------------------------- |
| `hetzner`                 | `cert-manager`  | `HCLOUD_TOKEN` — Keychain                        | below                                             |
| `github-dispatch`         | `flux-system`   | **the PAT, and nothing can re-derive it**        | below                                             |
| `sops-age`                | `flux-system`   | `~/.config/sops/age/event-junkie.txt`            | [SECRETS.md](SECRETS.md) §3                       |
| `openobserve-credentials` | `flux-system`   | a **fresh** root password + the `-o2` S3 keypair | [SECRETS.md](SECRETS.md) §openobserve-credentials |
| `openobserve-credentials` | `observability` | the same values again — see SECRETS.md on why    | as above                                          |
| `postgres-exporter`       | `observability` | `ALTER ROLE metrics` + a new DSN                 | as above                                          |

**Only `github-dispatch` has to be carried across a rebuild.** Everything else is derivable from the Keychain, a local file, or a role you can re-password —
which is worth knowing before you start copying secrets out of a cluster you are about to destroy. OpenObserve's root password can be new because its PVC is
`local-path` on the node's disk: the metadata DB dies with the node and the root user is re-seeded from the Secret at first boot.

**Two traps that cost time on 2026-08-21, both of which produce a credential that looks right and authenticates against nothing:**

- **`security find-generic-password -w` appends a newline.** `--from-file=token=<(kc …)` welds it into the value. Pipe through `tr -d '\n'`, and check with
  `kubectl get secret … -o json` that the decoded length is what you expect — 64 bytes for an hcloud token, 20 and 40 for the S3 pair.
- **`DATA_SOURCE_NAME` is a URI, so the password has to be percent-encoded.** A generated password containing `@`, `#`, `%` or `&` silently produces a DSN that
  parses as something else entirely. `urllib.parse.quote(pw, safe='')`.

The two below are hand-made for the same deliberate reason — this repository is public, and encrypting a secret into it publishes the ciphertext for good:

| Secret            | Namespace      | Why not encrypted                                                                                 | Production too?                     |
| ----------------- | -------------- | ------------------------------------------------------------------------------------------------- | ----------------------------------- |
| `hetzner`         | `cert-manager` | Read+write on the whole Hetzner account, and it takes two minutes to recreate                     | **No** — production solves HTTP-01  |
| `github-dispatch` | `flux-system`  | `contents: write` on this repository, which under ADR-016 is one ruleset away from cluster access | **Yes** — with its own separate PAT |

**Order matters for `github-dispatch` and only for it.** It lives in `flux-system`, which does not exist until `flux bootstrap` runs in §9 — so create it after that step, not with the others. Until it exists the `github-dispatch` Provider reconciles into a failed state and no deployment is recorded; nothing else is affected, and nothing needs restarting once it lands.

## 8b · The backup credential — _the node is not backing anything up until you do this_

`backups.sh` installed wal-g, turned on `archive_mode` and started two timers, and every one of them is failing right now. It could not do otherwise: the S3
access key would have had to travel through `user_data`, which is state, so the machine gets the mechanism and you supply the authority. The reasoning, and what
it costs, is [BACKUPS.md](BACKUPS.md) §5.

**This is the step a node rebuild silently undoes**, exactly like the `events` password. Put it in the rebuild checklist, not in your memory.

```sh
# The same project-scoped S3 key pair the state bucket uses — Hetzner has no finer grain.
# HEALTHCHECK_URL is what turns "the timers are failing" into an alert rather than a silence:
# a healthchecks.io check with a 26-hour period and a 2-hour grace (#518).
ssh -i ~/.ssh/id_ed25519_hetzner ops@10.10.1.1 'sudo install -d -m 0750 -o root -g postgres /etc/wal-g && \
  sudo tee /etc/wal-g/credentials.env >/dev/null && sudo chmod 0640 /etc/wal-g/credentials.env && \
  sudo chgrp postgres /etc/wal-g/credentials.env' <<'EOF'
AWS_ACCESS_KEY_ID=...
AWS_SECRET_ACCESS_KEY=...
HEALTHCHECK_URL=https://hc-ping.com/...
EOF
```

### The healthchecks.io check — create it before the credential file, not after

`HEALTHCHECK_URL` above is not optional decoration, and there is no URL to paste until the check exists. **[HEALTHCHECKS.md](HEALTHCHECKS.md) is the procedure**
— what to create, how to wire a node to it, and how to prove it fires. It is a separate page because the same account and channel carry the site probe (#271)
and anything else that alerts from outside, so it outgrew being a paragraph in the backup section.

The one-line version: create `walg-<environment>` (period 26h, grace 2h), paste its ping URL into the `HEALTHCHECK_URL` line above, and run
`sudo -u postgres walg check` to make it go green immediately rather than at the top of the next hour.

**`walg check` says so when you have not**, on every hourly run:

```
warning: HEALTHCHECK_URL is unset in /etc/wal-g/credentials.env — this check passes into a void.
```

That line exists because the two states are otherwise indistinguishable from outside: a dead-man's switch that is not wired up reports exactly what a healthy
one does (#518).

Then take the first base backup by hand rather than waiting for 02:30, because the failure you want to find is this one and you want to find it now:

```sh
ssh -i ~/.ssh/id_ed25519_hetzner ops@10.10.1.1 'sudo systemctl start walg-basebackup && sudo -u postgres walg check'
# expect: ok: newest <timestamp>, disk NN%
```

**`walg check` failing is the whole design working**, and a green `systemctl status` on the timer is not the same claim — BACKUPS.md §6.

### Prove the alert, once, by breaking it on purpose

**An alert nobody has seen fire is the same class of belief as an untested backup**, which is the argument this whole section rests on. The drill — inducing the
disk assertion, watching the check go red, and the two things that catch people out — is in [HEALTHCHECKS.md](HEALTHCHECKS.md), along with the log of when it
was last run.

**Once per bucket, not per cluster**, and **already done for `event-junkie-backups` on 2026-08-18** — the retention backstop the privacy notice depends on
([#277](https://github.com/enorm-labs/event-junkie/issues/277)). Only needed again for a new bucket:

```sh
cd infra && direnv exec . aws s3api put-bucket-lifecycle-configuration \
  --endpoint-url https://fsn1.your-objectstorage.com --bucket event-junkie-backups \
  --lifecycle-configuration '{"Rules":[{"ID":"retain-30-days","Status":"Enabled",
    "Filter":{"Prefix":""},"Expiration":{"Days":35}}]}'
```

**35 rather than 30, and no versioning on this bucket.** Both are deliberate and both have a failure mode attached — BACKUPS.md §3 and §4 before changing
either.

## 9 · Flux

Two repository settings have to be right first, and neither is a token scope:

```sh
gh api orgs/enorm-labs --jq .deploy_keys_enabled_for_repositories       # must be true
gh api repos/enorm-labs/event-junkie/rulesets --jq '.[] | {name, enforcement}'
```

- **Deploy keys must be enabled** for the org, or bootstrap fails at `422 Deploy keys are disabled for this repository`
- **Bootstrap pushes directly to `main`**, which the `main` ruleset forbids. Disable it (or add a bypass) for the two pushes, and **turn it back on immediately
  after** — with Flux live, branch protection is the control that replaces the kubeconfig

PAT scopes: classic `repo`, or fine-grained with **Contents: RW**, **Administration: RW**, **Metadata: RO**.

```sh
flux bootstrap github --owner=enorm-labs --repository=event-junkie \
  --branch=main --path=deploy/clusters/staging      # commits gotk-*, installs controllers, creates a read-only deploy key
```

Do **not** pass `--token-auth`; it stores the PAT in the cluster instead of a deploy key.

Then re-enable the ruleset and confirm:

```sh
gh api repos/enorm-labs/event-junkie/rulesets/<id> --jq '{enforcement, bypass_actors}'   # active, []
```

## 10 · Verify the reconciliation

```sh
flux --context event-junkie-staging get sources all -A        # GitRepository, OCIRepository, HelmRepositories
flux --context event-junkie-staging get helmreleases -A       # cert-manager → webhook → event-junkie, in that order
kubectl --context event-junkie-staging get pods -A
```

Success looks like `Helm test succeeded … 1 test hook completed successfully` on the app release — the chart's own smoke test, run where the workloads are
because CI cannot reach here.

**Then check that the reconcile reached GitHub**, which is the half that has no evidence inside the cluster (#565):

```sh
flux --context event-junkie-staging get alerts -A                                # github-dispatch: Ready
gh api repos/enorm-labs/event-junkie/deployments --jq '.[0] | {environment, ref, created_at}'
```

A Provider that is not Ready almost always means the `github-dispatch` Secret from §8 is missing or its PAT has expired — see [CREDENTIALS.md](../CREDENTIALS.md) #16 for the expiry date.

There is exactly one Provider per cluster, so a not-Ready one is unambiguous. (There used to be a second, `github`, posting commit statuses; [#567](https://github.com/enorm-labs/event-junkie/issues/567) deleted it — a HelmRelease reports a chart version, not a commit, so it could never have worked.)

## 11 · Verify the certificate

The last thing to come up, and the one most likely to sit quietly stuck.

```sh
kubectl --context event-junkie-staging get clusterissuer,certificate -A
kubectl --context event-junkie-staging get challenge -A \
  -o jsonpath='{range .items[*]}{.metadata.name}={.status.state} {.status.reason}{"\n"}{end}'
```

`Certificate … READY True`, and the challenge reaches `valid`. **While it is pending, the error lives on the `challenge`, not on the Certificate** — the
Certificate only ever says "not ready".

Confirm what was actually issued:

```sh
kubectl --context event-junkie-staging get secret event-junkie-staging-tls -n event-junkie \
  -o jsonpath='{.data.tls\.crt}' | base64 -d | openssl x509 -noout -subject -issuer -ext subjectAltName
```

> **The issuer will say `(STAGING)`, and that is deliberate — the certificate is not browser-trusted.**
> `certManager.clusterIssuer.server` points at Let's Encrypt's _staging_ ACME endpoint, because the production rate limit is **per registered domain** and
> `event-junkie.de` is the same registered domain production uses. Burning it here would lock production out for a week.
>
> So `https://staging.event-junkie.de` shows a certificate warning, by choice. What is proven is the **mechanism** — DNS-01 through the Hetzner webhook, for a
> hostname with no public `A` record, which is what staging existed to establish.
>
> **It stays on the staging CA, and that was reconsidered rather than inherited ([#265](https://github.com/enorm-labs/event-junkie/issues/265)).** Once DNS-01
> was known to work, switching looked like one value — but production issues over **HTTP-01**, so pointing staging at the production endpoint would rehearse
> ACME account registration and nothing else, while spending the shared domain's rate limit on the environment that is _meant_ to break. The warning is only
> ever seen from inside the tunnel.

**If a challenge fails at `Present`, fixing the config is not enough.** See the last row of the traps table.

---

## 12 · Standing an environment up dark — and rehearsing TLS before go-live

Production was applied this way on 2026-08-21: everything running, nothing resolving.

`public_web` only gates the 80/443 firewall rules, so a dark environment is still fully reachable over the tunnel. **The go-live switch is `publish_dns`**, in
`infra/environments/production/variables.tf`, and it defaults to `false`. It swaps rather than adds: false publishes one throwaway name, `prod-check`, at the
same addresses; true publishes the apex and `www`, and `prod-check` disappears in the same apply.

**The throwaway name exists because production solves HTTP-01**, which needs Let's Encrypt to reach the host _by name_. With nothing resolving, no certificate
can issue and the whole TLS path stays untested until the day it matters. One name nobody is looking for exercises all of it.

The application chart creates the real `letsencrypt-production` ClusterIssuer, so before it is installed you need a temporary one — and it **must not** share
that name, or the chart's install will collide with a resource Helm does not own:

```sh
# ClusterIssuer letsencrypt-rehearsal: acme-v02, http01 solver, ingressClassName traefik.
# Certificate prod-check-tls in `default`, dnsNames: [prod-check.<domain>]. Then:
kubectl --context event-junkie-production get certificate,order -n default
```

Result on 2026-08-21 — **Ready in about 50 seconds**, `order` `valid`:

```
subject=CN=prod-check.event-junkie.de
issuer=C=US, O=Let's Encrypt, CN=YR1          # a real intermediate, not (STAGING) Pretend Pear
tls.crt contains 3 certificates
openssl verify -CAfile <system roots> -untrusted <chain> <leaf>   ->  OK
```

That proves DNS, the firewall, the CAA record, Traefik and ACME reachability, all at once. **Delete the issuer, the certificate, its Secret and the ACME account
key afterwards** — the rehearsal is not a canary and a left-behind certificate renews every sixty days against a rate limit you want at go-live.

**What it does not prove** is serving: with the application suspended there is no Ingress for that hostname, so `curl https://prod-check…` fails the handshake
even though the certificate is valid and trusted. That is the absence of a workload, not a TLS fault, and it is worth expecting rather than debugging.

`prod-check.<domain>` is a different name from the apex, so it costs one of Let's Encrypt's fifty certificates per registered domain per week and leaves all
five duplicate slots for `event-junkie.de` itself.

## Rebuilding a node — including migrating to ARM

**A rebuild is this runbook again from §3, and that is the whole point of writing it down.** What follows is only the deltas.

You end up here for four reasons, and three of them are not optional:

|                                 |                                                                          |
| ------------------------------- | ------------------------------------------------------------------------ |
| Any edit under `cloud-init/`    | `user_data` is a force-new attribute                                     |
| **Changing architecture**       | `cpx*` ↔ `cax*` — see below                                              |
| The destroy/apply cycle         | [#424](https://github.com/enorm-labs/event-junkie/issues/424)'s last box |
| Something is broken past fixing | The reason a node is meant to be disposable                              |

### Architecture is a rebuild, not a resize, and the plan will not say so

Hetzner cannot rescale between architectures — [their FAQ](https://docs.hetzner.com/cloud/servers/faq/) lists rescale alongside snapshots and ISOs as places
where "it is not possible to work with two different architecture types". Within one architecture (`cpx22` → `cx23`) it is an in-place resize and behaves as you
would expect.

**Between them, `tofu plan` renders a tidy in-place update and the _apply_ fails against the API partway through.** So do not treat `k3s_server_type` as just
another variable when the prefix changes.

### What survives, and what does not

| Survives                                                                                                                                             | Does not                                                                   |
| ---------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------- |
| **The database** — `PGDATA` is on a volume, and the volume is not part of the server ([#460](https://github.com/enorm-labs/event-junkie/issues/460)) | **The k3s cluster** — new CA, new kubeconfig, new node identity            |
| **Both Primary IPs** — `auto_delete = false`, so the public address and your WireGuard `Endpoint` are unchanged                                      | **The WireGuard server key** — regenerated at first boot                   |
| The network, subnet and firewall                                                                                                                     | **Flux, and all six hand-made Secrets** — the cluster is new (§8)          |
| Your WireGuard _client_ keypair, and `wireguard_peers`                                                                                               | Anything on the node's own disk outside `/var/lib/postgresql`              |
| The backups already in the bucket — they are off-server, which is the point                                                                          | **`/etc/wal-g/credentials.env`**, so the node comes back archiving nothing |
| The DNS zones (`bootstrap/`, outside every environment destroy)                                                                                      |                                                                            |

**The database survives a _rebuild_, not a `destroy`.** The distinction is the whole of it. Replacing the server — which is what a `cloud-init/` edit or an
architecture change does — leaves the volume attached to whatever replaces it, and `postgres.sh` adopts the cluster already on it. A `tofu destroy` in the
environment directory deletes the volume along with everything else, because `delete_protection` does not stop OpenTofu. If you are about to destroy rather than
rebuild, the volume is not your safety net; the backups in the bucket are — see §8b, and note that a restore has not yet been rehearsed
([#270](https://github.com/enorm-labs/event-junkie/issues/270)).

**The backup credential is the second thing that will look fine and not be.** `backups.sh` runs on the new node, installs wal-g and starts the timers, and every
one of them fails because `/etc/wal-g/credentials.env` died with the old disk. Nothing about the node looks wrong. Re-do §8b as part of every rebuild, and let
`walg check` — not `systemctl status` — be what tells you it worked.

**`HEALTHCHECK_URL` dies with it, and that failure is quieter still.** The whole point of a dead-man's switch is that it reports by _not_ reporting, so a rebuilt
node whose credential file is missing the URL produces no signal at all — and neither does a healthy one, until the grace period elapses. Two hours later
healthchecks.io says the check is late, which is correct and is also the only thing that will tell you. Paste the **same** URL back rather than creating a new
check, or the history that makes "late" mean something starts over. `sudo -u postgres walg check` prints a warning naming the missing variable, and that is the
fast way to confirm it before waiting (#518).

**The server key is the one that will look like a broken tunnel.** `wireguard.sh` generates a keypair only if none exists, so a fresh node has a fresh one and
your `~/.wireguard/staging.conf` is pointing at a peer that no longer exists. The handshake simply never happens. Update the `PublicKey =` line from §4; your own
key and the `wireguard_peers` entry stay valid.

### The sequence

> **This block said `tofu destroy` until 2026-08-21, and that would have destroyed the database.** It sat four paragraphs below "Everything below is a
> _rebuild_, never a `destroy`", and contradicted it: staging's volume has `delete_protection = false`, and OpenTofu lifts its own locks anyway, so a `destroy`
> here takes `hcloud_volume.postgres` with it. A rebuild never needs one. Replacing the server is an ordinary `apply` — that is what #460 bought.

```sh
cd infra/environments/staging
./check-capacity.sh --probe staging   # ordering is the only real test — see the script's header

# 1. Edit main.tf if the point is to change hardware. Any edit under cloud-init/ already forces
#    replacement on its own; if nothing has drifted and you want one anyway, that is what
#    -replace='module.environment.hcloud_server.k3s' is for. Do NOT reach for `tofu destroy`.

# 2. The tunnel dies with the node, so the firewall has to admit you directly for the duration.
#    -4 ON BOTH: on 2026-08-21 `curl -s https://ifconfig.me` returned an IPv6 address, which makes
#    the /32 below meaningless, and the unforced `dig` returned nothing at all.
ADMIN="[\"$(curl -4 -s https://ifconfig.me)/32\",\"$(dig -4 +short myip.opendns.com @resolver1.opendns.com | tail -1)/32\"]"

# 3. PLAN FIRST, AND READ IT. Expect the server and hcloud_volume_attachment.postgres replaced,
#    the firewall changed in place, and NOTHING ELSE. `hcloud_volume.postgres` must not appear in
#    the plan at all — if it does, stop. That is the check this whole runbook exists for.
tofu plan -var "admin_cidrs=$ADMIN" -out=rebuild.tfplan   # Plan: 2 to add, 1 to change, 2 to destroy

# 4. A SAVED PLAN RE-READS terraform.tfvars AND REFUSES IF IT DISAGREES — "Mismatch between input
#    and plan variable value", because admin_cidrs is [] there and the plan was built with -var.
#    Put the same value in terraform.tfvars before applying (and take it out again at §6), or skip
#    -out and let `tofu apply -var …` prompt. A saved plan is worth the detour: it applies exactly
#    what you read, with no second plan in between and no confirmation prompt to mis-type.
tofu apply rebuild.tfplan
```

Then **§3 onward**, in full: wait for cloud-init, collect the _new_ server key and fix your client config, tunnel, close the door, kubeconfig, database, both
secrets, `flux bootstrap`. Steps 1 and 2 are the only ones you skip — your keypair and `terraform.tfvars` are unchanged.

Two things are cheaper the second time: nothing in `cloud-init/` is architecture-specific, and [#264](https://github.com/enorm-labs/event-junkie/issues/264)
publishes **multi-arch** images, so the chart, its tags and its digests-per-platform need no attention at all. That is what makes the architecture reversible;
it was not, before those images existed.

**Production is different and this section does not cover it.** Its PostgreSQL is a dedicated node, and a _rebuild_ there now keeps its data — that is #460,
and it is why the volume was declared before production was ever applied rather than migrated onto one afterwards. A **destroy** is still data loss, because
nothing off the volume exists yet: backups and a rehearsed restore are [#270](https://github.com/enorm-labs/event-junkie/issues/270). Do that one first.

### Proving the volume actually survives — the drill

**First run: 2026-08-17, staging — passed.** A sentinel row written at 20:11:27 was read back on a node that booted at 20:14:41, `postgres.sh` logged
`adopting the existing cluster on the volume`, and every table matched a `pg_dump` taken beforehand exactly — 3,310 events, 3,953 artists, zero rows lost. A
reboot afterwards confirmed the fstab entry and the `RequiresMountsFor` drop-in hold when the script does not run at all. Repeat it whenever `postgres.sh` or
`volume.tf` changes.

**That the volume is declared is not evidence that the data comes back**; the only evidence is having read a row that was written before the node was replaced.
Everything below is a _rebuild_, never a `destroy`.

```sh
# 1. Write a sentinel through the tunnel, from the k3s node.
ssh ops@<tunnel-address> "sudo -u postgres psql -c \
  \"create table if not exists rebuild_drill(at timestamptz default now()); insert into rebuild_drill default values;\" \
  -c 'select * from rebuild_drill;'"

# 2. Note what the volume is and where it is mounted, so step 5 compares against something.
ssh ops@<tunnel-address> "findmnt /var/lib/postgresql; ls /var/lib/postgresql/18/main/PG_VERSION"

# 3. Force a replacement of the server, and nothing else. Any cloud-init edit does it; so does
#    -replace, which is the honest way to do it without a spurious diff.
cd infra/environments/staging
ADMIN="[\"$(curl -s https://ifconfig.me)/32\",\"$(dig +short myip.opendns.com @resolver1.opendns.com | tail -1)/32\"]"
tofu plan -replace='module.environment.hcloud_server.k3s' -var "admin_cidrs=$ADMIN"
```

**Read that plan before applying it.** Expect exactly one `-/+` on the server and one `-/+` on `hcloud_volume_attachment.postgres`, which follows the server it
points at. **`hcloud_volume.postgres` must not appear in the plan at all.** If it does, stop — that is the failure this whole issue exists to prevent, and
applying would destroy the thing you are trying to prove survives.

Then apply, wait for cloud-init, fix your client config with the node's **new** WireGuard server key (§4 — this trap bites here too), and:

```sh
# 5. The proof. Same row, new machine.
ssh ops@<tunnel-address> "findmnt /var/lib/postgresql; sudo -u postgres psql -c 'select * from rebuild_drill;'"
```

`postgres.sh` logs which path it took — `adopting the existing cluster on the volume` on a successful rebuild, `seeding the volume` only ever on the first
boot of a fresh volume. Seeing `seeding` on a rebuild means the data was not found, and the row will confirm it.

Afterwards: `drop table rebuild_drill`, and put `admin_cidrs` back to `[]`.

#### The first time, the drill does not work as written — and why

**On an environment that has no volume yet, the first apply _seeds_ rather than adopts**, so a sentinel written beforehand is on the local disk and dies with the
node. Proving adoption needs the volume populated first. Two ways:

- **Two rebuilds.** Apply once to create and seed the volume (today's data is lost — `pg_dump` first), write the sentinel, then `-replace` the server to prove
  adoption. Simple, and it throws away a working database.
- **One rebuild, keeping the data**, which is what was actually done on 2026-08-17. Create the volume alone with
  `tofu apply -target=module.environment.hcloud_volume.postgres`, attach it out-of-band, run the new `postgres.sh` by hand on the live node so it seeds from the
  running cluster, write the sentinel, detach, then apply normally. The node is replaced once and adopts a volume that already holds the real dataset — a
  stronger proof than a sentinel alone, and a rehearsal of the live migration production would have needed had this been left until later.

**Do not try to `-target` the attachment.** `hcloud_volume_attachment` references `hcloud_server.k3s.id`, so targeting it pulls the server in as a dependency —
and the server's planned action is _replace_, which is the thing you were trying to avoid. Target the volume only; the attachment is what the out-of-band step
stands in for.

**`admin_cidrs` is not optional for any of this.** Its steady state is `[]`, and a replaced node generates a new WireGuard server key — so the tunnel stops
handshaking at exactly the moment SSH is closed, leaving Hetzner's browser console as the only way in. Pass `-var "admin_cidrs=…"` on every apply in the
sequence and close it again at the end. Note the recipe in §2 assumes both lookups return **IPv4**: `dig myip.opendns.com` can return an IPv6 address, which
needs `/128` rather than `/32` and otherwise fails at plan time with `is not the start of the cidr block`.

---

## Proving a restore actually works — the drill

**Moved.** The procedure is [RESTORE_RUNBOOK.md](RESTORE_RUNBOOK.md) §4 and §5, because a drill is a rehearsal of a real restore and keeping two copies of it
guarantees that the rehearsed one drifts from the real one. The design, the recorded results and the cadence are [BACKUPS.md](BACKUPS.md) §9.

**First run: 2026-08-18, staging — passed both halves**, full replay and point-in-time recovery past a `DROP TABLE`. Restore to serving in ~12 s on a 39 MB
cluster. Owner @enorm, quarterly, plus whenever `backups.sh`, `postgres.sh` or the PostgreSQL major version changes.

**What nags you is [`restore-drill-reminder.yml`](../../.github/workflows/restore-drill-reminder.yml)**, not this sentence. It opens the drill as an issue
assigned to the owner every quarter, and again whenever `backups.sh` or `postgres.sh` changes on `main` — so a skipped quarter shows up as an open issue rather
than as nothing at all. Each run records its measured timings in that issue and then overwrites the table in [BACKUPS.md](BACKUPS.md) §9.

## Traps, in the order they bite

|                                                                                |                                                                                                                                                                                                                                                                                                                                                   |
| ------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Port 22 times out for the first ~2 min**                                     | The node is booting. A timeout looks exactly like the firewall dropping you; `ping` answers much earlier. Wait and retry                                                                                                                                                                                                                          |
| **`ssh ops@…` → `Permission denied (publickey)`**                              | Your agent is offering the wrong key. Needs `-i`. It reads as though the `ops` user does not exist yet                                                                                                                                                                                                                                            |
| **`cloud-init status` says `error` on a healthy node**                         | A bug in cloud-init's _Hetzner datasource_, in `init-local`, before our scripts run. Read `/var/log/cloud-init-output.log` and the service states instead                                                                                                                                                                                         |
| **`kubectl` fails on the node**                                                | You are inside the `ssh` session. The kubeconfig is on your laptop. On the node it is `sudo k3s kubectl`                                                                                                                                                                                                                                          |
| **`password authentication failed for user "events"`**                         | PostgreSQL reports a **missing role** identically to a wrong password. Check the role exists before assuming the Secret is wrong                                                                                                                                                                                                                  |
| **`422 Deploy keys are disabled`**                                             | Org-level setting, not a token scope. No PAT fixes it                                                                                                                                                                                                                                                                                             |
| **Bootstrap's push rejected**                                                  | The `main` ruleset. Disable it for the two pushes, re-enable immediately                                                                                                                                                                                                                                                                          |
| **DNS-01 challenge stuck `pending`**                                           | Read the _challenge's_ `status.reason`. A `groupName` mismatch shows up as an RBAC error for an API group nothing serves                                                                                                                                                                                                                          |
| **Fixing the issuer does not unstick it**                                      | A challenge that failed at `Present` **cannot clean itself up** — its finalizer calls the same broken path forever, so it never finishes deleting and its order never progresses. The corrected config is simply never used. Clear it, then the new challenge starts within seconds                                                               |
| **Staging deploys a stale chart**                                              | Fixed in [#455](https://github.com/enorm-labs/event-junkie/issues/455): versions sorted by short sha, so the range picked one at random while `Ready`. If it recurs, compare `status.artifact.revision` with the newest published tag                                                                                                             |
| **`flux bootstrap` fails on `gotk-sync.yaml: no such file or directory`**      | A `flux-system/kustomization.yaml` was committed before bootstrap ran. Bootstrap `kustomize build`s that directory to install the controllers _before_ it generates the sync manifests, so the file names something that cannot exist yet. Commit it afterwards — [SECRETS.md](SECRETS.md) §4                                                     |
| **The sync reports `… is SOPS encrypted, configuring decryption is required`** | Two different causes. Either `flux-system` is missing from the cluster-level `resources:` list, so the patch never reaches the cluster — or it is there and the encrypted Secret arrived in the same reconcile, so the set that carries the patch is the set the Secret fails. The second needs one `kubectl patch` by hand; SECRETS.md §4 has it |
| **The tunnel stops working after a rebuild**                                   | The node generated a new WireGuard server key. Your client config points at a peer that no longer exists, and a handshake simply never happens — update `PublicKey =`. See _Rebuilding a node_                                                                                                                                                    |
| **`server_type` change fails during apply**                                    | `cpx*` ↔ `cax*` cannot be rescaled. The plan renders an in-place update anyway; the API refuses. It is a rebuild                                                                                                                                                                                                                                  |
| **PostgreSQL will not start after a rebuild**                                  | Deliberate. `postgres.sh` writes a `RequiresMountsFor=/var/lib/postgresql` drop-in, so if the volume did not attach, the service refuses rather than starting on the local disk and serving an empty database. `findmnt /var/lib/postgresql` and the cloud-init log say which                                                                     |
| **`walg-basebackup` fails, or `walg check` exits 1**                           | Almost always `/etc/wal-g/credentials.env` — absent on a fresh node and destroyed by a rebuild, because it is deliberately not in `user_data`. §8b. `sudo -u postgres walg check` says which of the three assertions failed                                                                                                                       |
| **The database stops accepting writes, disk full**                             | A failing `archive_command` does not block writes, it accumulates WAL — and `PGDATA` is a 10 GB volume. `walg check` warns at 85% for this reason. Fix the archive, then `pg_archivecleanup` or let the backlog drain; do **not** delete from `pg_wal` by hand                                                                                    |
| **A restore lists fine and will not replay**                                   | A base backup was removed while a later delta still needed it — `wal-g delete before` run without `FIND_FULL`, or a bucket lifecycle rule expiring at exactly the retention window rather than five days past it. §8b                                                                                                                             |
