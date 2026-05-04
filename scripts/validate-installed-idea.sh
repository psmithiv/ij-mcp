#!/bin/zsh

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd -P)"
IDEA_APP="${IDEA_APP:-/Applications/IntelliJ IDEA.app}"
IDEA_BIN="$IDEA_APP/Contents/MacOS/idea"
PROJECT_PATH="${PROJECT_PATH:-$ROOT_DIR}"
STARTUP_TIMEOUT="${STARTUP_TIMEOUT:-90}"
WORK_ROOT="${WORK_ROOT:-$(mktemp -d /tmp/ijmcp-installed-validation.XXXXXX)}"
SANDBOX_HOME="$WORK_ROOT/home"
CONFIG_DIR="$WORK_ROOT/config"
SYSTEM_DIR="$WORK_ROOT/system"
PLUGINS_DIR="$WORK_ROOT/plugins"
LOG_DIR="$WORK_ROOT/log"
OPTIONS_DIR="$CONFIG_DIR/options"
PROPERTIES_FILE="$WORK_ROOT/idea.properties"
SETTINGS_FILE="$OPTIONS_DIR/ijmcp.xml"
TRUSTED_PATHS_FILE="$OPTIONS_DIR/trusted-paths.xml"
STDOUT_LOG="$WORK_ROOT/idea.stdout.log"
CODEX_CONFIG_FILE="$SANDBOX_HOME/.codex/config.toml"
CLIENT_STATE_FILE="$SANDBOX_HOME/.ij-mcp/client-state.json"
REGISTRY_ROOT="${REGISTRY_ROOT:-$SANDBOX_HOME/.ij-mcp}"
REGISTRY_FILE="$REGISTRY_ROOT/targets.json"
REGISTRY_BACKUP="$WORK_ROOT/targets.json.backup"
VALIDATION_FILE="${VALIDATION_FILE:-}"
REMOVE_VALIDATION_FILE="false"

PLUGIN_ZIP="${PLUGIN_ZIP:-}"
if [[ -z "$PLUGIN_ZIP" ]]; then
    PLUGIN_ZIP="$(ls -1t "$ROOT_DIR"/build/distributions/ij-mcp-*.zip 2>/dev/null | head -n 1 || true)"
fi

if [[ ! -x "$IDEA_BIN" ]]; then
    echo "IntelliJ launcher not found at $IDEA_BIN" >&2
    exit 1
fi

if [[ -z "$PLUGIN_ZIP" || ! -f "$PLUGIN_ZIP" ]]; then
    echo "Plugin zip not found. Build it first with ./gradlew buildPlugin" >&2
    exit 1
fi

PROJECT_PATH="$(cd "$PROJECT_PATH" && pwd -P)"
if [[ -z "$VALIDATION_FILE" ]]; then
    VALIDATION_FILE="$PROJECT_PATH/.ijmcp-installed-validation.txt"
    REMOVE_VALIDATION_FILE="true"
fi

mkdir -p "$CONFIG_DIR" "$SYSTEM_DIR" "$PLUGINS_DIR" "$LOG_DIR" "$OPTIONS_DIR" "$REGISTRY_ROOT" "$SANDBOX_HOME/.codex"

