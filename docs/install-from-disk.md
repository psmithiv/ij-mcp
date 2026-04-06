# Install IJ-MCP From Disk

This guide covers the operator flow for installing the built `IJ-MCP` plugin
into a normal IntelliJ IDEA instance and enabling the local MCP target for a
project window.

## Prerequisites

* IntelliJ IDEA `2025.2.x`
* Java 21+
* a built plugin zip at `build/distributions/ij-mcp-<version>.zip`
* a local project you can open in IntelliJ IDEA

Build the plugin artifact from the repo root if needed:

```bash
./gradlew buildPlugin
```

Expected output:

```text
build/distributions/ij-mcp-<version>.zip
```

## Install The Plugin Zip

1. Open IntelliJ IDEA.
2. Open `Settings` / `Preferences`.
3. Go to `Plugins`.
4. Open the plugins menu and choose `Install Plugin from Disk...`.
5. Select `build/distributions/ij-mcp-<version>.zip`.
6. Accept the install prompt.
7. Restart IntelliJ IDEA if prompted.

The plugin is installed at the IDE level, but the MCP target only becomes
useful inside a real project window.

## Enable IJ-MCP In A Project Window

1. Open the target project in IntelliJ IDEA.
2. Open `Settings` / `Preferences`.
3. Search for `IJ-MCP`.
4. Open the `IJ-MCP` settings page.
5. Check `Enable local MCP server`.
6. Leave `Preferred Port` at `8765` unless you are intentionally overriding it.
7. Click `Apply`.

## Expected Success Signals

After `Apply`, the `IJ-MCP` settings page should show:

* `Server Status` as `Running at http://127.0.0.1:<port>/mcp`
* `Plugin Build` with the installed plugin version
* `Compatibility` with the current IDE build and supported range
* `Operator Guidance` with the next recommended action for the current state
* `Target Identity` populated with a `targetId`
* `Project` populated with the project name and base path
* `Endpoint` populated with the active loopback URL
* `Pairing Status` as `pairing required` until a CLI pairs successfully
* `Registry Status` as `registered at ...`

The `Diagnostics` area should also show the current target metadata, endpoint,
and local registry file path.

## Common Failure Signals

### No project target is available

If the page shows:

* `Target: none detected`
* `Project: open a project window to register a target`

then the plugin is installed, but there is no eligible project-window target
available yet. Open a normal project window and refresh the page.

### The server does not start

If `Server Status` or `Endpoint` stays stopped, check `Diagnostics` for
`lastError`.

Also check `Operator Guidance`, which should distinguish whether the problem is
caused by missing project configuration, compatibility drift, or a runtime
startup failure.

One known stop condition is:

```text
The project window does not expose a resolvable base path.
```

That means the current window is not backed by a normal project path.

### The preferred port is unavailable

If port `8765` is busy, the runtime falls back to an ephemeral loopback port.
This is not a failure if `Server Status` still shows `Running at ...`.

### Pairing cannot start

If `Generate Pairing Code` yields `Pairing code expiry: target unavailable`,
refresh the target state and confirm the server is running before retrying.

## Next Step

Once the plugin is installed and enabled, continue with:

* [Operator setup guide](operator-setup-guide.md)
* [Manual smoke verification](manual-smoke-verification.md)
