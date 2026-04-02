#!/bin/zsh

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd -P)"
IDEA_APP="${IDEA_APP:-/Applications/IntelliJ IDEA.app}"
IDEA_BIN="$IDEA_APP/Contents/MacOS/idea"
PROJECT_PATH="${PROJECT_PATH:-$ROOT_DIR}"
STARTUP_TIMEOUT="${STARTUP_TIMEOUT:-90}"
WORK_ROOT="${WORK_ROOT:-$(mktemp -d /tmp/ijmcp-installed-validation.XXXXXX)}"
CONFIG_DIR="$WORK_ROOT/config"
SYSTEM_DIR="$WORK_ROOT/system"
PLUGINS_DIR="$WORK_ROOT/plugins"
LOG_DIR="$WORK_ROOT/log"
OPTIONS_DIR="$CONFIG_DIR/options"
PROPERTIES_FILE="$WORK_ROOT/idea.properties"
SETTINGS_FILE="$OPTIONS_DIR/ijmcp.xml"
TRUSTED_PATHS_FILE="$OPTIONS_DIR/trusted-paths.xml"
STDOUT_LOG="$WORK_ROOT/idea.stdout.log"
REGISTRY_ROOT="${REGISTRY_ROOT:-$HOME/.ij-mcp}"
REGISTRY_FILE="$REGISTRY_ROOT/targets.json"
REGISTRY_BACKUP="$WORK_ROOT/targets.json.backup"

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

mkdir -p "$CONFIG_DIR" "$SYSTEM_DIR" "$PLUGINS_DIR" "$LOG_DIR" "$OPTIONS_DIR" "$REGISTRY_ROOT"

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

echo "Launching installed IntelliJ IDEA with isolated state..."
echo "  work_root=$WORK_ROOT"
echo "  plugin_zip=$PLUGIN_ZIP"
echo "  project_path=$PROJECT_PATH"

IDEA_PROPERTIES="$PROPERTIES_FILE" "$IDEA_BIN" "-Didea.properties.file=$PROPERTIES_FILE" "$PROJECT_PATH" >"$STDOUT_LOG" 2>&1 &
LAUNCHER_PID=$!

LOG_FILE="$LOG_DIR/idea.log"
deadline=$((SECONDS + STARTUP_TIMEOUT))

while (( SECONDS < deadline )); do
    if [[ -f "$LOG_FILE" ]] &&
        grep -Fq "Registered IJ-MCP target" "$LOG_FILE" &&
        grep -Fq "started on http://127.0.0.1:" "$LOG_FILE" &&
        grep -Fq "$PROJECT_PATH" "$REGISTRY_FILE"; then
        echo
        echo "Installed-instance validation passed."
        echo "  log_file=$LOG_FILE"
        echo "  registry_file=$REGISTRY_FILE"
        echo
        grep -E "Registered IJ-MCP target|IJ-MCP target .* started on" "$LOG_FILE" || true
        echo
        cat "$REGISTRY_FILE"
        exit 0
    fi

    sleep 2
done

echo "Installed-instance validation timed out." >&2
echo "  log_file=$LOG_FILE" >&2
echo "  stdout_log=$STDOUT_LOG" >&2

if [[ -f "$LOG_FILE" ]]; then
    echo >&2
    echo "Last log lines:" >&2
    tail -n 200 "$LOG_FILE" >&2 || true
fi

exit 1
