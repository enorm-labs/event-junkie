# Secrets

What is encrypted into git and restored by Flux, what stays hand-made and why, and the procedure for each. Split by who has to do it.

## The short version

- **Six objects.** Three are encrypted into git and restored by Flux. Three are typed by a human and exist nowhere else.
- **Only `github-dispatch` cannot be regenerated.** Everything else comes back from the Keychain, a local file, or an `ALTER ROLE`.
- **A rebuild silently loses every hand-made one**, and the cluster comes back looking healthy. §8b is the same shape.
- **`sops-age` is the whole recovery story.** The repository without it is noise.

```sh
sops -d deploy/clusters/staging/events-db.sops.yaml | head    # can I still decrypt?
flux --context event-junkie-staging get helmreleases -A       # a missing credential fails the release, by design
```

> **In place on both staging and production.** `events-db` is committed encrypted and restored by Flux. `hetzner` and `github-dispatch` stay hand-made by
> decision. `github-dispatch` is the one place the "encrypt it, the value is a nuisance at worst" reasoning does not hold, because its scope is
> `contents: write`. See the note under the table.

## The eight objects, and where each comes from

| Secret                    | Namespace                                         | Holds                                                 | Created at                                                     |
| ------------------------- | ------------------------------------------------- | ----------------------------------------------------- | -------------------------------------------------------------- |
| `events-db`               | `event-junkie`                                    | the `events` role's password                          | [CLUSTER_BOOTSTRAP.md](CLUSTER_BOOTSTRAP.md) §8                |
| `hetzner`                 | `cert-manager`                                    | an hcloud API token, **read+write** — staging only    | §8                                                             |
| `openobserve-credentials` | `flux-system` **and** `observability` — see below | the root login and an `-o2` S3 keypair                | [SECRETS.md](SECRETS.md) §openobserve-credentials              |
| `github-dispatch`         | `flux-system`                                     | a fine-grained PAT, **`contents: write`** on one repo | [CLUSTER_BOOTSTRAP.md](CLUSTER_BOOTSTRAP.md) §8 — **after** §9 |
| `postgres-exporter`       | `observability`                                   | the `metrics` role's DSN                              | §postgres-exporter, below                                      |
| `sops-age`                | `flux-system`                                     | the age private key that decrypts `events-db`         | §3, below                                                      |
| `event-junkie-images`     | `event-junkie`                                    | an S3 keypair for the cached image bucket **only**    | §event-junkie-images, below                                    |
| `event-junkie-imgproxy`   | `event-junkie`                                    | the key and salt that sign imgproxy URLs              | §event-junkie-imgproxy, below                                  |

**All eight belong in that table.** Two were once documented only in their own sections below and never reached this summary. A rebuild that followed it would restore
four, which is exactly the failure mode a summary exists to prevent. Add a row here in the same change that adds a secret.

