#!/usr/bin/env bash
#
# map-assets.sh — the self-hosted map: Berlin's vector tiles, the label fonts and the icon sprites (#357).
#
# Usage:
#   scripts/map-assets.sh build <out-dir> [YYYYMMDD]   # cut a fresh set from Protomaps into <out-dir>/map/
#   scripts/map-assets.sh dev                          # copy the pinned image's set into events-frontend/public/map/
#
# `build` requires curl, jq, unzip or tar, and reaches build.protomaps.com and github.com. The date
# picks a Protomaps daily build; the default is the newest. `dev` requires docker and pulls the image
# that `events-frontend/Dockerfile` copies from, so local development renders the deployed map.
#
# **Why an image and not a download at build time.** Protomaps keeps a daily build for a week, so a
# URL pinned by date stops resolving within days. `publish-map-assets.yml` runs `build` and pushes the
# result to GHCR as a `FROM scratch` image, and the frontend Dockerfile copies `/map/` out of it,
# pinned by digest. A refresh is that workflow plus a one-line digest bump.
#
# **The bounding box is the importer's validation range** (`VenueRequest.kt`, `DecimalMin` and
# `DecimalMax`), so every venue that can be stored is on the map. Zoom 14 is the last level cut;
# MapLibre over-zooms vector tiles past it, and zoom 15 costs 82 MB against 31 MB for no venue-level
# difference.
#
# What ships, and the licence each carries (LEGAL.md §9): the tiles are an ODbL Produced Work of
# OpenStreetMap, credited on the map itself; the fonts are Noto Sans under the SIL OFL, whose text
# ships beside them; the sprites derive from MIT-licensed tangrams/icons, whose notice ships too.

set -euo pipefail

case "${1:-}" in
  -h | --help) awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"; exit 0 ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

readonly BBOX=13.05,52.30,13.80,52.70
readonly MAX_ZOOM=14
readonly PMTILES_VERSION=1.31.2
readonly ASSETS_COMMIT=028c18f713baecad011301ff7a69acc39bcc2ae7
readonly ASSETS_SHA256=57e40e8c512bd8042d0a3a251f19d0d1c8523ad963c666c3c6643bada4dc92d0
# The sprites' MIT notice, which basemaps-assets names but does not ship.
readonly ICONS_LICENSE_URL=https://raw.githubusercontent.com/tangrams/icons/92510779634f4a006c61ea70e50cb8c52c765a81/LICENSE.md
readonly ICONS_LICENSE_SHA256=46d0ca73c10d7366ef7bf3932d8508267096393ccc9ef3a41d1b1d1fe37023f1
readonly BUILDS_INDEX=https://build-metadata.protomaps.dev/builds.json

die() {
  printf 'map-assets.sh: %s\n' "$1" >&2
  exit 1
}

sha256() {
  if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

# The release asset for this machine, and its digest from the GitHub release page.
pmtiles_asset() {
  case "$(uname -s)_$(uname -m)" in
    Linux_x86_64) echo "go-pmtiles_${PMTILES_VERSION}_Linux_x86_64.tar.gz 3ed7dbf4ec2e6dfe5e25b6f70d1ffc932729f93c86db353bf514dd71010a312f" ;;
    Linux_aarch64 | Linux_arm64) echo "go-pmtiles_${PMTILES_VERSION}_Linux_arm64.tar.gz f8bd47e7ea866863489cad588fbaf2f31f42e5821f7a03f009b3769f05801cb1" ;;
    Darwin_arm64) echo "go-pmtiles-${PMTILES_VERSION}_Darwin_arm64.zip 40528f7f616fcbf91207cd48c8fc023d213f6d86c0cbf1f748732803d1880f3d" ;;
    Darwin_x86_64) echo "go-pmtiles-${PMTILES_VERSION}_Darwin_x86_64.zip 1f0dc02eee6c58312dd6c509faee1b5c32f0596568af1bf51f1b034e7a88a65b" ;;
    *) die "no pinned go-pmtiles build for $(uname -s) $(uname -m)" ;;
  esac
}

fetch_verified() {
  local url="$1" file="$2" expected="$3"
  curl -fsSL --retry 3 -o "$file" "$url"
  [ "$(sha256 "$file")" = "$expected" ] || die "checksum mismatch for $url"
}

