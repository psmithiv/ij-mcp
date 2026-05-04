# Operator Setup Guide

This guide is the shortest supported path for installing `IJ-MCP` into a real
IntelliJ IDEA instance and connecting Codex to the current project window.

The intended v1 operating model is:

* install the plugin into IntelliJ IDEA from a built zip
* open the project in IntelliJ IDEA
* launch `codex` from that project directory
* let the coding agent create or modify files with its normal workspace tools
* use `IJ-MCP` only for IDE navigation, search, reveal, and open actions

## Prerequisites

Before starting, confirm:

* IntelliJ IDEA is `2025.2.x`
* Java `21+` is installed locally
* the repo can build `build/distributions/ij-mcp-<version>.zip`
* Codex CLI is installed locally
* you have a local project that can be opened in IntelliJ IDEA

Build the validated local artifacts from the repo root if needed:

```bash
./gradlew buildPlugin
```

## 1. Install The Plugin

1. Open IntelliJ IDEA.
2. Open `Settings` / `Preferences`.
3. Go to `Plugins`.
4. Choose `Install Plugin from Disk...`.
5. Select `build/distributions/ij-mcp-<version>.zip`.
6. Restart IntelliJ IDEA if prompted.

Detailed install notes are in [Install IJ-MCP from disk](install-from-disk.md).

## 2. Open The Project

1. Open the target project in IntelliJ IDEA.
2. Open `Settings` / `Preferences` and search for `IJ-MCP` only if you want to
   inspect connection state or disable automatic setup.

Expected success signals in the settings page:

* `Server Status` shows `Running at http://127.0.0.1:<port>/mcp`
* `Connection` says Codex is ready for this project terminal
* `Plugin Build` and `Compatibility` both show a supported runtime
* `Target Identity`, `Project`, and `Endpoint` are populated
* the managed Codex config points `ij-mcp` at the local project endpoint

## 3. Launch Codex

In a shell at the project root:

```bash
codex
```

Expected result:

* Codex starts with the `ij-mcp` MCP server enabled
* asking Codex to open or reveal a file uses the IntelliJ project window you
  already opened
* no target id, pairing code, token export, or gateway process is needed

## Advanced Recovery

The companion CLI remains available for diagnostics and manual recovery:

```bash
./gradlew :cli:run --args='targets list'
./gradlew :cli:run --args='targets current'
./gradlew :cli:run --args='gateway config'
./gradlew :cli:run --args='gateway serve'
```

Use the advanced path only when the automatic connection panel reports a repair
state or when you are validating low-level routing behavior.

## Legacy Pairing Flow

The manual pairing flow is retained for recovery and compatibility testing.

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

## Legacy Gateway Flow

The standalone gateway remains useful when validating low-level routing or
connecting a non-Codex MCP client.

Print the stable gateway endpoint and bearer token:

```bash
./gradlew :cli:run --args='gateway config'
```

Start the gateway in a dedicated shell:

```bash
./gradlew :cli:run --args='gateway serve'
```

For Codex, prefer the managed config written by IJ-MCP during normal project
startup.

## Validate Direct MCP Access

Confirm that the companion CLI can route directly through the selected target:

```bash
./gradlew :cli:run --args='mcp tools-list'
```

Expected result:

* `tools/list` succeeds
* the available tools stay limited to IDE navigation and search behavior
* no implicit fallback occurs to another target

## Validate The End-To-End IDE Control Path

Run the repo validation script:

```bash
./scripts/validate-agent-gateway-flow.sh
```

Expected result:

* the script creates or updates `.ijmcp-agent-validation.txt`
* gateway health, `initialize`, `tools/list`, `reveal_file_in_project`, and
  `open_file` all succeed
* IntelliJ reveals and opens `.ijmcp-agent-validation.txt` in the selected
  project window

The coding agent owns file generation and editing; `IJ-MCP` owns IDE visibility
and navigation.

## Daily Operator Commands

Useful commands after setup:

```bash
./gradlew :cli:run --args='targets list'
./gradlew :cli:run --args='targets current'
./gradlew :cli:run --args='mcp tools-list'
```

## Related Guides

* [Install IJ-MCP from disk](install-from-disk.md)
* [Agent gateway setup](agent-gateway-setup.md)
* [Installed IntelliJ validation](installed-instance-validation.md)
* [Manual smoke verification](manual-smoke-verification.md)
* [Troubleshooting and recovery](troubleshooting-and-recovery.md)
* [Plugin lifecycle](plugin-lifecycle.md)
