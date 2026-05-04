# Agent Gateway Setup

This guide covers the operator path for connecting a CLI coding agent to the
local `IJ-MCP` gateway and validating the create-or-change then open-or-reveal
workflow.

The validated CLI example in this guide uses Codex CLI.

## Prerequisites

* `IJ-MCP` is installed or running in the Gradle sandbox
* a project window is open and `Enable local MCP server` is on
* the selected target has already been paired through `targets pair --code ...`
* Codex CLI is installed locally if you want to follow the verified example

Before continuing, confirm the direct companion path works:

```bash
./gradlew :cli:run --args='targets current'
./gradlew :cli:run --args='mcp tools-list'
```

## 1. Inspect Gateway Settings

Print the stable loopback endpoint and bearer token:

```bash
./gradlew :cli:run --args='gateway config'
```

Expected output:

```text
endpointUrl=http://127.0.0.1:3765/mcp
healthUrl=http://127.0.0.1:3765/health
gatewayBearerToken=<stable token>
selectedTargetId=<targetId>
```

Export the token for agent configuration:

```bash
export IJ_MCP_GATEWAY_TOKEN='<token from gateway config>'
```

## 2. Start The Gateway

In a dedicated shell:

```bash
./gradlew :cli:run --args='gateway serve'
```

Expected output:

```text
IJ-MCP gateway listening on http://127.0.0.1:3765/mcp
```

The gateway health endpoint should then respond:

```bash
curl -fsS http://127.0.0.1:3765/health
```

Expected result:

* `routingMode` is `sticky-selected-target`
* `selectedTargetId` matches `targets current`
* `requiresAuth` is `true`

Keep this process running while the coding agent is active. Agent MCP calls
should go through this stable endpoint instead of repeated
`./gradlew :cli:run --args='mcp call ...'` commands; the gateway keeps the JVM
warm and caches the selected healthy target briefly after initialization.

## 3. Add The Verified Codex CLI MCP Entry

The verified Codex CLI command shape is:

```bash
codex mcp add ij-mcp \
  --url http://127.0.0.1:3765/mcp \
  --bearer-token-env-var IJ_MCP_GATEWAY_TOKEN
```

Inspect it:

```bash
codex mcp get ij-mcp
```

Expected result:

```text
ij-mcp
  enabled: true
  transport: streamable_http
  url: http://127.0.0.1:3765/mcp
  bearer_token_env_var: IJ_MCP_GATEWAY_TOKEN
```

The resulting Codex config entry is:

```toml
[mcp_servers.ij-mcp]
url = "http://127.0.0.1:3765/mcp"
bearer_token_env_var = "IJ_MCP_GATEWAY_TOKEN"
```

Validation note:

* this command path was verified locally in an isolated temp home with
  `HOME=/tmp/ijmcp-codex-home codex mcp add ...`
* `HOME=/tmp/ijmcp-codex-home codex mcp get ij-mcp` returned the expected
  streamable HTTP configuration above

## 4. Validate Create-Or-Change Then Open-Or-Reveal

Use the validation script from the repo root:

```bash
export IJ_MCP_GATEWAY_TOKEN='<token from gateway config>'
./scripts/validate-agent-gateway-flow.sh
```

What it does:

* creates or updates `.ijmcp-agent-validation.txt` in the repo workspace
* checks gateway health
* sends `initialize`
* sends `tools/list`
* sends `reveal_file_in_project`
* sends `open_file`

Expected result:

* the script prints success for gateway health and MCP calls
* `.ijmcp-agent-validation.txt` is updated locally
* IntelliJ reveals and opens that file in the selected project window

This mirrors the rollout workflow: the coding agent creates or edits the file
using its normal workspace tools, then uses IJ-MCP only for IDE reveal/open.

## 5. Operator Notes

* The gateway never chooses another target implicitly. If the sticky target is
  stale, unreachable, or requires re-pairing, the gateway returns explicit
  JSON-RPC error data with a recovery code and action.
* `gateway config` prints the stable endpoint and token; agents should not be
  configured against the per-window IntelliJ endpoint directly.
* If the selected target changes, restart or re-run the validation flow to
  confirm the gateway still points at the intended project window.
