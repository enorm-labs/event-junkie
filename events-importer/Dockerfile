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

FROM eclipse-temurin:25-jre@sha256:a214efa3200af4b657e41935799aa12d7aee3336fdb42eb505a0948f6ecdd983

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

USER 1000:1000

# 8081 is this module's `server.port`; 9001 is the management port the chart moves actuator to.
# Neither is reachable from outside the cluster — the importer has no ingress at all, by design.
EXPOSE 8081 9001

ENTRYPOINT ["java", "-jar", "application.jar"]
