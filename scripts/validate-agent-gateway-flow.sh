#!/bin/zsh

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd -P)"
GATEWAY_URL="${GATEWAY_URL:-http://127.0.0.1:3765/mcp}"
GATEWAY_HEALTH_URL="${GATEWAY_HEALTH_URL:-http://127.0.0.1:3765/health}"
GATEWAY_TOKEN="${IJ_MCP_GATEWAY_TOKEN:-${GATEWAY_TOKEN:-}}"
VALIDATION_FILE="${VALIDATION_FILE:-$ROOT_DIR/.ijmcp-agent-validation.txt}"

if [[ -z "$GATEWAY_TOKEN" ]]; then
    echo "Set IJ_MCP_GATEWAY_TOKEN (or GATEWAY_TOKEN) from 'gateway config' before running this script." >&2
    exit 1
fi

mkdir -p "$(dirname "$VALIDATION_FILE")"
printf 'validated at %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$VALIDATION_FILE"

escaped_path="${VALIDATION_FILE//\\/\\\\}"
escaped_path="${escaped_path//\"/\\\"}"

auth_header="Authorization: Bearer $GATEWAY_TOKEN"
protocol_header="MCP-Protocol-Version: 2025-11-25"

initialize_payload='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"ij-mcp-gateway-validator","version":"1.0.0"}}}'
tools_payload='{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
reveal_payload="{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"reveal_file_in_project\",\"arguments\":{\"path\":\"$escaped_path\"}}}"
open_payload="{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"open_file\",\"arguments\":{\"path\":\"$escaped_path\",\"line\":1,\"column\":1}}}"

fail_if_error() {
    local label="$1"
    local body="$2"
    if [[ "$body" == *'"error"'* ]]; then
        echo "$label failed:" >&2
        echo "$body" >&2
        exit 1
    fi
}

echo "Checking gateway health..."
health_response="$(curl -fsS "$GATEWAY_HEALTH_URL")"
echo "$health_response"

echo
echo "Initializing MCP session through the gateway..."
initialize_response="$(curl -fsS \
    -H "$auth_header" \
    -H 'Content-Type: application/json' \
    -d "$initialize_payload" \
    "$GATEWAY_URL")"
fail_if_error "initialize" "$initialize_response"
echo "$initialize_response"

echo
echo "Listing tools through the gateway..."
tools_response="$(curl -fsS \
    -H "$auth_header" \
    -H "$protocol_header" \
    -H 'Content-Type: application/json' \
    -d "$tools_payload" \
    "$GATEWAY_URL")"
fail_if_error "tools/list" "$tools_response"
echo "$tools_response"

echo
echo "Revealing the validation file in IntelliJ..."
reveal_response="$(curl -fsS \
    -H "$auth_header" \
    -H "$protocol_header" \
    -H 'Content-Type: application/json' \
    -d "$reveal_payload" \
    "$GATEWAY_URL")"
fail_if_error "reveal_file_in_project" "$reveal_response"
echo "$reveal_response"

echo
echo "Opening the validation file in IntelliJ..."
open_response="$(curl -fsS \
    -H "$auth_header" \
    -H "$protocol_header" \
    -H 'Content-Type: application/json' \
    -d "$open_payload" \
    "$GATEWAY_URL")"
fail_if_error "open_file" "$open_response"
echo "$open_response"

echo
echo "Agent gateway validation succeeded."
echo "  validation_file=$VALIDATION_FILE"
