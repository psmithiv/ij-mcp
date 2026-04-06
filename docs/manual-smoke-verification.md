# Manual Smoke Verification

This checklist verifies the current multi-target IJ-MCP flow: one MCP target per project window, local registry discovery, pair-once authentication, sticky CLI selection, and MCP tool routing through the selected target.

## Prerequisites

* Java 21+
* IntelliJ IDEA 2025.2-compatible runtime
* a local shell

## 1. Prepare IntelliJ IDEA

Choose one setup path.

### Installed IntelliJ IDEA instance

Follow:

* [Install IJ-MCP from disk](install-from-disk.md)

After install and restart, open one or two project windows in the normal IDE
instance before continuing.

### Plugin sandbox for development verification

From the repo root:

```bash
./gradlew runIde
```

Open one or two project windows in the sandbox IDE if you want to verify
multi-window behavior.

## 2. Enable IJ-MCP and inspect target state

In each window:

1. Open IntelliJ `Settings` and search for `IJ-MCP`.
2. Check `Enable local MCP server`.
3. Leave the preferred port at `8765` unless you are intentionally testing fallback behavior.
4. Click `Apply`.
5. Confirm the settings page shows:
   * a running endpoint for the selected target
   * plugin build and compatibility details for the current IDE
   * operator guidance for the current target state
   * the current `targetId`
   * the project name and path
   * registry diagnostics for that target

Expected result:

* each project window is listed as its own target in the settings UI
* the selected target shows `Pairing status: pairing required`
* the endpoint is bound to `127.0.0.1`

## 3. Verify target discovery and sticky selection

List all discovered targets:

```bash
./gradlew :cli:run --args='targets list'
```

Select one target:

```bash
./gradlew :cli:run --args='targets select <targetId>'
```

Read the sticky selection back:

```bash
./gradlew :cli:run --args='targets current'
```

Expected result:

* `targets list` shows one line per active project-window target
* the selected target is marked with `*`
* `targets current` returns the selected target identity and endpoint

## 4. Pair the CLI with the selected target

In the IJ-MCP settings page for the selected target:

1. Click `Pair CLI`.
2. Copy the one-time pairing code.

In the shell:

```bash
./gradlew :cli:run --args='targets pair --code <pairingCode>'
```

Expected result:

* the CLI prints `Paired target ...`
* `targets current` still points at the same target
* `targets list` shows the selected target as `paired`

## 5. Verify MCP routing through the sticky target

List tools through the CLI:

```bash
./gradlew :cli:run --args='mcp tools-list'
```

Call a navigation tool:

```bash
./gradlew :cli:run --args='mcp call open_file {"path":"README.md","line":1,"column":1}'
```

Call a search tool:

```bash
./gradlew :cli:run --args='mcp call search_files {"query":"IjMcpCli.kt","limit":5}'
```

Expected result:

* `mcp tools-list` returns the 8 documented tools
* `mcp call` returns JSON-RPC success payloads with `structuredContent`
* the action occurs in the selected project window, not in another open IDE window

## 6. Verify multi-target switching

If more than one target is available:

1. Select a different target with `targets select <targetId>`.
2. Pair it if needed with a new one-time pairing code.
3. Run `mcp call get_active_editor_context`.

Expected result:

* the CLI now talks only to the newly selected target
* no implicit fallback occurs to the previously selected target

## 7. Verify credential reset and recovery

In the IJ-MCP settings page for the selected target:

1. Click `Reset Pairing`.

In the shell:

```bash
./gradlew :cli:run --args='mcp tools-list'
```

Expected result:

* the request fails clearly with a message that the target should be re-paired
* no other available target is used automatically

Re-pair with a fresh code and confirm `mcp tools-list` succeeds again.

## 8. Verify stale-target recovery

1. Close the selected project window or stop the target from the IDE.
2. Run:

```bash
./gradlew :cli:run --args='mcp tools-list'
```

Expected result:

* the CLI fails with an unavailable or unreachable target message
* the CLI tells you to run `targets list` and `targets select <targetId>`
* the CLI does not silently switch to another target

## 9. Verify coding-agent gateway configuration

Start the gateway in a dedicated shell:

```bash
./gradlew :cli:run --args='gateway serve'
```

In a second shell, print the gateway config:

```bash
./gradlew :cli:run --args='gateway config'
```

Export the bearer token:

```bash
export IJ_MCP_GATEWAY_TOKEN='<token from gateway config>'
```

If you want to follow the validated Codex CLI path, add the MCP entry:

```bash
codex mcp add ij-mcp \
  --url http://127.0.0.1:3765/mcp \
  --bearer-token-env-var IJ_MCP_GATEWAY_TOKEN
codex mcp get ij-mcp
```

Then run the repo validation script:

```bash
./scripts/validate-agent-gateway-flow.sh
```

Expected result:

* the gateway health endpoint reports `routingMode` as `sticky-selected-target`
* `codex mcp get ij-mcp` shows the streamable HTTP gateway entry
* the validation script creates or updates `.ijmcp-agent-validation.txt`
* IntelliJ reveals and opens that file in the selected project window

Detailed setup notes:

* [Agent gateway setup](agent-gateway-setup.md)
