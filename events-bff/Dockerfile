# events-bff — the public read API.
#
# Build context is `events-bff/build/docker`, NOT the module directory:
#
#     ./gradlew :events-bff:bootJarLayers
#     docker buildx build -f events-bff/Dockerfile events-bff/build/docker \
#       --platform linux/amd64,linux/arm64 --build-arg VERSION=… --build-arg REVISION=…
#
# That context is exactly the extracted layers, so it needs no `.dockerignore` — there is nothing
# in it that should not be copied. Pointing the build at the module directory instead would drag
# the whole `build/` tree (classes, test reports, the fat jar) through the daemon.
#
# THERE IS DELIBERATELY NO BUILDER STAGE AND NO `RUN` THAT DOES BUILD WORK. The one `RUN` allowed
# here is a named `apk upgrade`, under the rule below.
#
# Spring Boot's reference Dockerfile extracts the layered jar in a builder stage with
# `RUN java -Djarmode=tools … extract`. The layout below is identical to what that produces — the
# extraction just happens in Gradle instead (`bootJarLayers` in the root build). The reason is
# multi-arch: build work in a `RUN` executes target-architecture code and produces
# architecture-specific output, so a two-platform build would need a runner per architecture plus
# a manifest merge. A JVM jar is architecture-independent, so with the extraction in Gradle one
# runner emits both platforms and only the base image layers differ.
#
# **A package upgrade is not build work, and it is the exception** (#770). `apk upgrade <pkg>` on
# the arm64 half runs under the emulation the `ubuntu-latest` runner already carries — the
# frontend image published this way under #964 and does again under #1118, and the arm64 upgrade
# takes about two seconds in its publish log. Nothing in this repository installs that emulation; if a runner image ever
# drops it, the publish fails at the build step, loudly. The rule for such a layer is the
# frontend's: one named package, the CVE and the reason a digest bump was not available in the
# comment, the deletion condition with its check, and `USER 10001:10001` restored below it.
#
# The cost of the build-work rule, recorded so it is a decision rather than an oversight: Spring
# Boot recommends an **AOT cache** for Java 25+ (`RUN java -XX:AOTCacheOutput=…`), which would cut
# startup time. Its output is architecture-specific, which is exactly the property that rules a
# `RUN` out here. Revisit when startup time is measured against the chart's probe budget rather
# than guessed at.

# Pinned by tag *and* digest, per Docker's best-practices guide: the tag says what it is, the digest
# says which one. Dependabot's `docker` ecosystem tracks both. This digest is the multi-arch
# manifest list, so it resolves per platform.
#
# **Liberica rather than Temurin, and this is a decision — ADR-017.** Temurin's JRE images are
# Ubuntu-based and carry `/usr/bin/pebble`, Canonical's service manager, as a Go binary compiled
# against a stdlib version that only moves when Temurin rebuilds. Every Go standard-library CVE
# therefore lands on us as a "fixable" finding we cannot fix, and `.trivyignore` grew from two
# entries to eight in three days waiving them (#492, #503). Nothing here runs pebble — but a waiver
# that grows on someone else's release cadence is a subscription, not a waiver.
#
# **The build JDK is still Temurin** (`.sdkmanrc`, `actions/setup-java`) and that split is
# deliberate: bytecode is vendor-neutral, both are OpenJDK builds, and the two choices answer
# different questions — reproducibility for the build, attack surface for the runtime.
#
# Alpine, so **musl rather than glibc**. Checked rather than assumed: nothing here depends on
# `netty-transport-native-*`, so Netty runs its NIO transport on both. The remaining musl difference
# that matters is DNS resolution, and the database is reached by hostname over the private network —
# which the k3d rehearsal and the chart's in-cluster `helm test` are what actually exercise.
FROM bellsoft/liberica-openjre-alpine:25@sha256:33b92304b3abdad54fb937224adcab07febad199180867d51be0fcd9da340ee4

# Supplied by the build; both default to something obviously wrong rather than to a plausible lie.
ARG VERSION=unspecified
ARG REVISION=unknown

