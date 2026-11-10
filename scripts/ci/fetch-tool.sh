#!/usr/bin/env bash
# Download a pinned tool release and verify its SHA-256 before anything uses it.
#
#   fetch-tool.sh <url> <sha256> <output-file>
#
# The checksums in the workflows come from each project's published checksum file (actionlint,
# gitleaks, osv-scanner), GitHub's release asset digest (zizmor) or Maven Central (detekt).
# Updating a tool means updating its URL and checksum together, in one reviewed change.
set -euo pipefail
url="$1"
sha="$2"
out="$3"
# Maven Central and GitHub sometimes answer runners with 429 (rate limited) or 5xx for a while.
# curl's default retry backoff (1, 2, 4 s) gives up within seconds, which failed whole jobs; wait
# 15 s between up to 6 tries instead (a Retry-After header from the server still takes precedence).
curl --fail --silent --show-error --location --proto '=https' --tlsv1.2 \
  --retry 6 --retry-delay 15 --retry-max-time 300 -o "$out" "$url"
echo "$sha  $out" | sha256sum --check --strict --quiet || {
  echo "::error title=Tool checksum mismatch::$(basename "$out") from $url does not match the pinned SHA-256. Not using it."
  rm -f "$out"
  exit 1
}