build() {
  local out="${1:-}" date="${2:-}"
  [ -n "$out" ] || die "build needs an output directory"
  for tool in curl jq; do command -v "$tool" >/dev/null || die "$tool is required but not on PATH"; done

  # Global, not local: the EXIT trap runs after this function has returned.
  work="$(mktemp -d)"
  trap 'rm -rf "$work"' EXIT

  read -r asset digest <<<"$(pmtiles_asset)"
  fetch_verified "https://github.com/protomaps/go-pmtiles/releases/download/v${PMTILES_VERSION}/${asset}" "$work/$asset" "$digest"
  case "$asset" in
    *.zip) unzip -oq "$work/$asset" -d "$work" ;;
    *) tar xzf "$work/$asset" -C "$work" ;;
  esac

  local builds build_key
  builds="$(curl -fsSL --retry 3 "$BUILDS_INDEX")"
  if [ -n "$date" ]; then
    build_key="${date}.pmtiles"
    jq -e --arg k "$build_key" 'any(.[]; .key == $k)' <<<"$builds" >/dev/null || die "Protomaps has no build $build_key (they are kept for a week)"
  else
    build_key="$(jq -r 'max_by(.key) | .key' <<<"$builds")"
  fi

  mkdir -p "$out/map"
  "$work/pmtiles" extract "https://build.protomaps.com/${build_key}" "$out/map/berlin.pmtiles" --bbox="$BBOX" --maxzoom="$MAX_ZOOM"

  fetch_verified "https://github.com/protomaps/basemaps-assets/archive/${ASSETS_COMMIT}.tar.gz" "$work/assets.tar.gz" "$ASSETS_SHA256"
  tar xzf "$work/assets.tar.gz" -C "$work"
  local assets="$work/basemaps-assets-${ASSETS_COMMIT}"
  rm -rf "$out/map/fonts" "$out/map/sprites"
  mkdir -p "$out/map/fonts" "$out/map/sprites"
  for font in 'Noto Sans Regular' 'Noto Sans Medium' 'Noto Sans Italic'; do
    cp -R "$assets/fonts/$font" "$out/map/fonts/"
  done
  cp "$assets/fonts/OFL.txt" "$out/map/fonts/"
  for theme in light dark; do
    cp "$assets/sprites/v4/${theme}.json" "$assets/sprites/v4/${theme}.png" \
      "$assets/sprites/v4/${theme}@2x.json" "$assets/sprites/v4/${theme}@2x.png" "$out/map/sprites/"
  done
  fetch_verified "$ICONS_LICENSE_URL" "$out/map/sprites/LICENSE.md" "$ICONS_LICENSE_SHA256"

  jq -n --arg build "$build_key" --arg bbox "$BBOX" --argjson maxzoom "$MAX_ZOOM" --arg assets "$ASSETS_COMMIT" \
    '{protomapsBuild: $build, bbox: $bbox, maxZoom: $maxzoom, basemapsAssetsCommit: $assets}' >"$out/map/source.json"
  printf 'map-assets.sh: wrote %s/map from %s (%s)\n' "$out" "$build_key" "$(du -sh "$out/map" | cut -f1)"
}

# The image reference is read from the Dockerfile, so the two cannot name different sets.
dev() {
  command -v docker >/dev/null || die "docker is required but not on PATH"
  local image target="$REPO_ROOT/events-frontend/public/map" container
  image="$(sed -n 's/^COPY --from=\(ghcr\.io\/[^ ]*map-assets[^ ]*\) .*/\1/p' "$REPO_ROOT/events-frontend/Dockerfile")"
  [ -n "$image" ] || die "events-frontend/Dockerfile names no map-assets image"
  docker pull --quiet "$image" >/dev/null
  container="$(docker create "$image" /none)"
  rm -rf "$target"
  docker cp "$container:/map" "$target"
  docker rm "$container" >/dev/null
  printf 'map-assets.sh: copied %s into %s\n' "$image" "$target"
}

case "${1:-}" in
  build) build "${2:-}" "${3:-}" ;;
  dev) dev ;;
  *) die "unknown command '${1:-}' (try --help)" ;;
esac