# `org.opencontainers.image.source` is what links the published package to this repository on GHCR —
# and it is matched on the *canonical* name, so a stale URL means the package silently fails to
# attach rather than erroring. Combined into one instruction, per the best-practices guide.
LABEL org.opencontainers.image.source="https://github.com/enorm-labs/event-junkie" \
      org.opencontainers.image.title="event-junkie-bff" \
      org.opencontainers.image.description="Public read API for Event Junkie" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}"

# **A base-image CVE fix that has not arrived upstream, taken here instead.** CVE-2026-14456, an
# unbounded allocation in OpenSSL's QUIC server listener, is fixed in Alpine 3.24's `openssl
# 3.5.8-r0` for both architectures, but the Liberica base has not been rebuilt since it was pinned
# and still ships 3.5.7-r0 (#770). Not reachable — the JVM does not link libssl, only BusyBox's
# `ssl_client` and `libapk` do — but a fixable finding is fixed here rather than waived, and this
# line is what let `.trivyignore` go back to empty.
#
# **Delete this once the base ships 3.5.8-r0 or later**, by the same rule `gradle.properties`
# states for a BOM override: an upgrade kept past its purpose hides that the base has caught up.
# Check with `docker run --rm --entrypoint sh <ref> -c 'apk list --installed | grep -E "^libssl3"'`
# on the pinned digest. `USER root` because apk needs write access; the final `USER` below is what
# the container runs as, and is unaffected.
USER root
RUN apk upgrade --no-cache libssl3 libcrypto3

WORKDIR /application

# Ordered least- to most-frequently-changed, so a code-only rebuild reuses the dependency layers.
# Each COPY is one image layer; they merge into `application.jar` beside a single `lib/`.
#
# `spring-boot-loader/` and `snapshot-dependencies/` are empty with this extraction mode and are
# copied anyway: they cost nothing, they keep the four lines matching Spring Boot's documented
# order, and `snapshot-dependencies` stops being empty the moment a `-SNAPSHOT` third-party
# dependency appears.
COPY dependencies/ ./
COPY spring-boot-loader/ ./
COPY snapshot-dependencies/ ./
COPY application/ ./

# Numeric, and numeric on purpose: a name would need `RUN useradd`, which is the one thing this
# file must not contain. Kubernetes' `runAsNonRoot` check reads the numeric UID, so this satisfies
# it with no passwd entry — and it must match `security.runAsUser` in the Helm chart
# (deploy/charts/event-junkie/values.yaml). `scripts/uid-consistency.sh` asserts that they agree,
# because a mismatch is a pod that cannot read its own files and does not look like a values problem.
#
# **Above 10000, which is the whole of #448.** A low UID collides with an account that already
# exists on the host: if a container ever escapes its namespace, a process running as 1000 lands as
# *whatever 1000 is on the node*, which on a Debian-family host is the first human user. Nothing in
# the 10000+ range maps to anything, so the same escape lands as an identity with no files and no
# group memberships. It is defence in depth against the failure of one of the other controls, not a
# fix for a live hole — every pod already sets `runAsNonRoot`, `readOnlyRootFilesystem`,
# `allowPrivilegeEscalation: false`, drops all capabilities and mounts no ServiceAccount token.
#
# The files copied above are `root:root` mode 644, so they are readable by any UID — which is what
# makes this a one-line change here rather than a `chown`.
USER 10001:10001

# Documentation only — it publishes nothing. The application port and the management port that
# carries /actuator are both set by the chart via SERVER_PORT and MANAGEMENT_SERVER_PORT; these are
# the defaults from application.yaml.
EXPOSE 8080 9001

# Exec form, so java is PID 1 and receives SIGTERM directly — without it a shell would swallow the
# signal and every pod deletion would wait out the full termination grace period.
#
# No JAVA_TOOL_OPTIONS baked in: the chart sets it (`-XX:MaxRAMPercentage=75`), and a value fixed
# here would either be overridden confusingly or, on the command line, silently win over it.
ENTRYPOINT ["java", "-jar", "application.jar"]
