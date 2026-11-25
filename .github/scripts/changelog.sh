#!/usr/bin/env bash
# Release notes for a component tag from the Conventional Commits that touched
# the component's directory since the previous tag of the same component.
#
#   .github/scripts/changelog.sh server-v0.3.0 server
set -euo pipefail
tag=$1
dir=$2
prefix="${tag%%v[0-9]*}v" # server-v0.3.0 -> server-v
if [[ "${tag#"$prefix"}" == *-* ]]; then
  prev=$(git describe --tags --abbrev=0 --match "${prefix}*" "${tag}^" 2>/dev/null || true)
else # a final release counts from the previous final release, not its rc
  prev=$(git describe --tags --abbrev=0 --match "${prefix}*" --exclude "${prefix}*-*" "${tag}^" 2>/dev/null || true)
fi
range=${prev:+$prev..}$tag
section() {
  local title=$1 pattern=$2 lines
  lines=$(git log --no-merges --format='%s' "$range" -- "$dir" | grep -E "$pattern" | sed -E 's/^[a-z]+(\(([^)]*)\))?!?: /- **\2** /; s/- \*\*\*\* /- /' || true)
  if [ -n "$lines" ]; then printf '### %s\n\n%s\n\n' "$title" "$lines"; fi
}
{
  section "不兼容的改动" '^[a-z]+(\([^)]*\))?!: '
  section "新功能" '^feat(\([^)]*\))?: '
  section "修复" '^fix(\([^)]*\))?: '
  section "性能" '^perf(\([^)]*\))?: '
  section "文档" '^docs(\([^)]*\))?: '
  if [ -n "$prev" ]; then echo "完整改动：$prev...$tag"; else echo "首个正式版本。"; fi
}