cleanup() {
    set +e

    local pids=()
    if command -v pgrep >/dev/null 2>&1; then
        while IFS= read -r pid; do
            [[ -n "$pid" ]] && pids+=("$pid")
        done < <(pgrep -f "$CONFIG_DIR" || true)
    fi

    if [[ ${#pids[@]} -eq 0 && -n "${LAUNCHER_PID:-}" ]]; then
        pids=("$LAUNCHER_PID")
    fi

    for pid in "${pids[@]:-}"; do
        kill "$pid" 2>/dev/null || true
    done

    sleep 2

    for pid in "${pids[@]:-}"; do
        kill -9 "$pid" 2>/dev/null || true
    done

    if [[ -f "$REGISTRY_BACKUP" ]]; then
        cp "$REGISTRY_BACKUP" "$REGISTRY_FILE"
    else
        cat > "$REGISTRY_FILE" <<'EOF'
{
  "version": 1,
  "targets": []
}
EOF
    fi

    if [[ "$REMOVE_VALIDATION_FILE" == "true" ]]; then
        rm -f "$VALIDATION_FILE"
    fi
}

trap cleanup EXIT

if [[ -f "$REGISTRY_FILE" ]]; then
    cp "$REGISTRY_FILE" "$REGISTRY_BACKUP"
fi

cat > "$REGISTRY_FILE" <<'EOF'
{
  "version": 1,
  "targets": []
}
EOF

unzip -q -o "$PLUGIN_ZIP" -d "$PLUGINS_DIR"

cat > "$PROPERTIES_FILE" <<EOF
idea.config.path=$CONFIG_DIR
idea.system.path=$SYSTEM_DIR
idea.plugins.path=$PLUGINS_DIR
idea.log.path=$LOG_DIR
EOF

cat > "$SETTINGS_FILE" <<EOF
<application>
  <component name="IjMcpSettings">
    <option name="enabled" value="true" />
    <option name="port" value="8765" />
    <option name="autoTrustLocalClients" value="true" />
    <option name="manageCodexConfig" value="true" />
  </component>
</application>
EOF

cat > "$TRUSTED_PATHS_FILE" <<EOF
<application>
  <component name="Trusted.Paths">
    <option name="TRUSTED_PROJECT_PATHS">
      <map>
        <entry key="$PROJECT_PATH" value="true" />
      </map>
    </option>
  </component>
  <component name="Trusted.Paths.Settings">
    <option name="TRUSTED_PATHS">
      <list>
        <option value="$PROJECT_PATH" />
      </list>
    </option>
  </component>
</application>
EOF

mkdir -p "$(dirname "$VALIDATION_FILE")"
printf 'validated at %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$VALIDATION_FILE"

json_escape() {
    local value="$1"
    value="${value//\\/\\\\}"
    value="${value//\"/\\\"}"
    printf '%s' "$value"
}

fail_if_error() {
    local label="$1"
    local body="$2"
    if [[ "$body" == *'"error":'* ]] || [[ "$body" == *'"isError":true'* ]]; then
        echo "$label failed:" >&2
        echo "$body" >&2
        exit 1
    fi
}

LAST_CURL_BODY=""
LAST_CURL_MS=""

curl_capture() {
    local label="$1"
    local body_file="$WORK_ROOT/curl.$RANDOM.body"
    local elapsed_seconds
    shift

    elapsed_seconds="$(curl -fsS -o "$body_file" -w '%{time_total}' "$@")"
    LAST_CURL_BODY="$(cat "$body_file")"
    LAST_CURL_MS="$(awk -v seconds="$elapsed_seconds" 'BEGIN { printf "%.0f", seconds * 1000 }')"
    fail_if_error "$label" "$LAST_CURL_BODY"
    echo "$label latency: ${LAST_CURL_MS} ms"
}

extract_codex_endpoint() {
    awk '
        /^\[mcp_servers\.ij-mcp\]/ { inside=1; next }
        inside && /^\[/ { inside=0 }
        inside && /^url = / {
            value=$0
            sub(/^url = "/, "", value)
            sub(/"$/, "", value)
            print value
            exit
        }
    ' "$CODEX_CONFIG_FILE"
}

extract_codex_token() {
    awk -F'Bearer ' '
        /^\[mcp_servers\.ij-mcp\]/ { inside=1; next }
        inside && /^\[/ { inside=0 }
        inside && /http_headers/ && NF > 1 {
            value=$2
            sub(/".*$/, "", value)
            print value
            exit
        }
    ' "$CODEX_CONFIG_FILE"
}

validate_direct_mcp_flow() {
    local endpoint_url
    local bearer_token
    local health_url
    local escaped_path
    local auth_header
    local protocol_header
    local initialize_payload
    local tools_payload
    local open_payload
    local open_again_payload
    local health_response
    local initialize_response
    local tools_response
    local open_response
    local open_again_response

    endpoint_url="$(extract_codex_endpoint)"
    bearer_token="$(extract_codex_token)"

    if [[ -z "$endpoint_url" || -z "$bearer_token" ]]; then
        echo "Codex config did not contain a usable IJ-MCP URL and bearer token." >&2
        exit 1
    fi

    health_url="${endpoint_url%/mcp}/health"
    escaped_path="$(json_escape "$VALIDATION_FILE")"
    auth_header="Authorization: Bearer $bearer_token"
    protocol_header="MCP-Protocol-Version: 2025-11-25"
    initialize_payload='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"ij-mcp-installed-validator","version":"1.0.0"}}}'
    tools_payload='{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
    open_payload="{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"open_file\",\"arguments\":{\"path\":\"$escaped_path\",\"line\":1,\"column\":1}}}"
    open_again_payload="{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"open_file\",\"arguments\":{\"path\":\"$escaped_path\",\"line\":1,\"column\":1}}}"

    echo
    echo "Checking direct project MCP endpoint from managed Codex config..."
    curl_capture "health" "$health_url"
    health_response="$LAST_CURL_BODY"
    echo "$health_response"

    echo
    echo "Initializing direct MCP session..."
    curl_capture "initialize" \
        -H "$auth_header" \
        -H 'Content-Type: application/json' \
        -d "$initialize_payload" \
        "$endpoint_url"
    initialize_response="$LAST_CURL_BODY"
    echo "$initialize_response"

    echo
    echo "Listing tools through the direct endpoint..."
    curl_capture "tools/list" \
        -H "$auth_header" \
        -H "$protocol_header" \
        -H 'Content-Type: application/json' \
        -d "$tools_payload" \
        "$endpoint_url"
    tools_response="$LAST_CURL_BODY"
    if [[ "$tools_response" != *'"name":"open_file"'* ]]; then
        echo "tools/list did not include open_file." >&2
        exit 1
    fi
    echo "tools/list returned open_file."

    echo
    echo "Opening the validation file through the direct endpoint..."
    curl_capture "open_file" \
        -H "$auth_header" \
        -H "$protocol_header" \
        -H 'Content-Type: application/json' \
        -d "$open_payload" \
        "$endpoint_url"
    open_response="$LAST_CURL_BODY"
    echo "$open_response"

    echo
    echo "Opening the validation file again through the direct endpoint..."
    curl_capture "open_file_again" \
        -H "$auth_header" \
        -H "$protocol_header" \
        -H 'Content-Type: application/json' \
        -d "$open_again_payload" \
        "$endpoint_url"
    open_again_response="$LAST_CURL_BODY"
    echo "$open_again_response"
}

echo "Launching installed IntelliJ IDEA with isolated state..."
echo "  work_root=$WORK_ROOT"
echo "  sandbox_home=$SANDBOX_HOME"
echo "  plugin_zip=$PLUGIN_ZIP"
echo "  project_path=$PROJECT_PATH"

HOME="$SANDBOX_HOME" IDEA_PROPERTIES="$PROPERTIES_FILE" "$IDEA_BIN" "-Didea.properties.file=$PROPERTIES_FILE" "-Duser.home=$SANDBOX_HOME" "$PROJECT_PATH" >"$STDOUT_LOG" 2>&1 &
LAUNCHER_PID=$!

LOG_FILE="$LOG_DIR/idea.log"
deadline=$((SECONDS + STARTUP_TIMEOUT))

while (( SECONDS < deadline )); do
    if [[ -f "$LOG_FILE" ]] &&
        grep -Fq "Registered IJ-MCP target" "$LOG_FILE" &&
        grep -Fq "started on http://127.0.0.1:" "$LOG_FILE" &&
        grep -Fq "$PROJECT_PATH" "$REGISTRY_FILE" &&
        [[ -f "$CODEX_CONFIG_FILE" ]] &&
        grep -Fq "[mcp_servers.ij-mcp]" "$CODEX_CONFIG_FILE" &&
        grep -Fq "http_headers" "$CODEX_CONFIG_FILE" &&
        [[ -f "$CLIENT_STATE_FILE" ]] &&
        grep -Fq '"selectedTargetId"' "$CLIENT_STATE_FILE" &&
        grep -Fq '"credentialsByTargetId"' "$CLIENT_STATE_FILE"; then
        validate_direct_mcp_flow
        echo
        echo "Installed-instance validation passed."
        echo "  log_file=$LOG_FILE"
        echo "  registry_file=$REGISTRY_FILE"
        echo "  client_state_file=$CLIENT_STATE_FILE"
        echo "  codex_config_file=$CODEX_CONFIG_FILE"
        echo "  validation_file=$VALIDATION_FILE"
        echo
        grep -E "Registered IJ-MCP target|IJ-MCP target .* started on" "$LOG_FILE" || true
        echo
        cat "$REGISTRY_FILE"
        echo
        sed -E 's/Bearer [^"]+/Bearer <redacted>/' "$CODEX_CONFIG_FILE"
        exit 0
    fi

    sleep 2
done

echo "Installed-instance validation timed out." >&2
echo "  log_file=$LOG_FILE" >&2
echo "  stdout_log=$STDOUT_LOG" >&2
echo "  registry_file=$REGISTRY_FILE" >&2
echo "  client_state_file=$CLIENT_STATE_FILE" >&2
echo "  codex_config_file=$CODEX_CONFIG_FILE" >&2

if [[ -f "$LOG_FILE" ]]; then
    echo >&2
    echo "Last log lines:" >&2
    tail -n 200 "$LOG_FILE" >&2 || true
fi

exit 1
