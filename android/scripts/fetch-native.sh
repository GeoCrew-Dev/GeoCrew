#!/usr/bin/env bash
# Puts the GeoCrew-Native AAR the app builds against at android/native/geocrew-native.aar:
# the artifact of the GeoCrew-Native CI run named in native.properties, checked
# against the SHA-256 there (ADR 0016). Needs gh, signed in (GH_TOKEN in CI).
# To try a GeoCrew-Native change, set GEOCREW_NATIVE_AAR to a locally built AAR.
set -euo pipefail
cd "$(dirname "$0")/.."
out=native/geocrew-native.aar
mkdir -p native
if [ -n "${GEOCREW_NATIVE_AAR:-}" ]; then
    cp "$GEOCREW_NATIVE_AAR" "$out"
    echo "using $GEOCREW_NATIVE_AAR, not the build in native.properties"
    exit 0
fi
run=$(sed -n 's/^run=//p' native.properties | tr -d '\r')
sha=$(sed -n 's/^sha256=//p' native.properties | tr -d '\r')
if [ -f "$out" ] && [ "$(sha256sum "$out" | cut -c1-64)" = "$sha" ]; then
    exit 0 # already there
fi
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
gh run download "$run" -R GeoCrew-Dev/GeoCrew-Native -n geocrew-native-android -D "$tmp"
aar=$(find "$tmp" -name '*.aar' | head -n 1)
got=$(sha256sum "$aar" | cut -c1-64)
if [ "$got" != "$sha" ]; then
    echo "the AAR of run $run has SHA-256 $got, not $sha" >&2
    exit 1
fi
mv "$aar" "$out"
echo "GeoCrew-Native AAR of run $run is in android/$out"
