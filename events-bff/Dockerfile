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
# THERE IS DELIBERATELY NO `RUN` INSTRUCTION IN THIS FILE, and no builder stage.
#
# Spring Boot's reference Dockerfile extracts the layered jar in a builder stage with
# `RUN java -Djarmode=tools … extract`. The layout below is identical to what that produces — the
# extraction just happens in Gradle instead (`bootJarLayers` in the root build). The reason is
# multi-arch: a `RUN` executes target-architecture code, so a two-platform build needs either QEMU
# emulation or a runner per architecture plus a manifest merge. A JVM jar is architecture-
# independent, so with no `RUN` at all one runner emits both platforms and only the base image
# layers differ.
#
# The cost, recorded so it is a decision rather than an oversight: Spring Boot recommends an **AOT
# cache** for Java 25+ (`RUN java -XX:AOTCacheOutput=…`), which would cut startup time. It needs a
# `RUN`, and the cache it produces is architecture-specific, so it is incompatible with the above.
# Revisit when startup time is measured against the chart's probe budget rather than guessed at.

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
FROM bellsoft/liberica-openjre-alpine:26@sha256:ce634a75fedc74e9c54507d1fc731e8ba9e240bd3282598b6c1ca571a2bfb376

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
# (deploy/charts/event-junkie/values.yaml), which is 1000.
USER 1000:1000

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
