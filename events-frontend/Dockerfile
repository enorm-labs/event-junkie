# events-frontend — the Vue 3 SPA, served as static files by nginx.
#
# Build context is the module directory, and `npm run build` must have run first:
#
#     npm --prefix events-frontend ci && npm --prefix events-frontend run build
#     docker buildx build events-frontend --platform linux/amd64,linux/arm64 \
#       --build-arg VERSION=… --build-arg REVISION=…
#
# `.dockerignore` next to this file cuts the context down to `dist/` and `docker/` — without it the
# context would carry node_modules, coverage and playwright-report.
#
# THERE IS DELIBERATELY NO BUILDER STAGE AND NO `RUN`, which is where this departs from every guide
# on the subject — Vue CLI's Docker/nginx page, and the community write-ups, all run
# `RUN npm run build` inside a first stage. Two reasons, and the first is the load-bearing one:
#
#  1. **Multi-arch.** A `RUN` executes target-architecture code, so a two-platform image needs QEMU
#     or a runner per architecture plus a manifest merge. `dist/` is just text — it is identical on
#     amd64 and arm64 — so with no `RUN` at all a single runner emits both platforms. Same
#     reasoning as events-bff/Dockerfile.
#  2. **CI already builds it.** `build-frontend.yml` runs `npm ci && npm run build` with a cached
#     node_modules before it ever reaches this file. A builder stage here would be a second,
#     uncached build of an artefact that already exists.
#
# The base image is the unprivileged variant, not `nginx:alpine`. It listens on 8080 rather than 80
# (a non-root process cannot bind a privileged port), and its nginx.conf already relocates the pid
# file and every temp path into /tmp. See docker/nginx.conf for why that matters.

# Pinned by tag *and* digest, per Docker's best-practices guide. Dependabot's `docker` ecosystem
# updates both halves — the digest within a tag, and the tag itself.
#
# **The pin has to be on a branch that is still being rebuilt.** This was `1.29-alpine` until #264's
# Trivy gate reported 10 fixable HIGH advisories on its first run — openssl, curl, libxml2, libexpat,
# c-ares, all in the Alpine base rather than in nginx. nginx 1.29 is a superseded *mainline* branch,
# last rebuilt 2026-05-11, so it kept shipping Alpine 3.23.4 packages while 3.24 fixed them.
#
# **Dependabot had already noticed** — PR #437 proposed 1.29-alpine → 1.30-alpine on 2026-08-11 and
# was sitting open when the scan ran. That is the useful part: an open Dependabot PR is a live
# vulnerability until someone merges it, and nothing in its title distinguishes ten fixable HIGH
# advisories from a routine version bump. The scan is what makes the difference blocking rather than
# advisory. (It was also unwatched for the three months before that: the `docker` ecosystem only
# started covering this directory on 2026-08-11.)
#
# 1.31 is the current mainline and is rebuilt within days (Alpine 3.24.1, Trivy-clean at the time of
# the bump). 1.30-alpine — the stable branch, and what #437 proposed — was equally clean and is the
# conservative alternative if mainline ever churns too fast. The reason to stay on mainline is that
# it picks up refreshed Alpine packages sooner, which is exactly the failure above.
FROM nginxinc/nginx-unprivileged:1.31-alpine@sha256:d9083fe47768377ef55dedafd67d4da7c2f2bc2bece7554954f29359deb0dce9

ARG VERSION=unspecified
ARG REVISION=unknown

LABEL org.opencontainers.image.source="https://github.com/enorm-labs/event-junkie" \
      org.opencontainers.image.title="event-junkie-frontend" \
      org.opencontainers.image.description="The Event Junkie web app, served as static files" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}"

# Config first, bundle second: the config changes rarely and the bundle changes every build, so
# this ordering keeps the config layer cached.
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY dist/ /usr/share/nginx/html/

# The base image defaults to UID 101. Overridden to 10001 to match `security.runAsUser` in the Helm
# chart, so one value covers all three workloads; `scripts/uid-consistency.sh` asserts they agree.
# Above 10000 per #448 — see events-bff/Dockerfile for why a low UID is worth avoiding.
#
# **This was the image expected to resist**, because `nginxinc/nginx-unprivileged` is built around a
# specific unprivileged user and writes to `/tmp` paths owned by it, and an arbitrary `runAsUser`
# would fail at start rather than at build. Verified instead of assumed: at 10001 with
# `--read-only --tmpfs /tmp` it starts clean, logs no permission errors and serves 200. It works
# because nginx here writes only to /tmp — a tmpfs owned by whoever mounts it — and reads only
# world-readable files. If a future base image changes that, the chart still has a
# `frontend.runAsUser` override waiting rather than needing a redesign.
USER 10001:10001

# Documentation only. It must stay above 1024 — a non-root process cannot bind a privileged port,
# which is the constraint this image places on the chart's `frontend.service.port`.
EXPOSE 8080

# No HEALTHCHECK on purpose: Kubernetes ignores it and uses the chart's own probes, so adding one
# would create a second definition of healthy that nothing reads and nobody maintains.
#
# Exec form, and the base image's docker-entrypoint.sh is deliberately bypassed: those scripts do
# envsubst templating into /etc/nginx/conf.d, which fails on a read-only root filesystem. There are
# no templates here to expand.
ENTRYPOINT ["nginx", "-g", "daemon off;"]
