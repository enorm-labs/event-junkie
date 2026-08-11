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

# Pinned by tag *and* digest, per Docker's best-practices guide; Dependabot's `docker` ecosystem
# keeps the digest current, which is the half that otherwise silently rots.
FROM nginxinc/nginx-unprivileged:1.30-alpine@sha256:44e36330f74d4f3a1d4e222acca9e23b401fb87811a7597024502bb759c4dd49

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

# The base image defaults to UID 101. Overridden to 1000 to match `security.runAsUser` in the Helm
# chart, so one value covers all three workloads. It works because nginx here writes only to /tmp
# and reads only world-readable files; if a future base image changes that, the chart has a
# `frontend.runAsUser` override waiting rather than needing a redesign.
USER 1000:1000

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
