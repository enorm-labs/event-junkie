# Bootstrapping a cluster

From nothing to a reconciling environment. **Once per cluster**, from a laptop, in this order.

This is the one-time bring-up. What happens on every commit afterwards is [RELEASING.md](RELEASING.md); why it is shaped this way is
[ADR-016](adr/ADR-016_GITOPS_DELIVERY.md) and [PLATFORM_SETUP](PLATFORM_SETUP.md); the long-form detail behind steps 1–8 is [infra/README.md](../infra/README.md).

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

## 1 · Your WireGuard keypair — *before* the apply

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
tofu apply -var "admin_cidrs=$ADMIN"     # 6 resources: network, subnet, firewall, 2 Primary IPs, server
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

## 8 · The database, and the two secrets — *before* Flux

Nothing creates these: `postgres.sh` stops at "a server is running", and the chart never templates a password. Do them now, or the first reconcile installs a
crash-looping importer.

```sh
PGPASS="$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 40)"    # generated, never typed or printed

# role + database. SQL over stdin so the password never lands in the node's process list.
printf "CREATE ROLE events WITH LOGIN PASSWORD '%s';\nCREATE DATABASE events OWNER events;\n" "$PGPASS" \
  | ssh -i ~/.ssh/id_ed25519_hetzner ops@10.10.1.1 'sudo -u postgres psql -v ON_ERROR_STOP=1'

kubectl --context event-junkie-staging create namespace event-junkie
kubectl --context event-junkie-staging create secret generic events-db -n event-junkie \
  --from-literal=username=events --from-literal=password="$PGPASS"

# staging only — production solves HTTP-01 and holds no Hetzner token at all.
# In `cert-manager`, NOT the release namespace: a ClusterIssuer resolves secret refs there.
kubectl --context event-junkie-staging create namespace cert-manager
kubectl --context event-junkie-staging create secret generic hetzner -n cert-manager \
  --from-literal=token="<an hcloud API token with read+write>"
```

**Verify before Flux depends on it** — this also exercises the `pg_hba` private-network rule:

```sh
printf '%s\n' "$PGPASS" | ssh -i ~/.ssh/id_ed25519_hetzner ops@10.10.1.1 \
  'read -r p; PGPASSWORD="$p" psql -h 10.1.1.10 -U events -d events -tAc "SELECT current_user, current_database()"'
unset PGPASS                                                          # expect: events|events
```

Both secrets are the last hand-made objects in the system; [#416](https://github.com/enorm-labs/event-junkie/issues/416) replaces them with SOPS.

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
> `certManager.clusterIssuer.server` points at Let's Encrypt's *staging* ACME endpoint, because the production rate limit is **per registered domain** and
> `event-junkie.de` is the same registered domain production uses. Burning it here would lock production out for a week.
>
> So `https://staging.event-junkie.de` shows a certificate warning, by choice. What is proven is the **mechanism** — DNS-01 through the Hetzner webhook, for a
> hostname with no public `A` record. Switching to the production endpoint is then one value, and worth doing only once the mechanism is known to work.

**If a challenge fails at `Present`, fixing the config is not enough.** See the last row of the traps table.

---

## Traps, in the order they bite

| | |
|---|---|
| **Port 22 times out for the first ~2 min** | The node is booting. A timeout looks exactly like the firewall dropping you; `ping` answers much earlier. Wait and retry |
| **`ssh ops@…` → `Permission denied (publickey)`** | Your agent is offering the wrong key. Needs `-i`. It reads as though the `ops` user does not exist yet |
| **`cloud-init status` says `error` on a healthy node** | A bug in cloud-init's *Hetzner datasource*, in `init-local`, before our scripts run. Read `/var/log/cloud-init-output.log` and the service states instead |
| **`kubectl` fails on the node** | You are inside the `ssh` session. The kubeconfig is on your laptop. On the node it is `sudo k3s kubectl` |
| **`password authentication failed for user "events"`** | PostgreSQL reports a **missing role** identically to a wrong password. Check the role exists before assuming the Secret is wrong |
| **`422 Deploy keys are disabled`** | Org-level setting, not a token scope. No PAT fixes it |
| **Bootstrap's push rejected** | The `main` ruleset. Disable it for the two pushes, re-enable immediately |
| **DNS-01 challenge stuck `pending`** | Read the *challenge's* `status.reason`. A `groupName` mismatch shows up as an RBAC error for an API group nothing serves |
| **Fixing the issuer does not unstick it** | A challenge that failed at `Present` **cannot clean itself up** — its finalizer calls the same broken path forever, so it never finishes deleting and its order never progresses. The corrected config is simply never used. Clear it, then the new challenge starts within seconds |
| **Staging deploys a stale chart** | [#455](https://github.com/enorm-labs/event-junkie/issues/455) — snapshot versions sort lexically, so the semver range picks a random sha. Staging is pinned to a tag until that lands |
