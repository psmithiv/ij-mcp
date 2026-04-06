# Operator Setup Guide

This guide is the shortest supported path for installing `IJ-MCP` into a real
IntelliJ IDEA instance and connecting a local CLI coding agent to the selected
project window.

The intended v1 operating model is:

* install the plugin into IntelliJ IDEA from a built zip
* enable the local MCP server in the target project window
* select and pair exactly one target from the companion CLI
* expose a stable local gateway endpoint for the coding agent
* let the coding agent create or modify files with its normal workspace tools
* use `IJ-MCP` only for IDE navigation, search, reveal, and open actions

## Prerequisites

Before starting, confirm:

* IntelliJ IDEA is `2025.2.x`
* Java `21+` is installed locally
* the repo can build `build/distributions/ij-mcp-<version>.zip`
* the companion CLI is available through `./gradlew :cli:run`
* you have a local project that can be opened in IntelliJ IDEA

Build the validated local artifacts from the repo root if needed:

```bash
./gradlew buildPlugin
./gradlew :cli:installDist
```

## 1. Install The Plugin

1. Open IntelliJ IDEA.
2. Open `Settings` / `Preferences`.
3. Go to `Plugins`.
4. Choose `Install Plugin from Disk...`.
5. Select `build/distributions/ij-mcp-<version>.zip`.
6. Restart IntelliJ IDEA if prompted.

Detailed install notes are in [Install IJ-MCP from disk](install-from-disk.md).

## 2. Enable The Local MCP Target

1. Open the target project in IntelliJ IDEA.
2. Open `Settings` / `Preferences`.
3. Search for `IJ-MCP`.
4. Check `Enable local MCP server`.
5. Leave `Preferred Port` at `8765` unless you intentionally need a different
   starting port.
6. Click `Apply`.

Expected success signals in the settings page:

* `Server Status` shows `Running at http://127.0.0.1:<port>/mcp`
* `Plugin Build` and `Compatibility` both show a supported runtime
* `Operator Guidance` explains the next step for the current state
* `Target Identity`, `Project`, and `Endpoint` are populated
* `Pairing Status` shows `pairing required` before the first CLI pair
* `Registry Status` shows the live registry path and registration result

## 3. Select The Intended Target

In a shell at the repo root:

```bash
./gradlew :cli:run --args='targets list'
./gradlew :cli:run --args='targets select <targetId>'
./gradlew :cli:run --args='targets current'
```

Expected result:

* `targets list` shows the active project-window targets
* `targets select` marks the intended target as sticky
* `targets current` returns `routeStatus=selected_unpaired`
* `targets current` prints the selected target metadata, registry file path,
  and `recoveryCode=pairing_required`

## 4. Pair CLI Access

In the IntelliJ `IJ-MCP` settings page for the selected target:

1. Click `Generate Pairing Code`.
2. Copy the one-time code.

In the shell:

```bash
./gradlew :cli:run --args='targets pair --code <pairingCode>'
./gradlew :cli:run --args='targets current'
```

Expected result:

* the CLI prints `Paired target ...`
* the CLI prints the next direct-access and gateway steps
* `targets current` now returns `routeStatus=selected`

## 5. Validate Direct MCP Access

Confirm that the companion CLI can route directly through the selected target:

```bash
./gradlew :cli:run --args='mcp tools-list'
```

Expected result:

* `tools/list` succeeds
* the available tools stay limited to IDE navigation and search behavior
* no implicit fallback occurs to another target

## 6. Configure The Stable Agent Gateway

Print the stable gateway endpoint and bearer token:

```bash
./gradlew :cli:run --args='gateway config'
```

Expected result:

* `endpointUrl=http://127.0.0.1:3765/mcp`
* `healthUrl=http://127.0.0.1:3765/health`
* `gatewayBearerToken=<stable token>`
* `selectedTargetId=<targetId>`
* `routeStatus=selected`

Export the token:

```bash
export IJ_MCP_GATEWAY_TOKEN='<token from gateway config>'
```

Start the gateway in a dedicated shell:

```bash
./gradlew :cli:run --args='gateway serve'
```

Health check:

```bash
curl -fsS http://127.0.0.1:3765/health
```

Expected health fields:

* `routingMode=sticky-selected-target`
* `selectedTargetId` matches `targets current`
* `requiresAuth=true`

## 7. Connect A Coding Agent

The validated example in this repo uses Codex CLI.

Register the stable gateway endpoint:

```bash
codex mcp add ij-mcp \
  --url http://127.0.0.1:3765/mcp \
  --bearer-token-env-var IJ_MCP_GATEWAY_TOKEN
codex mcp get ij-mcp
```

Expected result:

* the MCP entry is `enabled: true`
* the transport is `streamable_http`
* the URL is the stable loopback gateway, not the per-window IntelliJ endpoint

## 8. Validate The End-To-End IDE Control Path

Run the repo validation script:

```bash
export IJ_MCP_GATEWAY_TOKEN='<token from gateway config>'
./scripts/validate-agent-gateway-flow.sh
```

Expected result:

* the script creates or updates `.ijmcp-agent-validation.txt`
* gateway health, `initialize`, `tools/list`, `reveal_file_in_project`, and
  `open_file` all succeed
* IntelliJ reveals and opens `.ijmcp-agent-validation.txt` in the selected
  project window

This is the supported v1 behavior. The coding agent owns file generation and
editing; `IJ-MCP` owns IDE visibility and navigation.

## Daily Operator Commands

Useful commands after setup:

```bash
./gradlew :cli:run --args='targets list'
./gradlew :cli:run --args='targets current'
./gradlew :cli:run --args='mcp tools-list'
./gradlew :cli:run --args='gateway config'
./gradlew :cli:run --args='gateway serve'
```

## Related Guides

* [Install IJ-MCP from disk](install-from-disk.md)
* [Agent gateway setup](agent-gateway-setup.md)
* [Installed IntelliJ validation](installed-instance-validation.md)
* [Manual smoke verification](manual-smoke-verification.md)
* [Plugin lifecycle](plugin-lifecycle.md)
