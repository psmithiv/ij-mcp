# IJ-MCP

<!-- Plugin description -->
**IJ-MCP** is an IntelliJ IDEA plugin plus local CLI that expose safe, project-window-scoped MCP targets for IDE navigation and search workflows.

The current repository state includes:

* one loopback MCP endpoint per open IntelliJ project window
* local target discovery through `~/.ij-mcp/targets.json`
* pair-once authentication with target-scoped credentials
* a local CLI for sticky target selection, pairing, and MCP request routing
* IntelliJ-backed navigation tools for files, tabs, project reveal, and active editor context
* IntelliJ-backed file and symbol search tools with project-scope enforcement
* automated tests for transport behavior, registry updates, auth isolation, and project-scoped search/path verification
<!-- Plugin description end -->

## Development

Prerequisites:

* Java 21+ installed locally

Useful commands:

```bash
./gradlew buildPlugin
./gradlew runIde
./gradlew test
./gradlew :cli:installDist
./gradlew :cli:run --args='help'
```

## Release Artifacts

Versioned build outputs:

* plugin zip: `build/distributions/ij-mcp-<version>.zip`
* companion CLI install: `cli/build/install/cli/`

The plugin zip is the install-from-disk artifact for IntelliJ IDEA. The
companion CLI is the local operator surface for target discovery, pairing, and
request routing.

## Install In IntelliJ IDEA

To install `IJ-MCP` into a normal IntelliJ IDEA instance:

* build `build/distributions/ij-mcp-<version>.zip`
* install the zip with `Install Plugin from Disk...`
* open a project window, search `Settings` / `Preferences` for `IJ-MCP`, and
  enable `Enable local MCP server`

Detailed steps:

* [Install IJ-MCP from disk](docs/install-from-disk.md)

## Compatibility

The current rollout policy is intentionally narrow:

* IntelliJ IDEA compatibility is limited to `2025.2.x`
* plugin metadata encodes that range as `sinceBuild = 252` and `untilBuild = 252.*`
* the plugin and companion CLI should be run at the same repository version unless
  a mixed-version combination has been explicitly validated

Detailed policy:

* [Release artifact and compatibility](docs/release-artifact-and-compatibility.md)

## Repository Status

The v1 implementation currently covers:

* one MCP target per IntelliJ project window
* local registry discovery and sticky CLI target selection
* pair-once authentication with per-target bearer tokens
* MCP lifecycle and `tools/list` / `tools/call`
* `open_file`, `focus_tab`, `list_open_tabs`, `close_tab`, `reveal_file_in_project`, and `get_active_editor_context`
* `search_files` and `search_symbols`
* fail-closed behavior for missing auth, stale target selection, and outside-project file access

## CLI

The repo now includes a dedicated `cli` subproject for local target discovery and MCP routing.

Supported commands:

```bash
./gradlew :cli:run --args='targets list'
./gradlew :cli:run --args='targets current'
./gradlew :cli:run --args='targets select <targetId>'
./gradlew :cli:run --args='targets pair --code <pairingCode> [targetId]'
./gradlew :cli:run --args='targets forget [targetId]'
./gradlew :cli:run --args='mcp tools-list'
./gradlew :cli:run --args='mcp call <toolName> [jsonArguments]'
```

## Verification

Automated verification:

```bash
./gradlew :cli:test :cli:installDist test buildPlugin
./gradlew runIde --dry-run
./gradlew :cli:run --args='help'
```

Manual verification:

* [Install IJ-MCP from disk](docs/install-from-disk.md)
* [Plugin lifecycle](docs/plugin-lifecycle.md)
* [Installed IntelliJ validation](docs/installed-instance-validation.md)
* [Manual smoke verification](docs/manual-smoke-verification.md)

## Project Layout

```text
src/main/kotlin/ai/plyxal/ijmcp/
  app/
  ide/
  mcp/
  model/
  settings/

cli/src/main/kotlin/ai/plyxal/ijmcp/cli/

src/main/resources/META-INF/
  plugin.xml

src/test/kotlin/ai/plyxal/ijmcp/
```

## References

* PRD: https://plyxal.atlassian.net/wiki/spaces/IM/pages/4096001
* Architecture: https://plyxal.atlassian.net/wiki/spaces/IM/pages/4128769
* MCP Contract: https://plyxal.atlassian.net/wiki/spaces/IM/pages/4292610
