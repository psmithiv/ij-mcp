# V1 Release Readiness Checklist

This checklist is the final acceptance gate for the `IJ-MCP` v1 rollout.

Release may proceed only when every applicable item below is explicitly true.

## Release Scope

The approved v1 scope is:

* install `IJ-MCP` into a normal IntelliJ IDEA `2025.2.x` instance
* enable a project-window-scoped local MCP target
* pair the companion CLI to exactly one selected target
* expose a stable local gateway for a CLI coding agent
* let the coding agent reveal and open files in the selected IDE window
* fail closed when selection, pairing, auth, or runtime health is invalid

## 1. Build And Compatibility Gate

- [ ] `./gradlew :cli:test :cli:installDist test buildPlugin` passes from the repo root.
- [ ] The built plugin artifact exists at `build/distributions/ij-mcp-<version>.zip`.
- [ ] The companion CLI distribution exists at `cli/build/install/cli/`.
- [ ] Plugin metadata still targets IntelliJ IDEA `2025.2.x`.
- [ ] The plugin and companion CLI version are aligned for the intended release.

## 2. Installed IntelliJ Gate

- [ ] The plugin installs through `Install Plugin from Disk...`.
- [ ] A real project window can enable `Enable local MCP server` and start the target.
- [ ] The settings page shows current `Plugin Build`, `Compatibility`, `Operator Guidance`, `Registry Status`, `Registry File`, `Registry Entry`, `Runtime Identity`, and `Last Error`.
- [ ] The installed instance registers a live target entry in `~/.ij-mcp/targets.json`.

Reference:

* [Install IJ-MCP from disk](install-from-disk.md)
* [Installed IntelliJ validation](installed-instance-validation.md)

## 3. Direct CLI Routing Gate

- [ ] `targets list` shows active project-window targets.
- [ ] `targets select <targetId>` marks the intended target as sticky.
- [ ] `targets current` reports the correct `routeStatus` and recovery fields for selected, unpaired, stale, and repair-required states.
- [ ] `targets pair --code <pairingCode>` succeeds against the intended target.
- [ ] `mcp tools-list` succeeds after pairing and stays bound to the selected target only.

## 4. Coding-Agent Gateway Gate

- [ ] `gateway config` reports the stable endpoint, token, selected target, and route status.
- [ ] `gateway serve` exposes a healthy local gateway on `http://127.0.0.1:3765/mcp`.
- [ ] The gateway health endpoint reports `routingMode=sticky-selected-target`.
- [ ] A validated coding-agent client can connect through the gateway, not the per-window IntelliJ endpoint.
- [ ] `./scripts/validate-agent-gateway-flow.sh` succeeds and IntelliJ reveals and opens the validation file in the selected project window.

Reference:

* [Operator setup guide](operator-setup-guide.md)
* [Agent gateway setup](agent-gateway-setup.md)

## 5. Recovery And Lifecycle Gate

- [ ] `Reset CLI Access` invalidates the prior credential and forces a true repair flow.
- [ ] The CLI surfaces `recoveryCode` and `recoveryAction` for unselected, stale, unreachable, stopped, unpaired, and repair-required states.
- [ ] The gateway fails closed when the selected target is unhealthy.
- [ ] Upgrade, downgrade, and uninstall guidance exists and matches the current compatibility policy.

Reference:

* [Troubleshooting and recovery](troubleshooting-and-recovery.md)
* [Plugin lifecycle](plugin-lifecycle.md)

## 6. Documentation Gate

- [ ] Product, architecture, and engineering pages in Confluence reflect the final rollout model.
- [ ] The Confluence operator setup guide exists and matches the repo-backed guide.
- [ ] The Confluence troubleshooting and recovery guide exists and matches the repo-backed guide.
- [ ] Manual smoke verification steps match the current UI text and CLI route-status behavior.
- [ ] The repo README links to the current operator runbooks.

## 7. Final Sign-Off Gate

- [ ] Product acceptance: the supported operator journey is documented clearly enough for review and release.
- [ ] Engineering acceptance: the implementation matches the documented architecture and compatibility boundaries.
- [ ] Operator acceptance: the installed-plugin, CLI, and gateway workflow has been exercised end to end.
- [ ] Release owner accepts the evidence bundle for build, install, pairing, gateway, and recovery validation.

## Suggested Evidence Bundle

Capture these artifacts before declaring the release ready:

* the passing build command output for `:cli:test :cli:installDist test buildPlugin`
* the installed-instance validation result
* the manual smoke verification result
* the gateway validation result
* links to the Confluence operator and troubleshooting pages
* the release candidate version identifiers for both plugin and companion CLI
