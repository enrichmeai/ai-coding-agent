#!/usr/bin/env bash
# Proves the offline stack works end to end: local model, real tool call,
# a file on the host, and history read back out of SQLite.
#
#   ./scripts/demo-offline.sh            # against the bundled Ollama container
#   AGENT_PORT=9000 ./scripts/demo-offline.sh
set -euo pipefail

PORT="${AGENT_PORT:-8090}"
BASE="http://localhost:${PORT}"
WORKSPACE="${AGENT_WORKSPACE_HOST:-./agent-workspace}"
CONTENT="Hello from a fully offline agent"
FILE="hello.txt"

pass() { printf '  ok    %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; exit 1; }

echo "[1/5] health"
# Give a just-started container time to come up; Spring takes a few seconds and
# the port is bound before it serves.
health=""
for _ in $(seq 1 30); do
    health=$(curl -fsS --max-time 5 "${BASE}/api/health" 2>/dev/null) && break
    sleep 2
done
[ -n "$health" ] || fail "no response from ${BASE} after 60s — is the stack up? (docker compose ps)"
provider=$(printf '%s' "$health" | python3 -c 'import json,sys; print(json.load(sys.stdin)["provider"])')
ntools=$(printf '%s' "$health" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)["tools"]))')
[ "$provider" = "ollama" ] || fail "provider is '${provider}', expected 'ollama'"
pass "provider=${provider}, ${ntools} tools"

echo "[2/5] model is served locally"
# Ask the agent what it is actually pointed at. Checking whether the bundled
# ollama container is up would lie in host mode, where that container still runs
# (nothing can drop a depends_on in an override) but serves no traffic.
target=$(docker compose exec -T agent sh -c 'echo "$OLLAMA_MODEL $OLLAMA_BASE_URL"' 2>/dev/null | tr -d '\r') \
    || fail "could not reach the agent container"
model=${target% *}
url=${target#* }
case "$url" in
    *//ollama:*)  where="the bundled ollama container" ;;
    *host.docker.internal*|*//localhost:*|*//127.0.0.1:*) where="an Ollama on the host" ;;
    *) where="$url" ;;
esac
pass "${model} via ${where}"

echo "[3/5] tool-use turn"
rm -f "${WORKSPACE:?}/${FILE}"
response=$(curl -fsS --max-time 900 -X POST "${BASE}/api/chat" \
    -H 'Content-Type: application/json' \
    -d "{\"message\":\"Create a file named ${FILE} in the workspace containing exactly: ${CONTENT}\"}")

printf '%s' "$response" > /tmp/demo-offline-response.json
session=$(printf '%s' "$response" | python3 -c 'import json,sys; print(json.load(sys.stdin)["sessionId"])')
called=$(printf '%s' "$response" | python3 -c '
import json,sys
d=json.load(sys.stdin)
print(",".join(t["name"] for m in d["newMessages"] for t in (m.get("toolCalls") or [])) or "NONE")')
[ "$called" != "NONE" ] || fail "the model produced no tool call (see /tmp/demo-offline-response.json) — try a different OLLAMA_MODEL"
pass "${called} → ${FILE}"

echo "[4/5] file on host"
[ -f "${WORKSPACE}/${FILE}" ] || fail "${WORKSPACE}/${FILE} was not created"
actual=$(cat "${WORKSPACE}/${FILE}")
[ "$actual" = "$CONTENT" ] || fail "content mismatch: '${actual}'"
pass "\"${actual}\""

echo "[5/5] history persisted"
roles=$(curl -fsS --max-time 30 "${BASE}/api/sessions/${session}" \
    | python3 -c 'import json,sys; print(", ".join(m["role"] for m in json.load(sys.stdin)["history"]))')
case "$roles" in
    *USER*ASSISTANT*TOOL*ASSISTANT*) pass "${roles}" ;;
    *) fail "unexpected history: ${roles}" ;;
esac

echo
echo "All checks passed. UI: ${BASE}"
