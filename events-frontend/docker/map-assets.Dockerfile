# The self-hosted map's files, as an image the frontend Dockerfile copies `/map/` out of (#357).
# Built by `publish-map-assets.yml` from the directory `scripts/map-assets.sh build` writes, which is
# the whole build context. `FROM scratch`: no shell, no packages, nothing for a scanner to find.
FROM scratch

ARG PROTOMAPS_BUILD=unknown

LABEL org.opencontainers.image.source="https://github.com/enorm-labs/event-junkie" \
      org.opencontainers.image.title="event-junkie-map-assets" \
      org.opencontainers.image.description="Berlin vector tiles (OpenStreetMap via Protomaps), Noto Sans glyphs and map sprites for the Event Junkie map" \
      org.opencontainers.image.licenses="ODbL-1.0 AND OFL-1.1 AND MIT" \
      org.opencontainers.image.version="${PROTOMAPS_BUILD}"

COPY map/ /map/
