# events-frontend: the Vue 3 SPA, served as static files by nginx. Build context is the module
# directory, and `npm run build` must have run first:
#
#     npm --prefix events-frontend ci && npm --prefix events-frontend run build
#     docker buildx build events-frontend --platform linux/amd64,linux/arm64 \
#       --build-arg VERSION=… --build-arg REVISION=…
#
# `.dockerignore` cuts the context down to `dist/` and `docker/`.
#
# Deliberately no builder stage and no `RUN` that does build work, unlike every guide: build work
# in a `RUN` executes target-architecture code, so a two-platform image would need a runner per
# architecture plus a manifest merge, while `dist/` is identical on amd64 and arm64 (the same
# reasoning as events-bff/Dockerfile). The one `RUN` allowed is a named `apk upgrade`, when the base
# lags a fix: it is not build work, and the arm64 half runs under the runner's emulation (#770, #964).
# And `build-frontend.yml` already builds it with a cached node_modules.
#
# The base image is the unprivileged variant: it listens on 8080, and its nginx.conf relocates the
# pid file and every temp path into /tmp (docker/nginx.conf).

# Pinned by tag and digest; Dependabot's `docker` ecosystem updates both halves.
#
# GHCR, not Docker Hub: `nginxinc/nginx-unprivileged:1.31-alpine` on Docker Hub stayed at nginx 1.31.7
# while the same project published 1.31.10 to GHCR, so Dependabot had nothing to propose.
#
# The pin has to be on a branch that is still being rebuilt: a superseded mainline keeps shipping old
# Alpine packages (#264). An open Dependabot PR is a live vulnerability. Mainline (1.31) picks up
# refreshed Alpine packages sooner; 1.30-alpine is the conservative alternative.
#
# **The image scan does not see nginx itself.** The package comes from nginx.org, and Trivy reported
# none of the 1.31.9 fixes against 1.31.7 (CVE-2026-123007 and others). Compare the version with
# https://nginx.org/en/security_advisories.html: this pin must satisfy `nginx >= 1.31.10`.
#
# Verify the package, never the digest: #770 was triaged against a digest that had moved while
# still shipping the vulnerable openssl. This pin must also satisfy `libssl3 >= 3.5.8-r0` (#770),
# `libexpat >= 2.10.0-r0` (#1802, #3537, #4417), `libuuid >= 2.42.3-r1` (#1117),
# `pcre2 >= 10.49-r0` (#2298), `tiff >= 4.7.2-r0` (#2883), `libx11 >= 1.8.14-r0` (CVE-2026-88806),
# `brotli-libs >= 1.2.1-r0` (CVE-2026-111497), `curl`/`libcurl >= 8.23.0-r0` (CVE-2026-92392),
# `zlib >= 1.3.2-r1` (CVE-2026-85091), `musl`/`musl-utils >= 1.2.6-r3` (CVE-2026-107891) and
# `libxslt >= 1.1.43-r4` (CVE-2026-87636, CVE-2026-9947) and `libxml2 >= 2.13.9-r3`
# (CVE-2026-86144), checked with
# `docker run --rm --entrypoint sh <ref> -c 'apk list | grep -E "^nginx-1|^libssl3|^libexpat|^libuuid|^pcre2|^tiff|^libx11|^brotli-libs|^curl|^libcurl|^zlib|^musl|^libxslt|^libxml2"'`
# and `trivy image <ref> --severity CRITICAL,HIGH --ignore-unfixed --ignorefile /dev/null`, both
# on `--platform linux/amd64`: Alpine builds each architecture separately, which is how #1118
# verified clean and changed nothing.
FROM ghcr.io/nginx/nginx-unprivileged:1.31-alpine@sha256:86628f2c9eaa5e84e3b98d25e26563c4a4f9b0ead9cf5656da4731d86e0621de

ARG VERSION=unspecified
ARG REVISION=unknown

LABEL org.opencontainers.image.source="https://github.com/enorm-labs/event-junkie" \
      org.opencontainers.image.title="event-junkie-frontend" \
      org.opencontainers.image.description="The Event Junkie web app, served as static files" \
      org.opencontainers.image.licenses="BUSL-1.1" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}"

# Config first, bundle second, so the config layer stays cached.
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY dist/ /usr/share/nginx/html/

# The self-hosted map's tiles, fonts and icons (ADR-037). A data-only `FROM scratch` image, so this
# copies files and runs nothing. Refreshed by `publish-map-assets.yml`, whose summary prints this line.
COPY --from=ghcr.io/enorm-labs/event-junkie-map-assets:20261001@sha256:9cd081db9cca4455a77fde5bbcceabcdbf2c484097b9cd0ca947d15864e8b911 /map/ /usr/share/nginx/html/map/

# UID 10001 to match the chart's `security.runAsUser` (`scripts/uid-consistency.sh`), above
# 10000 per #448. Verified rather than assumed, since `nginx-unprivileged` is built
# around one user: at 10001 with `--read-only --tmpfs /tmp` it starts clean and serves 200,
# because nginx writes only to /tmp and reads only world-readable files. The chart keeps a
# `frontend.runAsUser` override for a base image that changes that.
USER 10001:10001

# Documentation only. Must stay above 1024, the constraint on the chart's `frontend.service.port`.
EXPOSE 8080

# No HEALTHCHECK: Kubernetes uses the chart's probes, and a second definition of healthy is one
# nobody maintains. Exec form, bypassing the base image's docker-entrypoint.sh, whose envsubst
# templating into /etc/nginx/conf.d fails on a read-only root filesystem.
ENTRYPOINT ["nginx", "-g", "daemon off;"]
