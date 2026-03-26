# IJ-MCP

<!-- Plugin description -->
**IJ-MCP** is an IntelliJ IDEA plugin that will expose a local MCP server so a CLI agent can control safe IDE navigation and search workflows.

The current repository state provides the initial plugin scaffold:

* Gradle Kotlin DSL build using the IntelliJ Platform Gradle Plugin 2.x
* IntelliJ plugin metadata and package layout aligned with the architecture docs
* Gradle wrapper and local `runIde` workflow for plugin development
* placeholder Kotlin packages for `app`, `mcp`, `ide`, `settings`, and `model`
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
