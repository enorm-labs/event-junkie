# Connecting to a cluster

Day-to-day access. **Nothing here changes anything** — it is the read-only half of operating an environment.

Setting a cluster up for the first time is [CLUSTER_BOOTSTRAP.md](CLUSTER_BOOTSTRAP.md); this assumes that has already happened and you have the tunnel config
and the node's address. The commands with the reasoning stripped out are [DAILY_COMMANDS.md](DAILY_COMMANDS.md).

## The short version

```sh
sudo wg-quick up ~/.wireguard/staging.conf         # 1. tunnel — nothing works without it
kubectl --context event-junkie-staging get nodes   # 2. work
sudo wg-quick down ~/.wireguard/staging.conf       # 3. done
```

For the database it is two hops rather than one — the tunnel, then an SSH forward, because `pg_hba` does not admit the tunnel address (§7).

Everything below is that, with the parts that go wrong explained.

## Two environments, and where this page assumes one

**Everything below is written for staging.** Production exists since 2026-08-21 and works the same way with different values — where a section says
`staging`, the production form is in this table:

|                 | Staging                                          | Production                                                     |
| --------------- | ------------------------------------------------ | -------------------------------------------------------------- |
| Tunnel config   | `~/.wireguard/staging.conf`                      | `~/.wireguard/production.conf`                                 |
| Tunnel address  | `10.10.1.1`                                      | `10.10.0.1`                                                    |
| kubectl context | `event-junkie-staging`                           | `event-junkie-production`                                      |
| PostgreSQL      | **on the k3s node**, `10.1.1.10`                 | a **separate node**, `10.0.1.20`, reached through the k3s node |
| Public web      | none — no `A` record, no 80/443                  | 80/443 open, but **nothing resolves yet**                      |
| TLS             | Let's Encrypt **staging** CA, so `-k` is correct | real certificates, via HTTP-01                                 |
| Observability   | OpenObserve, see §6b                             | **none yet**                                                   |

**Both tunnels can be up at once.** That is why the subnets differ — separate keypairs on `10.10.1.x` and `10.10.0.x`, chosen so the routing tables do not
collide. Overlapping ranges fail in a way that looks like a firewall problem for an hour.

