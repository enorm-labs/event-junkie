# `infra/` — the Hetzner environment, declared

OpenTofu configuration for [#260](https://github.com/enorm-labs/event-junkie/issues/260). The reasoning behind every choice here — sizing, the ARM line, the
Cloudflare removal, why deploys are pull-based — is in [`docs/PLATFORM_SETUP.md`](../docs/PLATFORM_SETUP.md) and [ADR-012](../docs/adr/ADR-012_CLOUD_PLATFORM.md).
This file covers only what an operator needs in front of them while running it; [`AGENTS.md`](AGENTS.md) next to it covers the conventions and the commands an
agent must not run.

**`bootstrap/` is applied; `environments/` is not.** The DNS zones, their records and the SSH key are live on Hetzner as of 2026-08-10, which also proved the
S3 backend works against Hetzner's Ceph. Nothing under `environments/` exists yet — no server, network, firewall or Primary IP — and cloud-init has never run on
a real machine, so treat that first apply as an experiment rather than a formality.

## Layout — split by lifetime, not by environment

```
infra/
├── bootstrap/                 DNS zones · SSH keys        — long-lived, outside every destroy
├── modules/environment/       one environment's servers, network, firewall, cloud-init
└── environments/
    ├── production/            CAX21 k3s + CAX11 PostgreSQL · public · address records
    └── staging/               one CAX11, all-in-one · not on the public internet at all
```

**The split is the design, not tidiness.** `tofu destroy` on an environment is meant to be routine — it is how the "a destroy/apply cycle produces a working
environment" promise gets tested. A DNS zone caught in that blast radius is not routine at all: delegation would survive, because Hetzner's nameservers are
fixed, but **DNSSEC would not**. A re-created zone has a new key, the DS record at INWX stops matching, and the domain becomes *unresolvable* rather than merely
wrong. Keeping the zone in a stack that `destroy` never reaches makes that impossible rather than something to remember at the wrong moment. `delete_protection`
on the zone is the second lock.

Environments read the zone with a `data` source and manage only their own address records, so destroying production removes its `A`/`AAAA` records and leaves
the zone, its delegation and its key untouched.

## Before the first apply — three things only a human can do

1. **A Hetzner Cloud project and an API token** with read *and* write. Shown once.
2. **The Object Storage subscription and the `event-junkie-tfstate` bucket, created by hand**, plus its S3 credentials. **Bucket names are unique
   Hetzner-wide, across every customer and location** — if that one is taken, pick another and change `bucket` in all three `backend.tf` files together.
   The subscription is billed per account regardless of how many buckets, projects or locations you use, up to 100 buckets, so creating `event-junkie-o2`
   (ADR-015) and `event-junkie-backups` (#270) at the same time costs nothing and saves a trip.
   Note that S3 credentials are **project-scoped, not bucket-scoped**: one key pair reaches all three buckets. That is acceptable here and worth knowing before
   assuming the backup bucket is isolated from the state bucket.
   Choose **Falkenstein (fsn1)** and **Private**. The location has to match `region` and the `endpoints.s3` URL in all three `backend.tf` files — but it does
   **not** have to match where the servers run. Hetzner charges nothing for "internal traffic within the network zone `eu-central`", so a server in `nbg1`
   reading a bucket in `fsn1` is free, and since buckets cannot be moved afterwards, that is a useful thing not to be constrained by. Private is not a
   preference: a public bucket needs no S3 key to *read*, and the state file describes the whole network.
   **Then turn versioning on — it is not in the creation dialog.** The console offers only location, name and visibility; versioning is an S3 API call
   afterwards, and it matters because locking is unavailable (see below), which makes a bad concurrent write a live risk with nothing else standing between it
   and a hand-rebuilt state file:

   ```sh
   export AWS_ACCESS_KEY_ID=... AWS_SECRET_ACCESS_KEY=... AWS_DEFAULT_REGION=fsn1
   ENDPOINT=https://fsn1.your-objectstorage.com

   aws s3api put-bucket-versioning --endpoint-url $ENDPOINT \
     --bucket event-junkie-tfstate --versioning-configuration Status=Enabled

   aws s3api get-bucket-versioning --endpoint-url $ENDPOINT --bucket event-junkie-tfstate   # expect: Enabled
   ```

   Then cap the history, because Hetzner's own guide warns that versioning grows storage silently. State files are small, so 90 days is generous:

   ```sh
   aws s3api put-bucket-lifecycle-configuration --endpoint-url $ENDPOINT --bucket event-junkie-tfstate \
     --lifecycle-configuration '{"Rules":[{"ID":"expire-old-state","Status":"Enabled","Filter":{"Prefix":""},
       "NoncurrentVersionExpiration":{"NoncurrentDays":90}}]}'
   ```
3. **Nothing else in the console.** Servers, networks, firewalls, IPs and DNS are all declared here; creating one by hand means the first apply either duplicates
   it or needs a `tofu import`.

### Why the state bucket is hand-made, and why that is not a lapse

It is the one deliberate exception to "declared, not clicked", for a reason that does not go away with effort: **a state backend cannot be managed by the state
it holds.**

The provider constraint is real but narrower than it first looks. Hetzner's own [S3 guide](https://registry.terraform.io/providers/hetznercloud/hcloud/latest/docs/guides/s3-object-storage)
says there is no Cloud API for buckets and that third-party providers are "the only supported method" — but it then links a documented workflow using the
**MinIO** provider. So the other two buckets (`-o2` for OpenObserve, `-backups` for `wal-g`) *could* be declared that way when their issues land, at the cost of
a second provider and a second credential in this configuration. Only `-tfstate` is genuinely unavoidable by hand, and it is unavoidable for the
chicken-and-egg reason rather than the provider one.

## Running it

Credentials come from [`.envrc.example`](.envrc.example) via [direnv](https://direnv.net/), which loads them on entering `infra/` and **unloads them on
leaving** — so a token with full read/write over the Hetzner project is not sitting in the environment of every unrelated command afterwards. It reads the
three secrets from the macOS Keychain, so no credential is written to a file inside a public repository's working tree. One-time setup:

```sh
security add-generic-password -a "$USER" -s event-junkie-hcloud-token  -w   # prompts; stays out of shell history
security add-generic-password -a "$USER" -s event-junkie-s3-access-key -w
security add-generic-password -a "$USER" -s event-junkie-s3-secret-key -w

cd infra
cp .envrc.example .envrc && direnv allow
```

Then:

```sh
cd bootstrap
cp terraform.tfvars.example terraform.tfvars   # then edit — your SSH public key goes here
tofu init                                       # first contact with the S3 backend
tofu plan                                       # expect 11 resources: 2 zones, 8 rrsets, 1 SSH key
tofu apply
```

**Start with `bootstrap`, and note that it is free.** It creates only DNS zones and an SSH key, neither of which Hetzner charges for — so it is a zero-euro
rehearsal that proves the token, the provider and, above all, that the S3 backend really does talk to Hetzner's Ceph through all those `skip_*` flags. If any of
that is wrong, you find out before a single server bills.

Then the same in `environments/production` (or `staging`), filling `ssh_key_ids` from `tofu -chdir=../../bootstrap output -json ssh_key_ids`.

**The first apply needs `admin_cidrs`,** because WireGuard is not running yet and the firewall would otherwise admit nobody:

```sh
ADMIN="[\"$(curl -s https://ifconfig.me)/32\",\"$(dig +short myip.opendns.com @resolver1.opendns.com | tail -1)/32\"]"
tofu apply -var "admin_cidrs=$ADMIN"
```

**Two addresses, not one, if you are behind a corporate HTTP proxy.** `ifconfig.me` reports the address the *proxy* egresses from, because that request was
proxied. SSH and WireGuard are not, so they reach Hetzner from a different address, and an allowlist built from `ifconfig.me` alone silently refuses the very
connection it was meant to admit — which looks exactly like a broken firewall or a failed cloud-init. `dig +short myip.opendns.com @resolver1.opendns.com`
goes over UDP/53 rather than through the proxy, so it reports the direct one. Allow both; it is a bootstrap value that goes back to `[]` shortly anyway.

`tofu output next_steps` then prints the rest — collecting the WireGuard public key, building the client config, and repointing the kubeconfig at the tunnel.

### Getting onto the tunnel

WireGuard is a two-key handshake and the halves are generated in different places, which is the part worth getting straight before you start: **your** keypair
is made on your laptop *before* the apply, because the public half is an input to it; the **server's** keypair is made on the node at first boot and never
enters the state file, which is why its public half has to be collected by hand afterwards.

**1. Before the apply — your keypair.** `brew install wireguard-tools` if `wg` is missing.

```sh
umask 077 && mkdir -p ~/.wireguard
wg genkey > ~/.wireguard/staging.key    # private — stays here, never in this repo
wg pubkey < ~/.wireguard/staging.key    # public  — goes in terraform.tfvars
```

Use a different keypair per environment. A WireGuard key is base64, exactly 44 characters, ending in `=`; **an SSH public key is a different format and will
not work.** That mistake is rejected at plan time, along with a peer address outside `wireguard_subnet` and two peers sharing an address — all three otherwise
fail late, after a node has booted with no working tunnel and no open port to reach it on.

**2. Apply**, with `admin_cidrs` on the command line for this run only, since the tunnel does not exist yet:

```sh
ADMIN="[\"$(curl -s https://ifconfig.me)/32\",\"$(dig +short myip.opendns.com @resolver1.opendns.com | tail -1)/32\"]"
tofu apply -var "admin_cidrs=$ADMIN"
```

**3. After the apply — the server's public key.** `tofu output next_steps` prints this with the real addresses filled in:

```sh
ssh ops@<node-ipv4> sudo cat /etc/wireguard/public.key
```

**4. Your client config**, at `~/.wireguard/staging.conf` — or paste the same values into the WireGuard app:

```ini
[Interface]
PrivateKey = <contents of ~/.wireguard/staging.key>
Address    = 10.10.1.2/32        # the address you declared for this peer

[Peer]
PublicKey  = <the key from step 3>
Endpoint   = <node-ipv4>:51820
AllowedIPs = 10.10.1.0/24        # the tunnel subnet; widen only if you need the private network
PersistentKeepalive = 25         # keeps NAT mappings alive from a laptop behind one
```

```sh
sudo wg-quick up ~/.wireguard/staging.conf
ssh ops@10.10.1.1                # the node's address inside the tunnel
```

**5. Name resolution for staging.** `staging.event-junkie.de` deliberately has no public record, so map it in `/etc/hosts` to the node's tunnel address —
`10.10.1.1`. Reaching it through Traefik rather than `kubectl port-forward` is the point: a port-forward skips TLS and the whole routing path, and so tests a
different topology than production.

**6. Then close the door** — below.

### Closing the door behind you

Once the tunnel works, re-apply with `admin_cidrs = []`. Both rules disappear and **22 and 6443 become unreachable from the internet at any address**. Only
`51820/udp` stays open, and WireGuard does not answer a packet without a valid key — to a scanner that port is indistinguishable from a closed one, which is a
far better public surface than SSH, a service that announces itself and its version to anyone who connects.

**The fallback below the fallback is Hetzner's browser console** — VNC to the server regardless of firewall, WireGuard or SSH state. It is the reason none of
this is unrecoverable. Log into it once *before* you need it, so the first time is not during an outage.

### `uniqueness_error` on the SSH key, first apply

Hetzner deduplicates SSH keys by **fingerprint across the whole project**, so if the key is already there — added by hand while setting the project up, which
is easy to do without noticing — the first apply fails with:

```
Error: API request failed … SSH key not unique (uniqueness_error) … Status code: 409
```

Everything else in the same apply still succeeds; only this resource is missing from state. Import it rather than deleting and recreating, which keeps the
declared name and labels and converges the existing key onto them:

```sh
direnv exec . bash -c 'curl -sS -H "Authorization: Bearer $HCLOUD_TOKEN" https://api.hetzner.cloud/v1/ssh_keys' \
  | python3 -c "import sys,json; [print(k['id'], k['name']) for k in json.load(sys.stdin)['ssh_keys']]"

tofu import 'hcloud_ssh_key.admin["<name-from-tfvars>"]' <id>
tofu plan     # expect an in-place update of `name` and `labels`, never a replacement
```

**A replacement in that plan means the key text does not match.** The API stores keys without their trailing comment, which `ssh.tf` already normalises away —
see the comment there. If you still see one, compare the two strings before applying, because replacing a key that a running server was built with is how you
lose your own access.

### Behind a TLS-intercepting proxy

On a corporate network that terminates TLS, every AWS CLI call fails with:

```
SSL validation failed … [SSL: CERTIFICATE_VERIFY_FAILED] self-signed certificate in certificate chain
```

**This is neither a credentials problem nor a Hetzner problem**, and it is worth knowing because it reads like both. The AWS CLI bundles its own Python CA
file and never consults the macOS keychain, so it cannot see the proxy's root certificate. `curl` and OpenTofu both use the system trust store and keep
working, which makes the failure look specific to Hetzner when it is specific to the *tool*. Confirm what you are dealing with by looking at who signed the
certificate:

```sh
openssl s_client -connect fsn1.your-objectstorage.com:443 -servername fsn1.your-objectstorage.com </dev/null 2>/dev/null \
  | openssl x509 -noout -issuer
```

If the issuer is your employer rather than a public CA, build a bundle that has both the public roots and the corporate one, and `.envrc` picks it up
automatically:

```sh
mkdir -p ~/.certs
security find-certificate -a -c "<your corporate root CN>" -p /Library/Keychains/System.keychain > ~/.certs/corp-root.pem
cat /usr/local/aws-cli/awscli/botocore/cacert.pem ~/.certs/corp-root.pem > ~/.certs/aws-ca-bundle.pem
```

Keep it outside the repository — it is machine- and network-specific, and it changes when IT rotates the proxy certificate. **Never disable verification**
(`--no-verify-ssl`, `AWS_CA_BUNDLE` pointing at nothing) to make the error go away: that turns a working chain of trust into no chain of trust, on the
credentials that own your infrastructure.

### Check capacity before you apply

```sh
cd infra && ./check-capacity.sh          # or --all to see everything orderable in eu-central
```

**Hetzner sells out of server types**, and when it does `tofu apply` fails at the very last step with `error during placement (resource_unavailable)` — after
the network, firewall and Primary IPs already exist. The script asks first, in about a second, and exits non-zero while anything is missing, so it doubles as a
waiter:

```sh
until ./check-capacity.sh; do sleep 1800; done && say "capacity is back"
```

That script reads Hetzner's own API, so it reports exactly what an apply will hit — but only for *right now*. For the shape of the problem over time,
**[Server Radar](https://radar.iodev.org/cloud-status?arch=arm)** polls every minute and keeps the history, which answers the question this script cannot: has
the type been gone for an hour or a fortnight, and does it flicker back at a predictable time of day. It is community-run
([elsbrock/hetzner-radar](https://github.com/elsbrock/hetzner-radar)), not Hetzner, so treat it as the trend and `check-capacity.sh` as the fact. The `arch=arm`
filter is the relevant view here, and it also confirms the constraint the CAX line carries anyway: ARM is only ever offered in Falkenstein, Helsinki and
Nuremberg.

> **Status, 2026-08-11: the entire CAX (ARM) line is unavailable across all of `eu-central`, and `fsn1` has nothing at all.** The staging apply failed on
> exactly this. Nothing is wrong with the configuration — there are simply no machines, and the same request would fail for anyone.
>
> The decision was to **wait rather than re-platform**. The alternative is the newer `cpx*` generation in `nbg1`/`hel1` at roughly 2.8× the cost — `cpx22`
> (2 vCPU/4 GB) at €23.19 against `cax11` at €7.13 — which would also move the design from arm64 to x86 and retire the parity argument in
> PLATFORM_SETUP.md §1. That trade is cheap to make *now*, while no container images exist, and expensive later; it is written down here so the option is not
> rediscovered from scratch. Nothing downstream is blocked meanwhile: the Helm chart (#261) and the frontend container (#262) are developed against k3d.
>
> If capacity returns to `nbg1`/`hel1` but not `fsn1`, moving is two variables (`location`, and the `*_server_type` values) plus destroying the `fsn1` Primary
> IPs, which are location-bound.

## Things that will surprise you

**`delete_protection` does not stop OpenTofu.** The provider lifts its own locks before destroying, which its documentation says plainly — so a `tofu destroy`
in `environments/production` will delete the Primary IPs and everything else, protection or not. The flag stops a mis-click in the console and any other tool;
it is not a safety rail against this repository. What actually keeps a rebuilt server on the same address is `auto_delete = false`, set unconditionally, so
deleting a server never takes its address with it.

**The DNS zones are the exception, and they carry `prevent_destroy`.** That one *is* enforced by OpenTofu, which refuses to plan a destroy at all. It is
deliberately the only such lock in the repository: `prevent_destroy` cannot be made conditional, so using it freely would make routine work require editing the
config, and a safety rail people routinely edit around is worse than none. The zone earns it because destroying it is the one action here that can take the
domain off the internet entirely.

**Editing anything under `cloud-init/` replaces the node.** `user_data` is a force-new attribute. Correct for disposable nodes, and worth knowing before a
one-word comment fix rebuilds production. `tofu plan` says so in red.

**The PostgreSQL node has no public IPv4, and it is not confirmed that `apt` survives that.** Everything it fetches goes over IPv6, and some mirrors are
IPv4-only. This is not resolved in advance because it is reversible in seconds: if the first boot fails, set `postgres_public_ipv4 = true`, re-apply, and the
address attaches for about €0.50/month. Check `/var/log/cloud-init-output.log` on the node. Do **not** pre-emptively build a NAT gateway — but if one is ever
wanted for its own sake, `AGENTS.md` records the mechanism and what not to copy from Hetzner's tutorial.

**Locking is not turned on.** `use_lockfile` needs S3 conditional writes, which Hetzner's Ceph has not been confirmed to implement — so it sits commented out in
every `backend.tf`. Until someone tests it and writes the answer down here: **one operator applying at a time.** Concurrent applies against an unlocked backend
corrupt state silently.

**The WireGuard server key never appears in the state file.** It is generated on the node at first boot, which is why the public key has to be collected by hand:

```sh
ssh ops@<node-ip> sudo cat /etc/wireguard/public.key
```

A private key passed in as a variable would end up in state, and state lives in a bucket.

**Root SSH is disabled during boot, and the `ops` user is the only way in.** Hetzner injects the project's keys into *root*; `harden.sh` then turns root login
off. It refuses to do so if `/home/ops/.ssh/authorized_keys` is missing or empty, and the module refuses to build with an empty `ssh_public_keys` — both guards
exist because the failure they prevent is a node reachable only through the browser console.

## What is deliberately not here

| | Where it lives |
|---|---|
| Database roles, credentials, schema | [#261](https://github.com/enorm-labs/event-junkie/issues/261) — application lifecycle, not machine lifecycle. Baking them in would mean a rebuild silently re-creates credentials |
| `wal-g`, backups, the restore drill | [#270](https://github.com/enorm-labs/event-junkie/issues/270) |
| Helm chart, cert-manager, ingress, NetworkPolicies | [#261](https://github.com/enorm-labs/event-junkie/issues/261) |
| Flux | [#414](https://github.com/enorm-labs/event-junkie/issues/414) |
| Observability | [ADR-015](../docs/adr/ADR-015_OBSERVABILITY_STACK.md), [#271](https://github.com/enorm-labs/event-junkie/issues/271) |
| A `staging` address record | Nowhere. Staging does not resolve on the public internet — [PLATFORM_SETUP.md §4a](../docs/PLATFORM_SETUP.md) |
| A 301 from `event-junkie.com` | Go-live. A redirect needs a certificate, which needs the name to resolve, which needs the site to be up |

## Local checks

Everything CI runs, and all of it works without credentials:

```sh
tofu fmt -recursive -check -diff infra
tofu -chdir=infra/bootstrap init -backend=false && tofu -chdir=infra/bootstrap validate
shellcheck -x infra/modules/environment/cloud-init/*.sh
```

`tofu plan` is the first command that needs a token, and it is where the unproven parts of this configuration will first show themselves.
