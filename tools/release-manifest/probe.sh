#!/bin/sh
# probe.sh <id> <tag> — is this version already on its registry? (ADR 29.9.26v D6)
#
# Prints `published` when every URL `release-manifest probe` names answers 200,
# `absent` when any answers 404, and fails on any other status, so a registry
# fault never reads as "publish". crates.io's API refuses a request with no
# User-Agent (403), so one is always sent.
set -eu

id=${1:?usage: probe.sh <id> <tag>}
tag=${2:?usage: probe.sh <id> <tag>}

if command -v release-manifest >/dev/null 2>&1; then
    urls=$(release-manifest probe "$id" "$tag")
else
    urls=$(cargo run -q -p release-manifest -- probe "$id" "$tag")
fi

state=published
for url in $urls; do
    status=$(curl -s -o /dev/null -w '%{http_code}' -A 'lingara-release (https://getlingara.com)' "$url")
    case "$status" in
        200) ;;
        404) state=absent ;;
        *) echo "probe: $url answered $status; neither published (200) nor absent (404)" >&2; exit 1 ;;
    esac
done
echo "$state"
