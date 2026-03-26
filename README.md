# IJ-MCP

<!-- Plugin description -->
**IJ-MCP** is an IntelliJ IDEA plugin that exposes a local MCP server so a CLI agent can control safe IDE navigation and search workflows.

The current repository state includes:

* Streamable HTTP MCP transport bound to loopback with bearer-token auth
* persisted plugin settings and secure token storage
* IntelliJ-backed navigation tools for files, tabs, project reveal, and active editor context
* IntelliJ-backed file and symbol search tools with project-scope enforcement
* automated tests for transport behavior and project-scoped search/path verification
<!-- Plugin description end -->

## Development

Prerequisites:

* Java 21+ installed locally

Useful commands:

```bash
./gradlew buildPlugin
./gradlew runIde
./gradlew test
```

## Repository Status

The v1 implementation currently covers:

* MCP lifecycle and `tools/list` / `tools/call`
* local server settings and token management
* `open_file`, `focus_tab`, `list_open_tabs`, `close_tab`, `reveal_file_in_project`, and `get_active_editor_context`
* `search_files` and `search_symbols`
* fail-closed behavior for missing auth, ambiguous project selection, and outside-project file access

## Verification

Automated verification:

```bash
./gradlew test buildPlugin
./gradlew runIde --dry-run
```

Manual verification:

* [Manual smoke verification](docs/manual-smoke-verification.md)

## Project Layout

```text
src/main/kotlin/ai/plyxal/ijmcp/
  app/
  ide/
  mcp/
  model/
  settings/

src/main/resources/META-INF/
  plugin.xml

src/test/kotlin/ai/plyxal/ijmcp/
```

## References

* PRD: https://plyxal.atlassian.net/wiki/spaces/IM/pages/4096001
* Architecture: https://plyxal.atlassian.net/wiki/spaces/IM/pages/4128769
* MCP Contract: https://plyxal.atlassian.net/wiki/spaces/IM/pages/4292610
