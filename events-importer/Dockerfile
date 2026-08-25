# events-importer — the scrapers, the scheduler, and the admin API.
#
# Build context is `events-importer/build/docker`, NOT the module directory:
#
#     ./gradlew :events-importer:bootJarLayers
#     docker buildx build -f events-importer/Dockerfile events-importer/build/docker \
#       --platform linux/amd64,linux/arm64 --build-arg VERSION=… --build-arg REVISION=…
#
# The structure below is identical to `events-bff/Dockerfile`, and the reasoning for every line of
# it — why there is no `RUN` and no builder stage, why the base image is pinned by digest, why the
# user is numeric, why the AOT cache is deliberately not used — is written out there. Read that one
# before changing either.
#
# The two files are kept separate rather than generated from a shared template: they differ in the
# labels and the exposed port, a template would need parameters for both, and a reviewer expects to
# find a service's Dockerfile next to its source.

# Liberica rather than Temurin — ADR-017, and the reasoning is set out at length in
# `events-bff/Dockerfile`. In short: Temurin's Ubuntu-based JRE carries `/usr/bin/pebble`, whose Go
# stdlib only moves when Temurin rebuilds, so every Go CVE arrived as a finding marked fixable that
# we could not fix (#492). The build JDK stays Temurin; only the runtime moved.
#
# **The two backend images must pin the same base**, which is also why Dependabot groups all three
# Dockerfiles into one PR — see `.github/dependabot.yml`.
FROM bellsoft/liberica-openjre-alpine:25@sha256:33b92304b3abdad54fb937224adcab07febad199180867d51be0fcd9da340ee4

ARG VERSION=unspecified
ARG REVISION=unknown

LABEL org.opencontainers.image.source="https://github.com/enorm-labs/event-junkie" \
      org.opencontainers.image.title="event-junkie-importer" \
      org.opencontainers.image.description="Scrapers, scheduling and the admin API for Event Junkie" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}"

WORKDIR /application

COPY dependencies/ ./
COPY spring-boot-loader/ ./
COPY snapshot-dependencies/ ./
COPY application/ ./

# Numeric and above 10000 — see events-bff/Dockerfile for the full reasoning, and #448. In short: a
# UID below the host's own user range lands as a real account if a container ever escapes its
# namespace, and nothing in the 10000+ range maps to anything. `scripts/uid-consistency.sh` asserts
# this matches `security.runAsUser` in the chart.
USER 10001:10001

# 8081 is this module's `server.port`; 9001 is the management port the chart moves actuator to.
# Neither is reachable from outside the cluster — the importer has no ingress at all, by design.
EXPOSE 8081 9001

ENTRYPOINT ["java", "-jar", "application.jar"]
