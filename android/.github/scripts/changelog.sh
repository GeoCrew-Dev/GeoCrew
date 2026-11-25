#!/usr/bin/env bash
# Release notes for tag $1 from the Conventional Commits since the previous tag.
set -euo pipefail
tag=$1
prev=$(git describe --tags --abbrev=0 --match 'v*' "${tag}^" 2>/dev/null || true)
range=${prev:+$prev..}$tag
section() {
  local title=$1 pattern=$2 lines
  lines=$(git log --no-merges --format='%s' "$range" | grep -E "$pattern" | sed -E 's/^[a-z]+(\(([^)]*)\))?!?: /- **\2** /; s/- \*\*\*\* /- /' || true)
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
