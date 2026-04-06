# Plugin Lifecycle

This guide covers operator actions after the initial install:

* upgrade to a newer compatible `IJ-MCP` build
* downgrade to an older compatible `IJ-MCP` build
* uninstall `IJ-MCP` and clean up companion state

## Compatibility Baseline

For v1 rollout work:

* the plugin supports IntelliJ IDEA `2025.2.x`
* the plugin and companion CLI should use the same repository version by default
* mixed-version operation is unsupported unless it has been explicitly validated

Before any lifecycle change, confirm the replacement plugin zip and companion
CLI were built from the same intended version.

## Upgrade Workflow

Use this path when moving from one validated `2025.2.x` `IJ-MCP` build to a
newer validated `2025.2.x` build.

1. Build or obtain the replacement plugin zip:

```bash
./gradlew buildPlugin
```

2. Build the matching companion CLI distribution if the CLI is also being
updated:

```bash
./gradlew :cli:installDist
```

3. In IntelliJ IDEA, open `Settings` / `Preferences` and go to `Plugins`.
4. Use `Install Plugin from Disk...` and choose the new
   `build/distributions/ij-mcp-<version>.zip`.
5. Restart IntelliJ IDEA if prompted.
6. After restart, open a normal project window and search for `IJ-MCP` in
   `Settings` / `Preferences`.
7. Confirm:
   * `Plugin Build` shows the new version
   * `Compatibility` still reports a supported IDE build
   * `Server Status` returns to `Running at http://127.0.0.1:<port>/mcp`

### Upgrade Expectations

For a normal upgrade within the supported range:

* the persisted plugin settings in `ijmcp.xml` should be reused
* the target registry at `~/.ij-mcp/targets.json` should repopulate when the
  project window starts
* existing target credentials may require re-pairing if the running target IDs
  change across restart or if credentials were reset during debugging

If the CLI cannot reconnect after upgrade, run:

```bash
./gradlew :cli:run --args='targets list'
./gradlew :cli:run --args='targets select <targetId>'
./gradlew :cli:run --args='targets pair --code <pairingCode>'
```

## Downgrade Workflow

Use this path only when you need to move back to an earlier validated build
within the same supported IntelliJ IDEA range.

1. Install the older plugin zip with `Install Plugin from Disk...`.
2. Replace the companion CLI with the matching older build.
3. Restart IntelliJ IDEA.
4. Open the target project window and check the `IJ-MCP` settings page.
5. Confirm:
   * `Plugin Build` shows the expected downgraded version
   * `Compatibility` still reports a supported IDE build
   * `Server Status` returns to `Running at ...`

### Downgrade Caveats

Downgrades are supported only when:

* the replacement plugin still targets IntelliJ IDEA `2025.2.x`
* the operator also aligns the companion CLI version

After a downgrade, assume the safest recovery path is:

* reselect the active target with `targets select <targetId>`
* re-pair if the CLI reports missing or invalid credentials

## Uninstall Workflow

1. Close active agent or CLI sessions that are using `IJ-MCP`.
2. In IntelliJ IDEA, open `Settings` / `Preferences` and go to `Plugins`.
3. Locate `IJ-MCP`.
4. Uninstall the plugin.
5. Restart IntelliJ IDEA if prompted.

At this point the plugin is removed from the IDE, but local companion state may
still remain.

## Optional Local Cleanup

If you want a clean uninstall on the local machine, remove companion state as
well.

### CLI sticky target and cached credentials

The companion CLI stores sticky target state and cached bearer tokens in:

```text
~/.ij-mcp/client-state.json
```

You can also clear one target through the CLI before deleting the file:

```bash
./gradlew :cli:run --args='targets forget'
```

### Target registry snapshot

The active target registry is stored at:

```text
~/.ij-mcp/targets.json
```

This file is repopulated by running project windows. After uninstall, it can be
removed if no remaining `IJ-MCP` instance is expected to recreate it.

### IntelliJ-side settings

The application-level plugin settings are stored in IntelliJ as:

```text
ijmcp.xml
```

That file lives under the IntelliJ options/config directory for the active IDE
profile. In most cases you should leave it alone unless you need a fully clean
support reset.

### IntelliJ password store entries

The plugin stores target bearer tokens in the IntelliJ password store. If you
need a deep cleanup for support or testing, reset pairing before uninstalling or
clear the corresponding entries from the IDE password store.

## Recommended Support Posture

When lifecycle problems occur, use this order:

1. Align plugin and companion CLI to the same version.
2. Confirm IntelliJ IDEA is still in the supported `2025.2.x` range.
3. Reopen the project window and inspect the `IJ-MCP` settings page.
4. Re-select and re-pair the target from the CLI if needed.
