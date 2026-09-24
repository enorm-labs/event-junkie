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
# reasoning as events-bff/Dockerfile, whose one exception, a named `apk upgrade`, this file does
# not need). And `build-frontend.yml` already builds it with a cached node_modules.
#
# The base image is the unprivileged variant: it listens on 8080, and its nginx.conf relocates the
# pid file and every temp path into /tmp (docker/nginx.conf).

# Pinned by tag and digest; Dependabot's `docker` ecosystem updates both halves.
#
# The pin has to be on a branch that is still being rebuilt: `1.29-alpine`, a superseded mainline
# last rebuilt 2026-05-11, kept shipping Alpine 3.23.4 packages with 10 fixable HIGH advisories
# after 3.24 fixed them (#264), while Dependabot's bump PR #437 sat open. An open Dependabot PR is
# a live vulnerability, and the scan is what makes it blocking. Mainline (1.31) picks up refreshed
# Alpine packages sooner, so two digest bumps in a fortnight are the design working; 1.30-alpine
# is the conservative alternative.
#
# Verify the package, never the digest: #770 was triaged against a digest that had moved while
# still shipping the vulnerable openssl. This pin must satisfy `libssl3 >= 3.5.8-r0` (#770),
# `libexpat >= 2.8.5-r0` (#1802) and `libuuid >= 2.42.3-r1` (#1117), checked with
# `docker run --rm --entrypoint sh <ref> -c 'apk list | grep -E "^libssl3|^libexpat|^libuuid"'`
# and `trivy image <ref> --severity CRITICAL,HIGH --ignore-unfixed --ignorefile /dev/null`, both
# on `--platform linux/amd64`: Alpine builds each architecture separately, which is how #1118
# verified clean and changed nothing. The one `RUN` allowed is a named `apk upgrade`, under the
# same rule as events-bff/Dockerfile: it names one package, the CVE, why a digest bump was not
# available, and the deletion condition with its check.
FROM nginxinc/nginx-unprivileged:1.31-alpine@sha256:b54ac358b83fc6c965793fd271839b4ea4cdb6e99895bb19618cbc2ca152d972

ARG VERSION=unspecified
ARG REVISION=unknown

LABEL org.opencontainers.image.source="https://github.com/enorm-labs/event-junkie" \
      org.opencontainers.image.title="event-junkie-frontend" \
      org.opencontainers.image.description="The Event Junkie web app, served as static files" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}"

# A base-image CVE fix that has not arrived upstream (#1802): CVE-2026-93990, XML injection via
# malformed UTF-16 input, is fixed in Alpine 3.24's `expat 2.8.5-r0`, and the base still installs
# 2.8.4-r0. A digest bump does not help — the newest `1.31-alpine` ships the same package, with
# 2.8.5-r0 only upgradable — so the pin stays where Dependabot put it. nginx does not parse XML
# from a request here, but a fixable finding is fixed rather than waived, and the scan is blocking.
#
# Delete this once the base installs 2.8.5-r0 or later: an upgrade kept past its purpose hides
# that the base has caught up. Check with `docker run --rm --entrypoint sh <ref> -c 'apk list
# --installed | grep -E "^libexpat"'` on the pinned digest, on `--platform linux/amd64`.
# `USER root` because apk needs write access; the final `USER` below is unaffected.
USER root
RUN apk upgrade --no-cache libexpat

# Config first, bundle second, so the config layer stays cached.
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY dist/ /usr/share/nginx/html/

# UID 10001 to match the chart's `security.runAsUser` (`scripts/uid-consistency.sh`), above
# 10000 per #448. Verified rather than assumed, since `nginxinc/nginx-unprivileged` is built
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