**Every one of these is per cluster.** The table lists eight objects, not eight values. Staging and production each hold their own copy. Two of them hold
_different_ values on purpose: `github-dispatch`, so revoking one does not take both clusters down, and `openobserve-credentials`, since
[#880](https://github.com/enorm-labs/event-junkie/issues/880).

**Only `github-dispatch` cannot be regenerated.** `hetzner` is the Keychain's `HCLOUD_TOKEN`, `sops-age` is `~/.config/sops/age/event-junkie.txt`, and
`postgres-exporter` is an `ALTER ROLE` away. OpenObserve's root password can be brand new, because its PVC is `local-path` on the node's disk. The metadata DB
dies with the node, and the Secret re-seeds the root user at first boot. Worth knowing before copying secrets out of a cluster you are about to destroy.

They are typed once by a human and exist nowhere else. **That is the whole problem**, and it is the same shape as the backup credential in §8b. A cluster
rebuild silently loses them, and everything comes back looking healthy. The failure is a `CrashLoopBackOff` at best, and a certificate that quietly stops
renewing at worst.

`/etc/wal-g/credentials.env` is deliberately **not** in this list. It lives on the node rather than in the cluster, so SOPS does not reach it — see
[HEALTHCHECKS.md](HEALTHCHECKS.md) and §8b.

### `event-junkie-images` — and the one place a shared keypair must not be used

The importer writes cached venue images to `event-junkie-images`, and the BFF reads them back to serve them (ADR-019).
Both need an S3 keypair, and **neither may be given the project-wide one.**

§92 above already makes this argument about OpenObserve: one keypair, `event-junkie-s3-access-key` in the Keychain,
reaches every bucket including `-backups` and `-tfstate`. Here the same objection is sharper rather than merely equal.
**The importer is the workload that fetches URLs taken out of scraped HTML.** A credential that reads the database
backups and the OpenTofu state turns a server-side request forgery into an infrastructure compromise. `ImageUrlValidator`
is a control and not a guarantee. It says so itself.

**So create a keypair for this bucket and nothing else**, and scope it to `event-junkie-images` if Hetzner's bucket
policies allow. This is a decision about what you type in, not a change to any manifest.

**One Secret, mounted into two workloads.** It is one bucket, so a second keypair is a second thing to rotate for no
gain. The scoping matters twice over here. With `images.serving.enabled` the same keypair sits in the pod that Traefik
can reach. Hetzner also scopes a keypair to a bucket and not to a verb. The BFF therefore holds a key that can write,
although it only ever gets.

```sh
kubectl create secret generic event-junkie-images -n event-junkie \
  --from-literal=IMAGE_STORAGE_ACCESS_KEY=<the images-bucket access key> \
  --from-literal=IMAGE_STORAGE_SECRET_KEY=<the images-bucket secret key>
```

**Losing it costs a refetch, not data.** Every cached image can be fetched again from the venue, which is what ADR-019
§2.1 means by the only re-derivable data here. So it is the least dangerous of the seven to lose. Its blast radius if
leaked depends entirely on how narrowly it was scoped.

**Without it both still run.** No credentials means no client is built. The importer records images and stores nothing,
and the BFF answers 503 on the image route. Both say so at startup, and a local run needs no bucket access at all.

### `event-junkie-imgproxy` — the one that is not a credential

imgproxy runs as a sidecar in the importer pod and checks a signature on every URL it is asked to
process. The key and salt are what produce that signature (ADR-020).

**It reaches nothing.** The other seven are credentials to a service. This pair is not.

It is a shared secret between two containers in one pod. Its job is to stop anything but the
importer calling the sidecar. imgproxy also binds `127.0.0.1`. Nothing outside the pod reaches it at
all, so the signature is the second lock rather than the only one.

**So losing it costs nothing.** Generate a fresh pair, restart the pod, and both sides agree again.
No stored object is signed, and no URL survives a restart. It is the least dangerous of the eight on
every axis, which is worth stating so a rebuild does not treat it as precious.

```sh
kubectl create secret generic event-junkie-imgproxy -n event-junkie \
  --from-literal=IMGPROXY_KEY="$(openssl rand -hex 32)" \
  --from-literal=IMGPROXY_SALT="$(openssl rand -hex 32)"
```

**Both values are hex, and imgproxy decodes them.** Signing with the ASCII of the hex string produces
a signature imgproxy rejects. The error is a `403 Forbidden`, which reads like a wrong key rather
than a wrong encoding.

**The sidecar also gets the images bucket keypair**, because it reads originals over S3 rather than
being handed bytes. That is the same Secret as above and not a second one.

## Why SOPS + age, and not Sealed Secrets

Recorded so it is not re-litigated. **Sealed Secrets keeps its private key in the cluster.** The one event you most want to survive is losing the cluster. That is
the event which makes every sealed secret in git permanently undecryptable. SOPS keeps the key outside, so the repository plus the key is a complete
recovery, which is the property being bought.

For one operator the operational difference is otherwise small, and the deciding argument is rebuild survival rather than ergonomics.

## The decision, taken 2026-08-19: one of three, because this repository is public

**Encrypting a secret into a public repository publishes its ciphertext, permanently.** Git history is world-readable, forkable and archived, so "we rotated it
later" does not un-publish the bytes. That is fine for a value whose exposure requires a future break in X25519 — and it is a different conversation for each
secret.

| Secret                    | If the ciphertext were ever broken                                                                                        | Worth encrypting into a public repo? |
| ------------------------- | ------------------------------------------------------------------------------------------------------------------------- | ------------------------------------ |
| `events-db`               | A Postgres password for a server reachable only through the private network and WireGuard. Useless without network access | **Yes**                              |
| `github-dispatch`         | Triggering `repository_dispatch` workflows on `main`. **The strongest of the three** — see below                          | **No** — see below                   |
| `hetzner`                 | **Read+write control of the Hetzner account** — servers, volumes, firewalls, the lot                                      | **Recommend not**                    |
| `openobserve-credentials` | Admin login to every log and metric, **and** Object Storage keys reaching all three buckets                               | **No** — see below                   |

**On `github-dispatch`.** The table's logic is exposure cost. A broken `github-dispatch` ciphertext buys `contents: write` on this repository. Under
ADR-016, what lands on `main` is what the cluster runs, so repository write access is one branch-protection rule away from cluster access. That is the
same argument that kept the Hetzner token hand-made, applied to a different asset.

**Hand-made, like `hetzner`, not encrypted like `events-db`.** The rebuild-survival benefit is small, because recreating a PAT is a two-minute job, and the
exposure cost is the highest of the three. So the count is **one of three**: `events-db` encrypted, `hetzner` and `github-dispatch` hand-made,
and that is the whole list.

**What hand-made means operationally**, because it is easy to assume Flux will handle it. Nothing in this repository creates this Secret, and no deploy brings
it. It is typed once against the cluster and exists nowhere else:

```sh
kubectl --context event-junkie-staging create secret generic github-dispatch \
  -n flux-system --from-literal=token=<the PAT>
```

Until it exists, the `github-dispatch` Provider reconciles into a failed state and sends no dispatch. The Alert is configured correctly and simply has no
credential to use. Once it exists, notification-controller picks it up on its next reconcile, and **nothing needs restarting**. The order does not matter, only that
both eventually exist.

**On `openobserve-credentials` (#271): hand-made.** Two assets in one Secret, and the second is the one that decides it.

The root login buys the observability stack: every log line and metric staging holds. LEGAL.md §7.5 is explicit that log content can carry personal data, so
that alone argues against publishing ciphertext permanently.

**The Object Storage keys are worse, and the reason is scope.** There is one S3 keypair for the whole project — `event-junkie-s3-access-key` in the Keychain —
and it reaches **all three buckets**: `-o2`, `-backups` and `-tfstate`. So the same credential that lets OpenObserve write Parquet also reads the database
backups and the OpenTofu state. Encrypting that into a public repository is the `hetzner` argument again with a wider blast radius.

> **Worth fixing rather than only documenting.** A pod that ingests untrusted content should not hold a credential that reaches the infrastructure state. This
> one ingests venue HTML in error strings, and request paths from the open internet. **Give OpenObserve its own S3 keypair**, so it can be rotated without
> breaking the state backend. Scope it to `-o2` if Hetzner's bucket policies allow. The Secret below takes whatever keys it is given. This is a decision about
> what you type into it, not a change to any manifest.

**Production took that advice and staging has not yet** ([#880](https://github.com/enorm-labs/event-junkie/issues/880)). Production's instance holds a
keypair created for it alone. Staging still holds the project-wide one. Hetzner issues credentials per project rather than per bucket, so this does
**not** narrow what the production key reaches. It buys independent rotation, and stops one cluster's compromise from being both. Narrowing the reach
needs a bucket policy denying that key on `-backups` and `-tfstate`, which is a separate question.

**What hand-made means here**, same as `github-dispatch` above: nothing in this repository creates it and no deploy will bring it. Four keys in one Secret,
because the chart reads two directly (`auth.existingRootUserSecret`) and Flux merges the other two in through `valuesFrom`:

**`ZO_ROOT_USER_PASSWORD` must satisfy OpenObserve's own policy**, and it is enforced late. The pod
starts, replays its write-ahead log, and _then_ panics with `ZO_ROOT_USER_PASSWORD is too weak`.
Nothing before that point complains, so a rejected password looks like a broken deployment rather
than a bad value. **8-128 characters with at least one lowercase, one uppercase, one digit and one
special character.** Most generated passwords qualify. A long random alphanumeric one does not.

```sh
kubectl --context event-junkie-staging create namespace observability
kubectl --context event-junkie-staging create secret generic openobserve-credentials \
  -n flux-system \
  --from-literal=ZO_ROOT_USER_EMAIL=<a role address, not a personal one> \
  --from-literal=ZO_ROOT_USER_PASSWORD=<generated, stored in the password manager> \
  --from-literal=ZO_S3_ACCESS_KEY=<the -o2 access key> \
  --from-literal=ZO_S3_SECRET_KEY=<the -o2 secret key>
```

**A fifth key, `O2_BASIC_AUTH_HEADER`, for the collector (#271 item 2).** The collector authenticates
to OpenObserve with an HTTP header rather than with a user and a password. Flux's `valuesFrom`
substitutes a value, and cannot compose one from two others. So the composed header is its own key:

```sh
# Same password you used above. Derived, not new — nothing extra to store in the password manager.
printf 'root password: '; read -rs OO_PASS; echo
HDR="Basic $(printf '%s:%s' 'hello@event-junkie.de' "$OO_PASS" | base64)"

kubectl --context event-junkie-staging patch secret openobserve-credentials -n flux-system \
  -p "{\"stringData\":{\"O2_BASIC_AUTH_HEADER\":\"$HDR\"}}"

unset OO_PASS HDR
```

**Only in `flux-system`** — this one is read by `valuesFrom`, which resolves in the HelmRelease's
namespace, and the collector's chart never reads it directly. **Rotate the root password and this key
goes stale silently.** The collector keeps posting with the old header, and OpenObserve starts
refusing. That looks like an ingestion outage rather than a credential problem. Re-derive it in the
same change.

**`-n flux-system`, and then again in `observability`.** `valuesFrom` resolves Secrets in the HelmRelease's own namespace, which is `flux-system` like every
other release here. The chart's `existingRootUserSecret` reads from the release's _target_ namespace instead. **So this Secret has to exist in both**: the same
contents, created twice, until that asymmetry is worth solving properly.

**Production is the same five commands with `--context event-junkie-production`**, and two of the values are deliberately different: its own root password,
and its own `-o2` keypair. Four copies in total across the two clusters, which is the cost of `valuesFrom` and `existingRootUserSecret` resolving in
different namespaces.

**Nothing there needs a bucket name or a prefix.** Production writes under `production/` in the shared `event-junkie-o2`, and that is set in
`deploy/clusters/production/openobserve.yaml` where it is reviewable — not in the Secret. A credential decides _whether_ it can write, never _where_.

Until it exists the release reconciles into a failed state, which is the intended shape. A missing credential should stop the deploy, rather than produce a
running server nobody can log into. Once it exists, helm-controller picks it up on the next reconcile and nothing needs restarting.

### `postgres-exporter` — a monitoring role, not the application's

**Hand-made, and for a different reason than the others.** This one is not about ciphertext exposure:
it needs a database role that does not exist yet, and creating one is `psql`, not `kubectl`.

**Do not reuse the `events` role.** It owns the schema and can write. An exporter that only reads
`pg_stat_*` has no business holding that. PostgreSQL ships `pg_monitor` for exactly this: a
predefined role granting the statistics views and nothing else.

**Getting a superuser shell**, because this is the one database task
[CLUSTER_ACCESS.md](CLUSTER_ACCESS.md) §7 does not cover. That section's `ssh -L` forward connects you
as the **`events`** role, which cannot `CREATE ROLE`. For a superuser you skip the forward and work on
the node itself, where the distribution's peer entries still admit the `postgres` account.
`cloud-init/postgres.sh` keeps them deliberately, "so `sudo -u postgres psql` keeps working for
operators":

```sh
# WireGuard first; 10.10.1.1 is only reachable through it. Staging co-locates PostgreSQL on the k3s
# node, so this is both hosts at once — production's database node has its own address.
ssh -i ~/.ssh/id_ed25519_hetzner ops@10.10.1.1
sudo -u postgres psql
```

**On production the database is a second machine with no inbound rules.** So it is reached through the k3s node. `-i` does not reach a jump host, and
CLUSTER_ACCESS.md §Two environments has the `~/.ssh/config` block that fixes it:

```sh
ssh -J ops@10.10.0.1 ops@10.0.1.20
sudo -u postgres psql
```

```sql
CREATE ROLE metrics WITH LOGIN PASSWORD '<generated, stored in the password manager>';
GRANT pg_monitor TO metrics;
-- No GRANT on the events schema. It is not supposed to read application data.
```

Then the connection string, as a single value — the whole DSN is the credential, so it is not
assembled from parts in a manifest:

```sh
printf 'metrics role password: '; read -rs PGPW; echo

kubectl --context event-junkie-staging create secret generic postgres-exporter -n observability \
  --from-literal=DATA_SOURCE_NAME="postgresql://metrics:${PGPW}@10.1.1.10:5432/events?sslmode=require"

unset PGPW
```

Production, with its own role password and its own address:

```sh
kubectl --context event-junkie-production create secret generic postgres-exporter -n observability \
  --from-literal=DATA_SOURCE_NAME="postgresql://metrics:${PGPW}@10.0.1.20:5432/events?sslmode=require"
```

**That address appears twice on production and both copies must agree**: here, and as an `ipBlock` in
`deploy/clusters/production/observability-netpol.yaml`. NetworkPolicy speaks CIDRs and cannot resolve a name. Get the DSN right and the policy wrong, and
the exporter starts and stays Ready. Its probe only asks whether `/metrics` answers, so the symptom is no database metrics at all.

**`DATA_SOURCE_NAME` is a URI, so the password has to be percent-encoded**, and the line above does not do it. A generated password containing `@`, `#`, `%`
or `&` silently yields a DSN that parses as something else. A generator satisfying OpenObserve's policy will happily produce all four. `@` starts the host,
`#` starts a fragment, and the exporter reports a connection failure that looks nothing like a quoting problem. Encode it:

```sh
PGENC="$(python3 -c 'import sys,urllib.parse; print(urllib.parse.quote(sys.stdin.readline().rstrip("\n"), safe=""))' <<<"$PGPW")"
```

**`10.1.1.10` is staging's `postgres_ip`**, the same address the application chart's `database.host`
carries. Staging co-locates PostgreSQL on the k3s node and still reaches it over the network.
Confirm it with `tofu -chdir=infra/environments/staging output postgres_ip` rather than trusting this
line. **Production's is different**, which is the whole reason it is a value and not a constant.

**`sslmode=require`** rather than the libpq default of `prefer`, which silently accepts plaintext if
the server declines TLS. On a private network that is a small risk and an even smaller cost to close.

### Registering the Signal number — not a Secret, but the same shape of problem

`signal-cli`'s registration is **state on a PVC**, not a Kubernetes Secret. It behaves like a
hand-made credential in every way that matters. Nothing in this repository creates it, no deploy
brings it, and **losing it stops alerting silently** (PLATFORM_SETUP §5a, caveat 3).

The pod runs, answers its health probe and sends nothing until this is done. That is precisely the
failure the external dead-man's switch exists to catch, and the second reason that layer is not
optional.

Once the prepaid SIM exists:

```sh
kubectl --context event-junkie-staging -n observability port-forward svc/signal-cli 8080:8080

# In another shell. +49… is the SIM's number, in international format.
curl -X POST 'http://localhost:8080/v1/register/+49XXXXXXXXX' \
  -H 'Content-Type: application/json' -d '{"use_voice": false}'

# Signal sends an SMS. Then:
curl -X POST 'http://localhost:8080/v1/register/+49XXXXXXXXX/verify/123-456'

# Prove it end to end before trusting it with an alert at 03:00:
curl -X POST 'http://localhost:8080/v2/send' -H 'Content-Type: application/json' \
  -d '{"message":"event-junkie alerting is alive","number":"+49XXXXXXXXX","recipients":["+49YYYYYYYYY"]}'
```

**Do the last one.** §5a is explicit: _"an alert route that never delivered a message at 23:00 is a
hypothesis, not a route"_. This is the cheapest moment to turn it into one.

**Then record, in the password manager and not here:** the number, its PIN, its PUK and the top-up
schedule. A prepaid number that lapses takes the alert channel with it, and the failure is silence.

**Encrypt `events-db`, leave the Hetzner token hand-made.** The token is staging-only, because production solves ACME by HTTP-01 and holds no Hetzner token at
all. It is a two-minute recreation, and it is the one credential where the rebuild-survival argument buys least and the exposure argument costs most.

The alternative, if you want no hand-made objects at all, is a **private** repository holding the Flux secrets, with a second `GitRepository` pointed at
it. That is a real option and a bigger change. It is not worth it for one staging DNS token.

## The procedure

Four steps, in this order, once per cluster.

### 1. Generate the age key

```sh
brew install sops age
mkdir -p ~/.config/sops/age
age-keygen -o ~/.config/sops/age/event-junkie.txt
# prints: Public key: age1....
```

**Back the private key up somewhere that is not this repository and not the cluster.** A password manager is the right place. The file is the entire recovery
story. With it, the repository restores every secret. Without it, the ciphertext in git is noise and every secret has to be regenerated by hand.

`age-keygen` writes the public key as a comment inside the same file, so back up the file rather than the two halves separately.

The public key (the `age1…` line) is safe to publish. It only encrypts.

### 2. `.sops.yaml` and the encrypted files

A `.sops.yaml` at the repository root names the public key as the sole recipient, and restricts encryption to secret values only:

```yaml
creation_rules:
  - path_regex: deploy/clusters/.*/secrets/.*\.yaml$
    encrypted_regex: ^(data|stringData)$
    age: age1...
```

`encrypted_regex` matters more than it looks. It leaves `metadata`, `kind` and `namespace` in plaintext, so a rendered manifest stays reviewable in a diff and
`flux schema validate` can read it. `validate-chart.yml` already relies on that, and says so in a comment.

Then each secret becomes a committed, encrypted file, and the hand-made `kubectl create secret` steps come out of §8.

### 3. Put the private key on each cluster

Flux decrypts with a key it holds in-cluster. This is the one step that must be repeated per cluster, and repeated again after a rebuild:

```sh
cat ~/.config/sops/age/event-junkie.txt |
  kubectl --context event-junkie-staging create secret generic sops-age \
    -n flux-system --from-file=age.agekey=/dev/stdin
```

The key name **must** be `age.agekey` — Flux looks for a `.agekey` suffix and ignores anything else, silently.

### 4. Wire Flux's decryption

The Flux `Kustomization` gains a `decryption` block. **It is added as a patch, not by editing `gotk-sync.yaml`**, which opens with `DO NOT EDIT` because
`flux bootstrap` regenerates it. The documented place is the bootstrap kustomization beside it:

```yaml
# deploy/clusters/staging/flux-system/kustomization.yaml
resources:
  - gotk-components.yaml
  - gotk-sync.yaml
patches:
  - patch: |
      - op: add
        path: /spec/decryption
        value:
          provider: sops
          secretRef:
            name: sops-age
    target:
      kind: Kustomization
      name: flux-system
```

`provider` is an enum whose only value is `sops`, and `secretRef.name` points at step 3's secret.

**Commit this file AFTER `flux bootstrap`, never before.** The tidier-looking order fails:

```
accumulating resources from 'gotk-sync.yaml': no such file or directory
```

`bootstrap` installs the controllers by running `kustomize build` over this directory, and it does that **before** it writes `gotk-sync.yaml`. So a
`kustomization.yaml` committed ahead of time names a file that cannot exist yet. **This ordering is a requirement, not an accident worth improving on.**

#### The deadlock this creates, and the one command that breaks it

Once `flux-system` is on the cluster-level resource list (above), Flux manages its own `Kustomization` — including this patch. That is the desired state and it
has an ugly corollary: **the patch is applied by the very sync that needs it.**

If the encrypted Secret and this patch arrive in the same reconcile on a cluster whose _live_ Kustomization has no `decryption` block, Flux applies neither. A
Kustomization is applied as a set. The encrypted Secret fails the set, and the patch that would fix it is in the set that just failed:

```
Ready=False  Secret/event-junkie/events-db is SOPS encrypted, configuring decryption
             is required for this secret to be reconciled
```

It cannot self-heal, and it looks exactly like the missing-`flux-system` bug above while having a different cause. Break it once, by hand, against the live
object:

```sh
kubectl --context event-junkie-production -n flux-system patch kustomization flux-system --type=json \
  -p '[{"op":"add","path":"/spec/decryption","value":{"provider":"sops","secretRef":{"name":"sops-age"}}}]'
```

From then on the committed patch reapplies it on every reconcile, and nothing needs doing again. You need this only when the encrypted Secret and the
`flux-system` entry land together.

**The clean ordering for a new cluster:** `sops-age` on the cluster → `flux bootstrap` → this patch committed → _then_ the encrypted Secret.
Doing the last two together is what deadlocks.

**A patch is only worth anything if something builds it.** A Flux `Kustomization` pointing at a directory with no `kustomization.yaml` walks it recursively and
picks up `flux-system/` for free. That is how a bootstrapped cluster manages Flux itself. An explicit `kustomization.yaml` at the cluster level **replaces**
that discovery with a literal list. **`flux-system` has to be on that list.** Leave it off and the patch renders correctly, commits, merges, and never reaches
the cluster. The sync then fails on an encrypted Secret it has no key for:

```
Ready=False  ReconciliationFailed: Secret/event-junkie/events-db is SOPS encrypted,
             configuring decryption is required for this secret to be reconciled
```

**Verify by looking at the live object, not the rendered one.** The two disagree for as long as the entry is missing:

```sh
kubectl --context event-junkie-staging -n flux-system get kustomization flux-system \
  -o jsonpath='{.spec.decryption}'          # expect: {"provider":"sops","secretRef":{"name":"sops-age"}}
```

## Verifying it

```sh
flux --context event-junkie-staging reconcile kustomization flux-system --with-source
kubectl --context event-junkie-staging -n event-junkie get secret events-db \
  -o jsonpath='{.metadata.managedFields[*].manager}'      # expect: kustomize-controller
```

**This can only be run once the encrypted file is on `main`.** Flux restores from the repository, so there is nothing to restore from until then. Applying over
the existing hand-made Secret transfers ownership without downtime. The value is identical, because it was encrypted from the live object.

**The manager field is the assertion that matters.** A secret that is still hand-made looks identical to a decrypted one from the outside: same name, same
keys, same value. The only thing that distinguishes "Flux restored this" from "this survived because nobody deleted it" is who owns it. Delete the hand-made
secret and let Flux put it back. That is the test, and the whole point of the exercise.

## Rotating

Rotating the **age key**: generate a new keypair and add it as a second recipient in `.sops.yaml`. Run `sops updatekeys` over each encrypted file, replace the
cluster secret, then drop the old recipient. Two recipients briefly, so nothing is undecryptable mid-flight.

Rotating a **secret's value** is the ordinary path — edit with `sops`, commit, let Flux apply it. Note that the old ciphertext stays in git history forever,
which is the public-repository caveat above, restated: **rotation changes the future, not the past.**

## What this does not solve

- **A leaked private key exposes everything, retroactively.** That is the trade against Sealed Secrets, taken deliberately.
- **The node's own credentials are out of scope.** `/etc/wal-g/credentials.env` and the `events` role password on the PostgreSQL side are written by hand,
  because `user_data` is state. SOPS covers what the cluster holds, not what the machine holds.
- **It is not a secrets manager.** No dynamic credentials, no leases, no audit trail beyond `git log`. For one operator and three secrets that is the right
  size. It stops being so the moment a second person should be able to deploy without being able to decrypt.
