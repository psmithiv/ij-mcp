# Release Artifact And Compatibility

This document defines the versioned artifacts and compatibility policy for the
first operator-ready `IJ-MCP` rollout.

## Release Artifacts

The release is made of two local artifacts that share the same semantic version.

### IntelliJ plugin artifact

Build command:

```bash
./gradlew buildPlugin
```

Produced artifact:

```text
build/distributions/ij-mcp-<version>.zip
```

This zip is the install-from-disk artifact for IntelliJ IDEA.

### Companion CLI artifact

Build command:

```bash
./gradlew :cli:installDist
```

Produced artifact:

```text
cli/build/install/cli/
```

This distribution is the operator companion surface for target discovery,
pairing, and direct MCP routing. Later tickets will extend it with the stable
agent-facing gateway mode.

## IntelliJ Compatibility Policy

The v1 rollout supports IntelliJ IDEA `2025.2.x` only.

The compatibility boundary is encoded in plugin metadata as:

* `sinceBuild = 252`
* `untilBuild = 252.*`

The build currently targets IntelliJ IDEA Community `2025.2.6.1` during local
development and verification. Any expansion beyond `2025.2.x` should be handled
as an explicit compatibility task with validation in a real installed instance.

## Plugin And Companion Version Policy

For v1 rollout work:

* the plugin zip and companion CLI ship from the same repository version
* the operator should use matching plugin and companion versions by default
* mixed-version operation is unsupported unless explicitly validated and
  documented in a later compatibility task

This keeps rollout and support straightforward while the agent gateway surface
is still stabilizing.

## Operator Expectation

An operator should install the plugin zip that matches the companion CLI version
used for pairing and routing. If the versions do not match, the default support
action is to align both components to the same release before deeper debugging.
