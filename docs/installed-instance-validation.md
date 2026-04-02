# Installed IntelliJ Validation

This note captures the repeatable validation path for `IJMCP-45`.

## What It Validates

The installed-instance validation script checks that `IJ-MCP` works when loaded
by the real IntelliJ IDEA application, not only in the Gradle sandbox.

It validates:

* the built plugin zip can be unpacked as an installed plugin
* IntelliJ IDEA starts with the plugin present in a normal application launch
* the persisted `IJ-MCP` settings state enables the local MCP server
* a project window registers an `IJ-MCP` target into `~/.ij-mcp/targets.json`
* the runtime starts and logs a loopback MCP endpoint

## How To Run It

From the repo root:

```bash
./scripts/validate-installed-idea.sh
```

Optional overrides:

* `IDEA_APP=/Applications/IntelliJ IDEA.app`
* `PLUGIN_ZIP=/path/to/ij-mcp-<version>.zip`
* `PROJECT_PATH=/path/to/project`
* `STARTUP_TIMEOUT=90`

## Isolation Model

The script launches the installed IntelliJ application with temporary:

* config state
* system state
* plugins directory
* log directory

It does not depend on the Gradle `runIde` sandbox.

The script temporarily clears `~/.ij-mcp/targets.json` for the duration of the
validation and restores its prior contents on exit.

## Success Signals

The validation passes only when all of the following are true:

* the installed IDE log contains `Registered IJ-MCP target`
* the installed IDE log contains `IJ-MCP target ... started on http://127.0.0.1:.../mcp`
* the registry file contains the launched project path

## Output

On success, the script prints:

* the temporary work root
* the installed IDE log path
* the restored registry file path
* the matching startup log lines
* the target registry snapshot observed during validation

On failure, the script prints the last installed IDE log lines to help isolate
installed-only startup defects.
