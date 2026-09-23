# events-bff: the public read API. Build context is `events-bff/build/docker`, NOT the module
# directory:
#
#     ./gradlew :events-bff:bootJarLayers
#     docker buildx build -f events-bff/Dockerfile events-bff/build/docker \
#       --platform linux/amd64,linux/arm64 --build-arg VERSION=… --build-arg REVISION=…
#
# That context is exactly the extracted layers, so it needs no `.dockerignore`.
#
# Deliberately no builder stage and no `RUN` that does build work; the one `RUN` allowed is a
# named `apk upgrade`, under the rule below. Spring Boot's reference Dockerfile extracts the
# layered jar in a builder stage; here Gradle does it (`bootJarLayers`), because build work in a
# `RUN` produces architecture-specific output and a two-platform build would need a runner per
# architecture. A JVM jar is architecture-independent, so one runner emits both platforms.
#
# A package upgrade is not build work (#770): `apk upgrade <pkg>` on the arm64 half runs under
# the emulation the `ubuntu-latest` runner carries (#964, #1118, about two seconds in the log).
# If a runner image drops it, the publish fails loudly. Such a layer names one package, the CVE,
# why a digest bump was not available, the deletion condition with its check, and restores
# `USER 10001:10001` below it.
#
# The cost of that rule: Spring Boot's AOT cache for Java 25+ (`RUN java -XX:AOTCacheOutput=…`)
# is architecture-specific, so it is not used. Measured in #434: it halves startup, which was never
# slow, and barely shortens warm-up under load. A second CPU in the chart's limit removes that.

# Pinned by tag and digest; Dependabot's `docker` ecosystem tracks both, and the digest is the
# multi-arch manifest list.
#
# Liberica rather than Temurin (ADR-017): Temurin's JRE images carry `/usr/bin/pebble`, a Go
# binary whose stdlib only moves when Temurin rebuilds, so every Go CVE lands as a "fixable"
# finding we cannot fix, and `.trivyignore` grew from two entries to eight in three days (#492,
# #503). The build JDK is still Temurin (`.sdkmanrc`, `actions/setup-java`): bytecode is
# vendor-neutral, and the two choices answer reproducibility and attack surface respectively.
#
# Alpine, so musl: nothing here depends on `netty-transport-native-*`, so Netty runs NIO on
# both. The musl difference that matters is DNS, which the k3d rehearsal and the chart's
# `helm test` exercise.
FROM bellsoft/liberica-openjre-alpine:25@sha256:33b92304b3abdad54fb937224adcab07febad199180867d51be0fcd9da340ee4

# Supplied by the build; both default to something obviously wrong rather than a plausible lie.
ARG VERSION=unspecified
ARG REVISION=unknown

# `org.opencontainers.image.source` links the published package to this repository on GHCR,
# matched on the canonical name, so a stale URL silently fails to attach.
LABEL org.opencontainers.image.source="https://github.com/enorm-labs/event-junkie" \
      org.opencontainers.image.title="event-junkie-bff" \
      org.opencontainers.image.description="Public read API for Event Junkie" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}"

# A base-image CVE fix that has not arrived upstream (#770): CVE-2026-14456, an unbounded
# allocation in OpenSSL's QUIC listener, is fixed in Alpine 3.24's `openssl 3.5.8-r0`, but the
# Liberica base still ships 3.5.7-r0. Not reachable (the JVM does not link libssl), but a
# fixable finding is fixed rather than waived, and this line let `.trivyignore` go back to empty.
#
# Delete this once the base ships 3.5.8-r0 or later, by the BOM-override rule: an upgrade kept
# past its purpose hides that the base has caught up. Check with
# `docker run --rm --entrypoint sh <ref> -c 'apk list --installed | grep -E "^libssl3"'` on the
# pinned digest. `USER root` because apk needs write access; the final `USER` below is unaffected.
USER root
RUN apk upgrade --no-cache libssl3 libcrypto3

WORKDIR /application

# Ordered least- to most-frequently-changed, so a code-only rebuild reuses the dependency layers.
# `spring-boot-loader/` and `snapshot-dependencies/` are empty with this extraction mode and
# copied anyway: they keep Spring Boot's documented order, and the latter stops being empty the
# moment a `-SNAPSHOT` third-party dependency appears.
COPY dependencies/ ./
COPY spring-boot-loader/ ./
COPY snapshot-dependencies/ ./
COPY application/ ./

# Numeric on purpose: a name would need `RUN useradd`. Kubernetes' `runAsNonRoot` reads the
# numeric UID, and it must match `security.runAsUser` in the Helm chart;
# `scripts/uid-consistency.sh` asserts that, because a mismatch is a pod that cannot read its
# own files.
#
# Above 10000 (#448): a low UID collides with an account on the host, so a container escape as
# 1000 lands as the first human user on a Debian-family node, while nothing in the 10000+ range
# maps to anything. Defence in depth beside `runAsNonRoot`, `readOnlyRootFilesystem`,
# `allowPrivilegeEscalation: false`, dropped capabilities and no ServiceAccount token. The files
# above are `root:root` mode 644, so no `chown`.
USER 10001:10001

# Documentation only. The chart sets SERVER_PORT and MANAGEMENT_SERVER_PORT; these are
# application.yaml's defaults.
EXPOSE 8080 9001

# Exec form, so java is PID 1 and receives SIGTERM directly; a shell would swallow it and every
# pod deletion would wait out the grace period. No JAVA_TOOL_OPTIONS baked in: the chart sets it
# (the heap share and the direct-memory cap, #1766).
ENTRYPOINT ["java", "-jar", "application.jar"]
