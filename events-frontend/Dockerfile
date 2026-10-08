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
# reasoning as events-bff/Dockerfile). The one `RUN` allowed is a named `apk upgrade`, which is not
# build work: it runs on the arm64 half under the emulation the runner carries (#770, #964).
# And `build-frontend.yml` already builds it with a cached node_modules.
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
# `libexpat >= 2.8.5-r0` (#1802), `libuuid >= 2.42.3-r1` (#1117) and `pcre2 >= 10.49-r0` (#2298),
# checked with
# `docker run --rm --entrypoint sh <ref> -c 'apk list | grep -E "^libssl3|^libexpat|^libuuid|^pcre2"'`
# and `trivy image <ref> --severity CRITICAL,HIGH --ignore-unfixed --ignorefile /dev/null`, both
# on `--platform linux/amd64`: Alpine builds each architecture separately, which is how #1118
# verified clean and changed nothing.
FROM nginxinc/nginx-unprivileged:1.31-alpine@sha256:b9241c6e7b8e9a862f129d8d4199ab64b10390949a78bdd5603379b32c844083

ARG VERSION=unspecified
ARG REVISION=unknown

LABEL org.opencontainers.image.source="https://github.com/enorm-labs/event-junkie" \
      org.opencontainers.image.title="event-junkie-frontend" \
      org.opencontainers.image.description="The Event Junkie web app, served as static files" \
      org.opencontainers.image.licenses="BUSL-1.1" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}"

# A base-image CVE fix that has not arrived upstream (#2883): CVE-2026-4775, a signed-integer
# overflow in libtiff that allows arbitrary code execution or denial of service, is fixed in Alpine
# 3.24's `tiff 4.7.2-r0`, but the base still ships 4.7.1-r0. A digest bump was not available —
# `1.31-alpine` already resolves to the digest pinned above. Not reachable either: `tiff` arrives
# through `libgd`, which the base's `nginx-module-image-filter` pulls in, and `docker/nginx.conf`
# loads no module and names no `image_filter` directive. But a fixable finding is fixed rather than
# waived, which is what keeps `.trivyignore` empty.
#
# Delete this once the base ships 4.7.2-r0 or later: an upgrade kept past its purpose hides that the
# base has caught up and becomes the thing choosing the version. Check with
# `docker run --rm --entrypoint sh <ref> -c 'apk list --installed | grep -E "^tiff"'` on the pinned
# digest. `USER 0` (root) because apk needs write access; the final `USER` below is unaffected.
USER 0
RUN apk upgrade --no-cache tiff

# Config first, bundle second, so the config layer stays cached.
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY dist/ /usr/share/nginx/html/

# The self-hosted map's tiles, fonts and icons (ADR-037). A data-only `FROM scratch` image, so this
# copies files and runs nothing. Refreshed by `publish-map-assets.yml`, whose summary prints this line.
COPY --from=ghcr.io/enorm-labs/event-junkie-map-assets:20261001@sha256:9cd081db9cca4455a77fde5bbcceabcdbf2c484097b9cd0ca947d15864e8b911 /map/ /usr/share/nginx/html/map/

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
