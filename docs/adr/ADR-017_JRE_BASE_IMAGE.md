# ADR-017: The JRE base image — Liberica on Alpine, and why the runtime vendor is not the build vendor

## Status

**Accepted (2026-08-17) — `bellsoft/liberica-openjre-alpine:25` as the runtime base for both backend images. Temurin remains the build JDK.**

**Implemented in [#492](https://github.com/enorm-labs/event-junkie/issues/492) on 2026-08-17**, in the same change that
emptied `.trivyignore`. This ADR exists because the reasoning generalises past this one image. It is really about
**what an unfixable finding in a base layer costs**, and that will come up again.

Two things the implementation settled, which this document could only flag as risks:

- **Netty's native transports are not used.** Nothing in the build depends on `netty-transport-native-*`, so the NIO
  transport runs on musl unchanged. The largest musl risk was therefore not a risk at all.
- **The images halved.** `events-bff` went 658 MB → 332 MB and `events-importer` 665 MB → 339 MB. Compressed, that is
  183 MB → 111 MB and 186 MB → 115 MB. It was not a goal and is not the justification, but it is the largest single
  measured effect.

Does not supersede anything. [ADR-012](ADR-012_CLOUD_PLATFORM.md) chose the platform and
[ADR-016](ADR-016_GITOPS_DELIVERY.md) the delivery mechanism. Neither said anything about what the images are built
on.

## Context

### What forced the decision

`release.yml` blocks a publish on a `CRITICAL` or `HIGH` finding **that has a fix available**. `--ignore-unfixed` is
deliberate: a finding nobody can act on would otherwise block every release until someone deleted the gate. That
design assumes a fixable finding is one _we_ can fix, normally by bumping a base image digest.

`eclipse-temurin:25-jre` broke that assumption. It carries `/usr/bin/pebble`, Canonical's service manager, compiled
against **Go stdlib v1.26.5**. Trivy reports every Go standard-library CVE against it, each one marked `fixed` with a
fixed version available. None of them is fixable here. The fix is a Temurin rebuild against a patched Go, and that did
not happen.

The arithmetic is what settled it. The waiver went from **two entries to eight in three days**
([#503](https://github.com/enorm-labs/event-junkie/pull/503)). It grows on the Go release cadence rather than on
anything this project does:

| Date       | Waived | Trigger                                                                                             |
| ---------- | ------ | --------------------------------------------------------------------------------------------------- |
| 2026-08-14 | 2      | `CVE-2026-39821`, `CVE-2026-46600` — releases had been blocked on four consecutive pushes to `main` |
| 2026-08-17 | 8      | six more, after three days. Surfaced on an unrelated PR about version ordering                      |

**A waiver that grows on someone else's schedule is not a waiver, it is a subscription.** The reachability argument
was sound throughout and still is. pebble never runs, and nothing can feed it input. Both Dockerfiles set
`ENTRYPOINT ["java", "-jar", "application.jar"]` and a numeric non-root `USER`. The
problem is that repeatedly re-making a true argument is how a gate stops being read. Each batch is individually cheap,
and the habit is what rots.

### The constraints any candidate had to satisfy

Four of these are non-negotiable and come from decisions already made elsewhere:

- **Java 25.** `.sdkmanrc`, `java.version=25`, the Gradle toolchain. A base that forces a runtime downgrade is not a candidate.
- **`linux/amd64` and `linux/arm64` from one runner.** The Dockerfiles contain **no `RUN` instruction** on purpose,
  because Gradle extracts the layered jar instead. One runner therefore emits both platforms with no QEMU and no build
  matrix. Staging is x86 and production is meant to be arm64
  ([#424](https://github.com/enorm-labs/event-junkie/issues/424)), so both are real.
- **Non-root by numeric UID.** `USER 10001:10001` is numeric, so it needs no `/etc/passwd` entry and no `RUN useradd`.
  It must keep matching `security.runAsUser` in `values.yaml`, which `scripts/uid-consistency.sh` enforces. #448
  raised it above 10000.
- **Startup time.** The Dockerfiles already record that an AOT cache was rejected because it needs a `RUN`. Startup is measured against the chart's probe budget.

## Candidate options

Measured on **2026-08-17**, each scanned with the release workflow's own flags:
`trivy image <ref> --severity CRITICAL,HIGH --ignore-unfixed`. Vulnerability counts are a snapshot and will drift. The
`pebble` column is the one that does not.

| Base                                      | Arch                            | Java       | Fixable HIGH/CRIT                              | Base OS                   | pebble             |
| ----------------------------------------- | ------------------------------- | ---------- | ---------------------------------------------- | ------------------------- | ------------------ |
| `eclipse-temurin:25-jre` (incumbent)      | amd64 · arm64 · ppc64le · s390x | 25         | **8** — all pebble, all waived                 | Ubuntu 26.04              | **yes**            |
| **`bellsoft/liberica-openjre-alpine:25`** | amd64 · arm64                   | 25.0.4     | **0**                                          | Alpine 3.24.1, musl 1.2.6 | no                 |
| `eclipse-temurin:25-jre-alpine`           | amd64 · arm64                   | 25         | **3** — `libexpat`, `p11-kit`, `p11-kit-trust` | Alpine 3.23.5             | no                 |
| `cgr.dev/chainguard/jre`                  | amd64 · arm64                   | **26.0.2** | 0                                              | Wolfi                     | no                 |
| `gcr.io/distroless/java25-debian12`       | —                               | —          | —                                              | —                         | **does not exist** |

**Distroless was ruled out on availability, not merit.** There is no `java25` tag — only `java21`. Adopting it means downgrading the runtime to satisfy a
scanner, which inverts the point of the scanner.

**Chainguard was ruled out on version control, not quality.** `cgr.dev/chainguard/jre:latest` is **Java 26.0.2**
today, and the free tier publishes only `latest`. The runtime's major version would therefore track Chainguard's
release schedule rather than ours. Pinning a digest stops that drift, and also freezes the CVE fixes that were the
entire attraction. This repository pins ShellCheck, Helm, Flux, actionlint, Trivy and every base image by digest. It
should not adopt a runtime whose only tag is a moving target.

## Decision

**`bellsoft/liberica-openjre-alpine:25` for both backend runtime stages. Temurin stays as the build JDK** — `.sdkmanrc` and `actions/setup-java` are unchanged.

### Be honest about how much of this margin is durable

The **decisive** property is the absence of pebble, and **both** Alpine candidates have it. That is the part of this decision that will still be true next year.

The 0-versus-3 gap is mostly **base freshness, not vendor quality**. Liberica ships on Alpine 3.24.1 and Temurin's
Alpine variant on 3.23.5. All three of Temurin's findings are ordinary Alpine packages that get fixed on rebuild. That
is unlike pebble's frozen Go, which is the whole reason this ADR exists. **That gap can be expected to close.** So
Liberica is chosen for being clean today and for offering a version-pinned tag. The margin over
`eclipse-temurin:25-jre-alpine` is real today and may be temporary. Reverting to Temurin's Alpine variant is a one-line
change, and explicitly a good outcome rather than a defeat.

### Why the build vendor and the runtime vendor may differ

They answer different questions. The build JDK is a **developer-experience and reproducibility** choice. It is what a
contributor installs through `.sdkmanrc` and what CI compiles with, and Temurin is the sane default there. The runtime
base is an **attack-surface and image-hygiene** choice, decided on what is in the layer rather than on who compiled the
bytecode.

Java bytecode is vendor-neutral and both are OpenJDK builds. BellSoft states Liberica is TCK-verified. Building on one
certified OpenJDK build and running on another is ordinary practice, not a compromise.

**The cost of that split is stated plainly rather than waved away.** A JVM-level difference between the two vendors
would appear only at runtime, in staging or production, and never in a local build. That is a genuine
new failure mode. It is why the implementation is gated on a k3d rehearsal that runs the real image, plus the chart's
in-cluster `helm test`. A chart render would not do.

## Consequences

**What improves**

- **`.trivyignore` returns to empty**, which is the state its own header describes as correct. The gate blocks on a
  finding that can be acted on, and a red scan means something again.
- The image loses a service manager, an init system and a package manager it never used. Smaller attack surface, and smaller images.
- Publishing stops being hostage to Temurin's rebuild schedule.

**What it costs**

- **musl instead of glibc.** Confirmed rather than assumed: the image is Alpine 3.24.1 with musl 1.2.6. A pure-JVM
  workload is fine, but this stack runs Netty and R2DBC. Netty's native epoll transport is optional and unused by
  default, and that must be verified rather than believed. **DNS resolution differs between musl and glibc**, which
  deserves a thought for a service that reaches PostgreSQL by hostname over the private network.
- **A second JDK vendor in play**, with the runtime-only failure mode described above.
- **Alpine's own CVE cadence** replaces Ubuntu's. Different, and not obviously better. It is _fixable_, which is the
  property that was missing.
- The Liberica tag tracks patch releases (`25.0.4` today). Pinned by digest like every other base image here, so it moves only when someone moves it.

**What is unaffected, and worth stating so nobody re-derives it**

- **No `RUN` is introduced.** The single-runner multi-arch property survives, and the change is one `FROM` line per
  Dockerfile.
- The numeric `USER` needs no change — numeric UIDs require no passwd entry on any base.
- `WORKDIR`, the four layered `COPY`s, `EXPOSE` and the exec-form `ENTRYPOINT` are base-agnostic.
- The frontend image is untouched. It is `nginxinc/nginx-unprivileged:1.31-alpine` and was never affected.

## When to revisit

- **If Temurin publishes an Alpine variant that is consistently clean**, the vendor split closes for free. Unifying on
  Temurin is then the better answer. Check this at every base-image bump rather than treating the decision as
  settled.
- **If a musl-specific problem appears** — a native library, a DNS behaviour, a JVM ergonomics difference. The fallback is `eclipse-temurin:25-jre` again, with
  the waiver, which is a worse position but a known one.
- **At the Java 26 upgrade**, when every candidate's availability is re-drawn and distroless may finally have a matching tag.
- **If the pull-through registry or an image-signing requirement** ([#443](https://github.com/enorm-labs/event-junkie/issues/443)) constrains which registries
  are acceptable. Liberica is on Docker Hub, which carries rate limits that GHCR does not.

## References

- [#492](https://github.com/enorm-labs/event-junkie/issues/492) — the implementation, and where the option measurements were first recorded
- [#503](https://github.com/enorm-labs/event-junkie/pull/503) · [#483](https://github.com/enorm-labs/event-junkie/issues/483) — the two waiver batches and the
  base-image bump that was tried first
- `.trivyignore` — the waiver policy this decision exists to stop straining
- [`events-bff/Dockerfile`](../../events-bff/Dockerfile) · [`events-importer/Dockerfile`](../../events-importer/Dockerfile) — the no-`RUN` constraint, in the
  files it constrains
- [ADR-012](ADR-012_CLOUD_PLATFORM.md) — the platform, including the arm64 intent · [ADR-016](ADR-016_GITOPS_DELIVERY.md) — how images reach a cluster
