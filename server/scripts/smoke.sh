#!/usr/bin/env bash
# Quick check that a running GeoCrew server answers: info, a throwaway
# workspace, one record pushed and pulled back, a view link and the MCP
# tools. Needs curl and jq.
#
#   scripts/smoke.sh https://geo.example.org
#
# It creates a workspace named "smoke-test-<time>"; delete it afterwards
# with `geocrew-server -data <dir> admin delete <id> confirm`. For a full check of
# the protocol use the conformance tool in geocrew-protocol.
set -euo pipefail
base=${1:?usage: smoke.sh <server url>}
base=${base%/}
api=$base/api/v0
step() { printf '%-28s' "$1"; }
ok() { echo "ok${1:+  $1}"; }
fail() { echo "FAILED: $*"; exit 1; }

step "server info"
info=$(curl -fsS "$api/info") || fail "no answer from $api/info"
ok "$(jq -r '"\(.name // "?") \(.version // "?"), \(.capabilities | length) capabilities"' <<<"$info")"

step "create workspace"
ws=$(curl -fsS -X POST "$api/workspaces" -H 'Content-Type: application/json' \
  -d "{\"name\":\"smoke-test-$(date +%s)\",\"nickname\":\"smoke\"}") || fail "could not create a workspace"
id=$(jq -r .workspace_id <<<"$ws"); token=$(jq -r .token <<<"$ws")
[ -n "$id" ] && [ "$id" != null ] || fail "no workspace_id in $ws"
ok "$id"
auth=(-H "Authorization: Bearer $token" -H 'Content-Type: application/json')

step "push a record"
now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
push=$(curl -fsS -X POST "$api/workspaces/$id/features" "${auth[@]}" -d '{"features":[{"type":"Feature","id":"smoke-1",
  "geometry":{"type":"Point","coordinates":[116.39,39.91]},
  "properties":{"kind":"geo_point","title":"SMOKE1"},
  "sync":{"version":1,"updated_at":"'"$now"'"}}]}') || fail "push refused"
ok "$(jq -c '[.results[].status]' <<<"$push")"

step "pull it back"
pull=$(curl -fsS "$api/workspaces/$id/features?since=0" "${auth[@]}") || fail "pull refused"
jq -e '[.features[].id] | index("smoke-1") != null' <<<"$pull" >/dev/null || fail "record not in pull: $pull"
ok

step "view link"
link=$(curl -fsS -X POST "$api/workspaces/$id/view-links" "${auth[@]}" -d '{}') || fail "view link refused"
vtok=$(jq -r .token <<<"$link"); ok

if jq -e '.capabilities | index("mcp") != null' <<<"$info" >/dev/null; then
  step "MCP tools (view link)"
  tools=$(curl -fsS -X POST "$api/workspaces/$id/mcp" -H "Authorization: Bearer $vtok" -H 'Content-Type: application/json' \
    -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}') || fail "MCP refused"
  ok "$(jq -r '.result.tools | length' <<<"$tools") tools"
  step "MCP search"
  found=$(curl -fsS -X POST "$api/workspaces/$id/mcp" -H "Authorization: Bearer $vtok" -H 'Content-Type: application/json' \
    -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"search_records","arguments":{"query":"smoke1"}}}')
  [ "$(jq -r '.result.structuredContent.matched' <<<"$found")" = 1 ] || fail "search did not find the record: $found"
  ok
fi

curl -fsS -X DELETE "$api/workspaces/$id/view-links" "${auth[@]}" >/dev/null || true
echo "all good; remove the test workspace with: geocrew-server -data <dir> admin delete $id confirm"
