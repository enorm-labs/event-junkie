# Secrets — what is hand-made today, and how SOPS replaces it

The objects nothing in this repository creates, why that is a problem worth fixing, and the procedure for fixing it — split by who has to do each part.

> **Done for staging on 2026-08-19** ([#416](https://github.com/enorm-labs/event-junkie/issues/416) item 8). `events-db` is committed encrypted and restored by
> Flux; `hetzner` stays hand-made by decision. Production gets the same
> treatment as part of standing it up ([#560](https://github.com/enorm-labs/event-junkie/issues/560)).
>
> **`github-dispatch` joined the list on 2026-08-19** ([#565](https://github.com/enorm-labs/event-junkie/issues/565)) and is **hand-made**, not encrypted — its
> scope is `contents: write`, which is the one place the "encrypt it, the value is a nuisance at worst" reasoning does not hold. See the note under the table.
> A fourth secret, `github-status`, was declared and then removed without ever existing ([#567](https://github.com/enorm-labs/event-junkie/issues/567)).

## What is hand-made today

| Secret                    | Namespace                                         | Holds                                                 | Created at                                                     |
| ------------------------- | ------------------------------------------------- | ----------------------------------------------------- | -------------------------------------------------------------- |
| `events-db`               | `event-junkie`                                    | the `events` role's password                          | [CLUSTER_BOOTSTRAP.md](CLUSTER_BOOTSTRAP.md) §8                |
| `hetzner`                 | `cert-manager`                                    | an hcloud API token, **read+write** — staging only    | §8                                                             |
| `openobserve-credentials` | `flux-system` **and** `observability` — see below | the root login and the `-o2` S3 keypair               | [SECRETS.md](SECRETS.md) §openobserve-credentials              |
| `github-dispatch`         | `flux-system`                                     | a fine-grained PAT, **`contents: write`** on one repo | [CLUSTER_BOOTSTRAP.md](CLUSTER_BOOTSTRAP.md) §8 — **after** §9 |

They are typed once by a human and exist nowhere else. **That is the whole problem**, and it is the same shape as the backup credential in §8b: a cluster
rebuild silently loses them, everything comes back looking healthy, and the failure is a `CrashLoopBackOff` at best and a certificate that quietly stops
renewing at worst.

`/etc/wal-g/credentials.env` is deliberately **not** in this list. It lives on the node rather than in the cluster, so SOPS does not reach it — see
[HEALTHCHECKS.md](HEALTHCHECKS.md) and §8b.

## Why SOPS + age, and not Sealed Secrets

Recorded so it is not re-litigated. **Sealed Secrets keeps its private key in the cluster**, which means the one event you most want to survive — losing the
cluster — is the event that makes every sealed secret in git permanently undecryptable. SOPS keeps the key outside, so the repository plus the key is a complete
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

**On `github-dispatch`.** The table's logic is exposure cost. A broken `github-dispatch` ciphertext buys `contents: write` on this repository — and under
ADR-016, what lands on `main` is what the cluster runs, so repository write access is one branch-protection rule away from being cluster access. That is the
same argument that kept the Hetzner token hand-made, applied to a different asset.

**Decided 2026-08-19: hand-made, like `hetzner`, not encrypted like `events-db`.** The rebuild-survival benefit is small — recreating a PAT is a two-minute job —
and the exposure cost is the highest of the three. So the count is **one of three**: `events-db` encrypted, `hetzner` and `github-dispatch` hand-made,
and that is the whole list.

**What hand-made means operationally, because it is easy to assume Flux will handle it:** nothing in this repository creates this Secret, and no deploy will
bring it. It is typed once against the cluster and exists nowhere else:

```sh
kubectl --context event-junkie-staging create secret generic github-dispatch \
  -n flux-system --from-literal=token=<the PAT>
```

Until it exists, the `github-dispatch` Provider reconciles into a failed state and no dispatch is sent — the Alert is configured correctly and simply has no
credential to use. Once it exists, notification-controller picks it up on its next reconcile; **nothing needs restarting**. The order does not matter, only that
both eventually exist.

**On `openobserve-credentials` (#271), decided 2026-08-20: hand-made.** Two assets in one Secret, and the second is the one that decides it.

The root login buys the observability stack: every log line and metric staging holds. LEGAL.md §7.5 is explicit that log content can carry personal data, so
that alone argues against publishing ciphertext permanently.

**The Object Storage keys are worse, and the reason is scope.** There is one S3 keypair for the whole project — `event-junkie-s3-access-key` in the Keychain —
and it reaches **all three buckets**: `-o2`, `-backups` and `-tfstate`. So the same credential that lets OpenObserve write Parquet also reads the database
backups and the OpenTofu state. Encrypting that into a public repository is the `hetzner` argument again with a wider blast radius.

> **Worth fixing rather than only documenting.** A pod that ingests untrusted content — venue HTML in error strings, request paths from the open internet —
> should not hold a credential that reaches the infrastructure state. **Give OpenObserve its own S3 keypair**, so it can be rotated without breaking the state
> backend, and scope it to `-o2` if Hetzner's bucket policies allow. The Secret below takes whatever keys it is given, so this is a decision about what you type
> into it rather than a change to any manifest.

**What hand-made means here**, same as `github-dispatch` above: nothing in this repository creates it and no deploy will bring it. Four keys in one Secret,
because the chart reads two directly (`auth.existingRootUserSecret`) and Flux merges the other two in through `valuesFrom`:

**`ZO_ROOT_USER_PASSWORD` must satisfy OpenObserve's own policy**, and it is enforced late: the pod
starts, replays its write-ahead log, and _then_ panics with `ZO_ROOT_USER_PASSWORD is too weak`.
Nothing before that point complains, so a rejected password looks like a broken deployment rather
than a bad value. **8-128 characters with at least one lowercase, one uppercase, one digit and one
special character** — most generated passwords qualify, but a long random alphanumeric one does not.

```sh
kubectl --context event-junkie-staging create namespace observability
kubectl --context event-junkie-staging create secret generic openobserve-credentials \
  -n flux-system \
  --from-literal=ZO_ROOT_USER_EMAIL=<a role address, not a personal one> \
  --from-literal=ZO_ROOT_USER_PASSWORD=<generated, stored in the password manager> \
  --from-literal=ZO_S3_ACCESS_KEY=<the -o2 access key> \
  --from-literal=ZO_S3_SECRET_KEY=<the -o2 secret key>
```

**`-n flux-system`, and then again in `observability`.** `valuesFrom` resolves Secrets in the HelmRelease's own namespace, which is `flux-system` like every
other release here; the chart's `existingRootUserSecret` reads from the release's _target_ namespace instead. **So this Secret has to exist in both** — the same
contents, created twice, until that asymmetry is worth solving properly.

Until it exists the release reconciles into a failed state, which is the intended shape: a missing credential should stop the deploy rather than produce a
running server nobody can log into. Once it exists, helm-controller picks it up on the next reconcile and nothing needs restarting.

**Decided: encrypt `events-db`, leave the Hetzner token hand-made.** It is staging-only (production solves ACME by HTTP-01 and holds no Hetzner token at all),
it is a two-minute recreation, and it is the one credential where the rebuild-survival argument buys least and the exposure argument costs most.

The alternative, if you would rather have no hand-made objects at all, is to move the Flux secrets to a **private** repository and point a second `GitRepository`
at it. That is a real option and a bigger change; it is not worth it for one staging DNS token.

## The procedure

Four steps. **Two are yours** — they involve a private key that must never reach this repository — and two are mine.

### 1. Generate the age key — yours ✅ _done 2026-08-19_

```sh
brew install sops age
mkdir -p ~/.config/sops/age
age-keygen -o ~/.config/sops/age/event-junkie.txt
# prints: Public key: age1....
```

**Back the private key up somewhere that is not this repository and not the cluster.** A password manager is the right place. The file is the entire recovery
story: with it, the repository restores every secret; without it, the ciphertext in git is noise and each secret has to be regenerated by hand.

`age-keygen` writes the public key as a comment inside the same file, so back up the file rather than the two halves separately.

**Give me the public key** (the `age1…` line). It is safe to publish — it only encrypts.

### 2. `.sops.yaml` and the encrypted files — mine ✅ _done for staging_

I add a `.sops.yaml` at the repository root naming the public key as the sole recipient and restricting encryption to secret values only:

```yaml
creation_rules:
  - path_regex: deploy/clusters/.*/secrets/.*\.yaml$
    encrypted_regex: ^(data|stringData)$
    age: age1...
```

`encrypted_regex` matters more than it looks: it leaves `metadata`, `kind` and `namespace` in plaintext, so a rendered manifest is still reviewable in a diff
and `flux schema validate` can read it — which is what `validate-chart.yml` already relies on and says so in a comment.

Then each secret becomes a committed, encrypted file, and the hand-made `kubectl create secret` steps come out of §8.

### 3. Put the private key on each cluster — yours ✅ _done for staging 2026-08-19_

Flux decrypts with a key it holds in-cluster. This is the one step that must be repeated per cluster, and repeated again after a rebuild:

```sh
cat ~/.config/sops/age/event-junkie.txt |
  kubectl --context event-junkie-staging create secret generic sops-age \
    -n flux-system --from-file=age.agekey=/dev/stdin
```

The key name **must** be `age.agekey` — Flux looks for a `.agekey` suffix and ignores anything else, silently.

### 4. Wire Flux's decryption — mine ✅ _done for staging_

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

**A patch is only worth anything if something builds it, and here that nearly did not happen.** A Flux `Kustomization` pointing at a directory with no
`kustomization.yaml` walks it recursively and picks up `flux-system/` for free — that is how a bootstrapped cluster manages Flux itself. This repository added an
explicit `kustomization.yaml` at the cluster level, which **replaces** that discovery with a literal list, and `flux-system` was not on it. So the patch rendered
correctly, was committed and merged, and never reached the cluster; the sync then failed on an encrypted Secret it had no key for:

```
Ready=False  ReconciliationFailed: Secret/event-junkie/events-db is SOPS encrypted,
             configuring decryption is required for this secret to be reconciled
```

`flux-system` is now on that list, so the cluster is self-managing again and the patch applies on every reconcile. **Verify by looking at the live object, not
the rendered one** — the two disagreed for as long as this bug existed:

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

**This can only be run once the encrypted file is on `main`** — Flux restores from the repository, so there is nothing to restore from until then. Applying over
the existing hand-made Secret transfers ownership without downtime; the value is identical, because it was encrypted from the live object.

**The manager field is the assertion that matters.** A secret that is still hand-made looks identical to a decrypted one from the outside — same name, same
keys, same value — and the only thing that distinguishes "Flux restored this" from "this survived because nobody deleted it" is who owns it. Delete the
hand-made secret and let Flux put it back; that is the test, and it is the whole point of the exercise.

## Rotating

Rotating the **age key**: generate a new keypair, add it as a second recipient in `.sops.yaml`, run `sops updatekeys` over each encrypted file, replace the
cluster secret, then drop the old recipient. Two recipients briefly, so nothing is undecryptable mid-flight.

Rotating a **secret's value** is the ordinary path — edit with `sops`, commit, let Flux apply it. Note that the old ciphertext stays in git history forever,
which is the public-repository caveat above, restated: **rotation changes the future, not the past.**

## What this does not solve

- **A leaked private key exposes everything, retroactively.** That is the trade against Sealed Secrets, taken deliberately.
- **The node's own credentials are out of scope** — `/etc/wal-g/credentials.env` and the `events` role password on the PostgreSQL side are written by hand
  because `user_data` is state. SOPS covers what the cluster holds, not what the machine holds.
- **It is not a secrets manager.** No dynamic credentials, no leases, no audit trail beyond `git log`. For one operator and three secrets that is the right
  size; it stops being so the moment there is a second person who should be able to deploy without being able to decrypt.
