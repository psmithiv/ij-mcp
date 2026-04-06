# Troubleshooting And Recovery

This guide covers the supported recovery paths for the v1 `IJ-MCP` rollout.

Use it when:

* the plugin does not register a usable target
* the companion CLI cannot route to the selected target
* pairing or re-pairing fails
* the coding-agent gateway reports an unhealthy or unrecoverable state

## Start With The Right Signals

When diagnosing a problem, inspect both surfaces:

### IntelliJ plugin settings

Open `Settings` / `Preferences` and search for `IJ-MCP`.

Check these fields first:

* `Operator Guidance`
* `Server Status`
* `Pairing Status`
* `Registry Status`
* `Registry File`
* `Registry Entry`
* `Runtime Identity`
* `Last Error`

### Companion CLI

From the repo root, check:

```bash
./gradlew :cli:run --args='targets list'
./gradlew :cli:run --args='targets current'
./gradlew :cli:run --args='gateway config'
```

`targets current` is the fastest route-health summary. When routing is broken, it
returns both a `routeStatus` and a `recoveryCode`.

## Recovery Codes

The CLI and gateway surface these recovery codes:

* `no_selection`: no sticky target is selected
* `stale_target`: the selected target is no longer published in the registry
* `target_unreachable`: the selected target is still registered, but the local
  HTTP endpoint cannot be reached
* `target_not_running`: the selected target is registered, but the runtime
  reports `running=false`
* `pairing_required`: the target is live, but the CLI has no usable credential
* `repair_required`: the target is live, but the stored credential is stale or
  was invalidated by reset
* `initialize_failed`: the target answered, but MCP initialization failed

Treat the `recoveryAction` value as the first-line operator instruction.

## Common Problems

### 1. No usable target appears in the plugin

Symptoms:

* `Target: none detected`
* `Project: open a project window to register a target`
* `Last Error` says no open IntelliJ project window is available

Recovery:

1. Open a normal project window from disk.
2. Reopen the `IJ-MCP` settings page.
3. Click `Apply`.

### 2. The server will not start

Symptoms:

* `Server Status` remains stopped
* `Last Error` is populated
* `Operator Guidance` tells you to inspect diagnostics

Recovery:

1. Read `Last Error`.
2. Correct the underlying project or environment issue.
3. Click `Apply` again.

Known startup-specific case:

* If `Last Error` says `The project window does not expose a resolvable base path.`,
  reopen the project from a real filesystem path before retrying.

### 3. The preferred port is unavailable

Symptoms:

* the target is running, but the active port is not the configured port
* `Operator Guidance` warns that the preferred port was unavailable

Recovery:

1. Accept the fallback loopback port if the target is otherwise healthy.
2. Re-discover the target from the CLI:

```bash
./gradlew :cli:run --args='targets list'
./gradlew :cli:run --args='targets select <targetId>'
```

3. Re-pair if any external client was bound to the old endpoint assumptions.

### 4. No sticky target is selected

Symptoms:

* `targets current` returns `routeStatus=unselected`
* `recoveryCode=no_selection`

Recovery:

```bash
./gradlew :cli:run --args='targets list'
./gradlew :cli:run --args='targets select <targetId>'
```

### 5. The sticky target is stale

Symptoms:

* `targets current` returns `routeStatus=stale_selection`
* `recoveryCode=stale_target`

Recovery:

```bash
./gradlew :cli:run --args='targets list'
./gradlew :cli:run --args='targets select <targetId>'
```

If the expected project window is missing, reopen it in IntelliJ first.

### 6. The target requires first-time pairing

Symptoms:

* `targets current` returns `routeStatus=selected_unpaired`
* `recoveryCode=pairing_required`
* direct MCP calls fail before routing

Recovery:

1. In the plugin UI, click `Generate Pairing Code`.
2. Run:

```bash
./gradlew :cli:run --args='targets pair --code <pairingCode>'
```

3. Re-run:

```bash
./gradlew :cli:run --args='targets current'
./gradlew :cli:run --args='mcp tools-list'
```

### 7. The target requires repair after reset or auth drift

Symptoms:

* `targets current` returns `routeStatus=selected_repair_required`
* `recoveryCode=repair_required`
* initialization fails with unauthorized behavior

Recovery:

1. In the plugin UI, click `Generate Pairing Code`.
2. Run:

```bash
./gradlew :cli:run --args='targets pair --code <pairingCode>'
```

3. Confirm routing works again:

```bash
./gradlew :cli:run --args='mcp tools-list'
```

`Reset CLI Access` intentionally invalidates the old credential. Repair always
means re-pair with a fresh one-time code.

### 8. The target is unreachable or stopped

Symptoms:

* `recoveryCode=target_unreachable` or `recoveryCode=target_not_running`
* gateway health fails or direct CLI calls fail

Recovery:

1. Confirm the project window is still open.
2. Return to the plugin settings page and inspect `Last Error`.
3. If the target is stopped, click `Apply` or `Refresh Targets`.
4. If the window was closed, reopen it.
5. Re-run:

```bash
./gradlew :cli:run --args='targets list'
./gradlew :cli:run --args='targets current'
```

6. Re-select the intended target if the registry entry changed.

### 9. The gateway does not look healthy

Symptoms:

* `gateway config` reports a broken `routeStatus`
* the health endpoint is unavailable
* the agent cannot call `tools/list`

Recovery:

1. Check the selected-target state first:

```bash
./gradlew :cli:run --args='targets current'
```

2. Repair that state before debugging the gateway itself.
3. Reprint the gateway config:

```bash
./gradlew :cli:run --args='gateway config'
```

4. Restart the gateway process:

```bash
./gradlew :cli:run --args='gateway serve'
```

5. Recheck health:

```bash
curl -fsS http://127.0.0.1:3765/health
```

The gateway is intentionally fail-closed. It will not silently route to another
target when the selected route is stale or unhealthy.

### 10. The coding agent is pointed at the wrong endpoint

Symptoms:

* the agent is configured against a per-window IntelliJ endpoint
* agent calls break after target restart or reselection

Recovery:

Always configure the coding agent against the stable gateway endpoint:

```bash
./gradlew :cli:run --args='gateway config'
codex mcp add ij-mcp \
  --url http://127.0.0.1:3765/mcp \
  --bearer-token-env-var IJ_MCP_GATEWAY_TOKEN
```

Do not configure the agent directly against `http://127.0.0.1:<dynamicPort>/mcp`.

### 11. Upgrade, downgrade, or uninstall recovery

If a lifecycle change breaks routing:

1. Confirm IntelliJ IDEA is still within the supported `2025.2.x` range.
2. Align the plugin and companion CLI to the same validated version.
3. Reopen the project window.
4. Re-run `targets list` and `targets select <targetId>`.
5. Re-pair if the target ID or stored credential changed.

Detailed lifecycle steps are in [Plugin lifecycle](plugin-lifecycle.md).

## Escalation Path

If the standard recovery path fails:

1. Capture the plugin diagnostics fields from the `IJ-MCP` settings page.
2. Capture the output of:

```bash
./gradlew :cli:run --args='targets current'
./gradlew :cli:run --args='gateway config'
```

3. Note the exact `routeStatus`, `recoveryCode`, and `Last Error`.
4. Reproduce the failure with the smallest possible project-window setup.

## Related Guides

* [Operator setup guide](operator-setup-guide.md)
* [Agent gateway setup](agent-gateway-setup.md)
* [Plugin lifecycle](plugin-lifecycle.md)
* [Manual smoke verification](manual-smoke-verification.md)