**Production's database node has no inbound rules at all.** Its public IPv4 exists for egress — `wal-g` installs from GitHub, which publishes no AAAA record
(#270) — and the firewall admits nothing. So it is reached through the k3s node, and every command against it needs a jump:

```sh
ssh -i ~/.ssh/id_ed25519_hetzner -J ops@10.10.0.1 ops@10.0.1.20
```

**Production is dark, and that is a state rather than a stage.** `publish_dns` in `infra/environments/production/variables.tf` defaults to `false`, so the apex
and `www` resolve to nothing; a throwaway `prod-check` record points at the node so TLS can be exercised before launch. Flipping that variable is the launch.
CLUSTER_BOOTSTRAP.md §12.

---

## 1 · Bring the tunnel up

**Staging is not on the public internet.** There is no `A` record and no open 80/443/22/6443 — the tunnel is the only way in, so this comes first and nothing
else works without it.

```sh
sudo wg-quick up ~/.wireguard/staging.conf
sudo wg show
```

**Check for `latest handshake`.** It is the only line that proves the tunnel is up — the interface appears and routes are added either way:

```
peer: xFfuj…
  endpoint: <node-ipv4>:51820
  latest handshake: 3 seconds ago      <- this, or it did not work
  transfer: 4.51 KiB received, 6.32 KiB sent
```

**No handshake almost always means outbound UDP/51820 is blocked** — corporate and hotel networks do this silently. Test from a phone hotspot before suspecting
the node. WireGuard never replies to an unauthenticated packet, so there is nothing to see from either side.

The tunnel is a _split_ tunnel: `AllowedIPs = 10.10.1.0/24` covers the cluster and nothing else, so the rest of your traffic keeps going out your own
connection.

## 2 · Get the kubeconfig — once

k3s writes one on the node for `127.0.0.1`; this rewrites it to the tunnel address, which works because `10.10.1.1` is one of the API server's certificate SANs.

```sh
mkdir -p ~/.kube
ssh -i ~/.ssh/id_ed25519_hetzner ops@10.10.1.1 sudo cat /etc/rancher/k3s/k3s.yaml \
  | sed 's|127.0.0.1|10.10.1.1|' > ~/.kube/event-junkie-staging
chmod 600 ~/.kube/event-junkie-staging

# k3s names every cluster, user and context `default`. Give it a real name.
KUBECONFIG=~/.kube/event-junkie-staging kubectl config rename-context default event-junkie-staging
```

**Run that on your laptop.** The `ssh` is fetching a file, not somewhere to stand — see the traps below.

**This file is cluster-admin.** `chmod 600`, never commit it, and re-fetch rather than copy it around. You only do this once; it does not need repeating each
time you connect.

## 3 · Point kubectl at it

Two ways. Pick one and stay with it.

### A · Keep it separate (default)

```sh
export KUBECONFIG=~/.kube/event-junkie-staging     # this shell only
kubectl --context event-junkie-staging get nodes
```

Nothing else on your machine can see the cluster, and forgetting the export fails loudly rather than quietly hitting the wrong cluster. Add it to a
direnv `.envrc` if you want it per-directory.

### B · Merge it into `~/.kube/config` (convenient)

```sh
cp ~/.kube/config ~/.kube/config.bak.$(date +%Y%m%d)          # it gets rewritten — back it up

KUBECONFIG=~/.kube/config:~/.kube/event-junkie-staging \
  kubectl config view --flatten > ~/.kube/config.merged
mv ~/.kube/config.merged ~/.kube/config
chmod 600 ~/.kube/config

kubectl config get-contexts | grep event-junkie-staging       # confirm it arrived
```

`--flatten` inlines the certificates and token, so the merged file stands alone and `~/.kube/event-junkie-staging` becomes redundant.

> **What merging costs, so it is a choice rather than a surprise.** Every `kubectl`, `helm` and `flux` command now defaults to _whatever context is current_,
> and one of them is a real cluster running the real database. [`deploy/AGENTS.md`](../../deploy/AGENTS.md) forbids running `helm install/upgrade/uninstall` or
> Flux commands against anything but `k3d-*` without being asked — merging makes that rule depend entirely on `--context` discipline rather than on the shell
> you happen to be in. **Pass `--context` explicitly for anything that writes.**

### Switching context

```sh
kubectl config current-context                      # where am I?
kubectl config use-context event-junkie-staging     # switch
kubectl --context event-junkie-staging get pods -A  # or override per command — preferred for writes
```

With [kubectx](https://github.com/ahmetb/kubectx) (installed):

```sh
kubectx                          # list all contexts, current one highlighted
kubectx event-junkie-staging     # switch
kubectx -                        # back to the previous one
kubens flux-system               # default namespace for this context
```

`kubectx` gives an interactive picker only when `fzf` is installed — without it, the bare command just lists. `brew install fzf` if you want the picker.

## 4 · Check it works

```sh
kubectl --context event-junkie-staging get nodes
```

```
NAME          STATUS   ROLES           AGE   VERSION
staging-k3s   Ready    control-plane   36m   v1.36.3+k3s1
```

A hang usually means the tunnel, not the cluster — `sudo wg show` and look at the handshake again.

Useful next looks:

```sh
kubectl --context event-junkie-staging get pods -A
flux --context event-junkie-staging get all -A          # sources, kustomizations, releases
kubectl --context event-junkie-staging logs -n event-junkie deploy/event-junkie-bff --tail=50
```

## 5 · k9s, for anything more than one command

[k9s](https://k9scli.io/) (installed) is a terminal UI over the same kubeconfig — far quicker than repeated `kubectl` for reading logs, watching a rollout or
finding why a pod is unhappy.

```sh
k9s --context event-junkie-staging
```

Enough to be useful: `:pods` `:deploy` `:svc` `:helmreleases` to jump, `/` to filter, `l` for logs, `d` to describe, `y` for the YAML, `Esc` back, `:q` to
quit. `s` opens a shell in a container and `Ctrl-D` deletes a resource — both write, so treat them as you would the equivalent `kubectl`.

k9s follows `KUBECONFIG` and the current context if you omit `--context`, which is precisely the thing worth being explicit about on a real cluster.

## 6 · The site itself

**`staging.event-junkie.de` does not resolve, anywhere, on purpose.** There is no public `A` record and there never will be — that is the whole design
(PLATFORM_SETUP §4a). So the name has to be mapped locally, to the node's _tunnel_ address:

```sh
sudo sh -c 'echo "10.10.1.1  staging.event-junkie.de" >> /etc/hosts'
```

Then `https://staging.event-junkie.de/` in a browser, with the tunnel up.

**Reaching it through Traefik is the point.** `kubectl port-forward` would also get you a page, and it would prove almost nothing: it skips TLS, the ingress
rules, the `/api` split and the middlewares, so it tests a different topology than production. If the routing is broken, a port-forward hides it.

### Expect a certificate warning, and do not fix it the easy way

The certificate is real and correctly issued — for `staging.event-junkie.de`, via DNS-01, valid 90 days — but it comes from Let's Encrypt's **staging** CA,
whose root no browser trusts. Click through it.

**Do not install the Let's Encrypt staging root to silence the warning.** It issues to anyone who asks, so trusting it means trusting a CA that will happily
vouch for any domain, for every site you visit. That is a permanent change to your machine, to remove one warning on one host you reach through a tunnel.

**And do not switch the issuer to production either — that was decided, not deferred.**
[#265](https://github.com/enorm-labs/event-junkie/issues/265) closed on it: production issues over HTTP-01, so pointing staging at the production ACME endpoint
would rehearse account registration and nothing else, while spending a rate limit that is per _registered_ domain and therefore shared with the real site. The
reasoning is in [CLUSTER_BOOTSTRAP §11](CLUSTER_BOOTSTRAP.md#11--verify-the-certificate) and next to the value itself in
`deploy/clusters/staging/helm-release.yaml`. Click through the warning.

### Without touching `/etc/hosts`

`curl` can resolve a name for one request, which is also the quickest way to check the site is up from a script:

```sh
curl -I --resolve 'staging.event-junkie.de:443:10.10.1.1' https://staging.event-junkie.de/ -k
```

`-k` skips verification, for the staging-CA reason above. What a healthy response looks like:

```
HTTP/2 200
server: nginx
x-robots-tag: noindex, nofollow      <- the environment is un-indexable
content-type: text/html
```

### What each path should do

Worth knowing, because two of these look wrong and are not:

|                    |                                                                                                                                       |
| ------------------ | ------------------------------------------------------------------------------------------------------------------------------------- |
| `/`                | `200` — the frontend                                                                                                                  |
| `/api/events`      | `200` — the BFF, which serves under `/api` itself; there is no rewrite                                                                |
| `/api/admin`       | **`404`** — correct. The importer's admin API has no ingress backend at all                                                           |
| `/actuator/health` | **`200`, but from nginx** — the SPA catch-all, _not_ the actuator. Check `server:` and the body before concluding anything is exposed |

That last row is the one that looks alarming. Actuator lives on its own port that no ingress rule names, so any unmatched path falls through to the frontend's
SPA fallback and returns the index page with a `200`.

## 6a · The importer's admin API, and seeding staging

**The importer has no ingress backend on any cluster** (§6's table, and `deploy/charts/event-junkie/templates/ingress.yaml` says so in a comment). That is the
design from PLATFORM_SETUP §8a: rather than defend `/api/admin/**` with an allowlist or basic-auth middleware, it is simply not routed, and the tunnel is the
only way to it. So this is the one place where a `kubectl port-forward` is the right tool rather than the lazy one — §6's warning about port-forwards hiding
routing bugs is about the _site_, which does have an ingress to test.

```sh
kubectl --context event-junkie-staging port-forward -n event-junkie svc/event-junkie-importer 18081:8081
```

**`18081`, not `8081`.** The local importer from `scripts/dev-env.sh` owns `8081`, and a forward that silently lands on a local stack is how you seed the wrong
database and believe you seeded staging.

That port is what the `staging` environment in `http/http-client.env.json` points `importer-host` at, so every `.http` file under `http/importer/` works against
staging unchanged:

```sh
cd http && ijhttp --env-file http-client.env.json --env staging importer/dev-seed.http
```

`bff-host` in the same environment is the real `https://staging.event-junkie.de`, through Traefik — the read path _does_ have an ingress, so there is no reason
to bypass it. It needs `/etc/hosts` (§6) and **`ijhttp --insecure`**, because the certificate comes from Let's Encrypt's staging CA.

`scripts/dev-env.sh seed-all` is deliberately not the way to do this: it hardcodes `--env local` and health-checks a local importer first, which is what keeps
it from ever reaching a real cluster.

## 6b · OpenObserve, for logs and metrics

The service is `ClusterIP` on port 5080 in the `observability` namespace, and staging is not on the public internet — so there is no URL, and that is
deliberate. **`/metrics` and `/api/metrics` are blocked for public access whenever OpenObserve's own ingress is enabled**, which is one more reason not to give
it one here. Reach it through the tunnel and a port-forward:

```sh
kubectl --context event-junkie-staging -n observability \
  port-forward svc/openobserve-openobserve-standalone 5080:5080
```

Then `http://localhost:5080/` and log in with the root credentials from the password manager — the ones in the `openobserve-credentials` Secret
([SECRETS.md](SECRETS.md)). They are not in this repository and no deploy will bring them.

**A port-forward is the right tool here, unlike for the site below.** §6a rejects it for the application because it skips TLS, the ingress rules and the
middlewares — the very things being tested. Nothing about OpenObserve is being tested by reaching it; it is an operator console, so the shortest path is the
correct one.

**If the page does not load, check the release before the tunnel:**

```sh
flux --context event-junkie-staging get helmrelease openobserve -n flux-system
```

A missing or malformed `openobserve-credentials` Secret leaves it in a failed state rather than running-but-inaccessible — that is the intended shape, so a
reconcile error is the first thing to read, not the last.

## 7 · The PostgreSQL database

**The WireGuard tunnel alone is not enough, and the reason is worth understanding before you try.** PostgreSQL listens on `localhost` and `10.1.1.10` — the
private network — and `pg_hba.conf` admits exactly two ranges:

```
host  all  all  10.1.1.0/24     scram-sha-256      # the private network
host  all  all  10.42.0.0/16    scram-sha-256      # pods
```

Your tunnel address is `10.10.1.2`, which is in **neither**. So widening `AllowedIPs` to route `10.1.1.0/24` does not help: you would reach port 5432 and then
be refused with `no pg_hba.conf entry for host`, which reads like a firewall problem and is not one. Adding the tunnel range to `pg_hba` would work and is the
wrong fix — it widens who may reach the database in order to save one flag.

**So the connection has to originate on the node.** An SSH local forward does that, needs no change to anything, and stops when you close it.

```sh
ssh -f -N -i ~/.ssh/id_ed25519_hetzner -L 15432:localhost:5432 ops@10.10.1.1
```

`-f -N` background with no shell; `15432` locally so it cannot collide with a PostgreSQL you already run. The WireGuard tunnel must be up first — `10.10.1.1`
is only reachable through it.

### The password

It lives in the Kubernetes Secret, which is the only copy:

```sh
kubectl --context event-junkie-staging get secret events-db -n event-junkie \
  -o jsonpath='{.data.password}' | base64 -d
```

For `psql`, pipe it into the environment rather than printing it:

```sh
PGPASSWORD="$(kubectl --context event-junkie-staging get secret events-db -n event-junkie \
  -o jsonpath='{.data.password}' | base64 -d)" \
  psql -h 127.0.0.1 -p 15432 -U events -d events
```

```
events=> \dt events.*
  event · artist · promoter · genre_tag · event_artist · event_genre_tag
  event_promoter · event_source · flyway_schema_history
```

The local client is PostgreSQL 17 (Homebrew) against a **18.6** server. That works; a few `\d`-family commands may warn about the version gap. `brew install
postgresql@18` if it ever matters.

### IntelliJ IDEA

IntelliJ has its own SSH tunnel, so it does not need the `ssh -L` above — but it **does** still need WireGuard up.

**Database** tool window → **+** → **Data Source** → **PostgreSQL**, then:

| Tab         | Field              | Value                                     |
| ----------- | ------------------ | ----------------------------------------- |
| General     | Host / Port        | `localhost` / `5432`                      |
| General     | Database           | `events`                                  |
| General     | User / Password    | `events` / from the Secret above          |
| **SSH/SSL** | **Use SSH tunnel** | ✔                                         |
| SSH config  | Host / Port        | `10.10.1.1` / `22`                        |
| SSH config  | User               | `ops`                                     |
| SSH config  | Auth type          | **Key pair**, `~/.ssh/id_ed25519_hetzner` |

**`Host: localhost` is correct and is the part people get wrong.** With a tunnel configured, IntelliJ resolves the host _from the SSH endpoint_ — so
`localhost` means the node, which is exactly where PostgreSQL is listening. Putting `10.10.1.1` there sends it somewhere nothing is bound.

**Test Connection** should report PostgreSQL 18.6. IntelliJ will offer to download the driver on first use.

> **This is a real database.** It is staging, so there is no personal data and nothing irreplaceable — but the importer is writing to it, and a stray `UPDATE`
> in a query console is not undone by a redeploy. IntelliJ's read-only checkbox on the data source is a cheap seatbelt.

Close the forward when you are done — it does not close itself:

```sh
pkill -f '15432:localhost:5432'
```

## 8 · Close the tunnel

```sh
sudo wg-quick down ~/.wireguard/staging.conf
sudo wg show                                   # no interface listed = down
```

Not strictly required — it is a split tunnel and idle costs nothing — but bringing it down means a stray command cannot reach staging, which is worth the two
seconds. If you merged the kubeconfig, this is the only thing standing between a mistyped context and a real cluster.

---

## The local k3d cluster is none of the above

`scripts/k3d-rehearsal.sh` creates a throwaway cluster for the rehearsal, and **everything on this page is the wrong instinct for it**. No tunnel, no fetching,
no `sed`, nothing to keep:

```sh
scripts/k3d-rehearsal.sh up        # k3d writes the kubeconfig itself
kubectl --context k3d-event-junkie get nodes
scripts/k3d-rehearsal.sh down      # and takes it away again
```

`k3d cluster create` merges into `~/.kube/config` and switches the active context as a side effect. The cluster is named `event-junkie`, so the context is
always **`k3d-event-junkie`** — and it only exists between `up` and `down`.

**Do not save a copy of it.** Every recreate changes three things: the **API server port** (the script passes no `--api-port`, so Docker assigns a random one),
the **cluster CA**, and the **client certificate**. A stale copy fails as a connection refused against a port nothing is listening on, which reads like a
crashed cluster rather than a stale file.

If `~/.kube/config` is ever rewritten while a cluster is running:

```sh
k3d kubeconfig merge event-junkie --kubeconfig-merge-default   # back into ~/.kube/config
k3d kubeconfig get event-junkie                                 # or print it standalone
```

**The contrast with staging is the point, and it is a design difference rather than an accident.** Staging's kubeconfig is fetched once and stays valid because
the cluster is long-lived — which is exactly why it lives in its own file and why losing it costs a rebuild. k3d's is disposable because the cluster is, so it
belongs in `~/.kube/config` and nobody should care when it changes.

**A rehearsal cannot touch staging, even with staging's context current.** `k3d-rehearsal.sh` never relies on the active context: every call goes through a
wrapper that passes `--context`/`--kube-context` explicitly, `guard_context` refuses to act on anything not named `k3d-*`, and `down` restores whatever context
was selected before. That mattered less when the only clusters were local; it matters now.

---

## Traps

|                                                |                                                                                                                                                                                                                   |
| ---------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`kubectl` fails right after `ssh`**          | You are in the ssh session on the node. The kubeconfig is on your laptop. Symptom is `localhost:8080 connection refused` plus `permission denied` on `config.yaml.d`. On the node itself it is `sudo k3s kubectl` |
| **`Permission denied (publickey)`**            | ssh is offering the wrong key. Needs `-i`; check with `ssh-add -l`                                                                                                                                                |
| **Everything hangs**                           | The tunnel, not the cluster. `sudo wg show` — no `latest handshake` means no tunnel                                                                                                                               |
| **`staging.event-junkie.de` does not resolve** | Correct — it has no public record. Map it to `10.10.1.1` in `/etc/hosts`                                                                                                                                          |
| **Certificate warning in the browser**         | Also correct. Staging issues from Let's Encrypt's _staging_ CA so the production rate limit is not burned — see CLUSTER_BOOTSTRAP §11                                                                             |
| **`no pg_hba.conf entry for host`**            | You reached PostgreSQL from the tunnel address. It only admits the private network and the pod range — connect through the SSH forward in §7, do not widen `pg_hba`                                               |
| **k3d: connection refused after a recreate**   | A saved kubeconfig. k3d assigns a new API port, CA and client cert every time — re-merge rather than keeping a copy                                                                                               |
