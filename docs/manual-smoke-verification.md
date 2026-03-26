# Manual Smoke Verification

This checklist verifies the current `IJ-MCP` v1 surface against the documented transport and tool contract.

## Prerequisites

* Java 21+
* IntelliJ IDEA 2025.2-compatible runtime
* a local shell with `curl`

## 1. Launch the plugin sandbox

From the repo root:

```bash
./gradlew runIde
```

In the sandbox IDE, open a project you want to drive through MCP.

## 2. Configure the local server

1. Open IntelliJ `Settings` and search for `IJ-MCP`.
2. Check `Enable local MCP server`.
3. Leave the port at `8765` unless you need a different loopback port.
4. Click `Generate Token`.
5. Copy the staged token before clicking `Apply`.
6. Click `Apply` and confirm the status reads `Running at http://127.0.0.1:8765/mcp`.

For the shell examples below, export the values you configured:

```bash
export IJMCP_PORT=8765
export IJMCP_TOKEN='<copied-token>'
```

## 3. Verify lifecycle and discovery

Initialize:

```bash
curl -s "http://127.0.0.1:${IJMCP_PORT}/mcp" \
  -H "Authorization: Bearer ${IJMCP_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}'
```

List tools:

```bash
curl -s "http://127.0.0.1:${IJMCP_PORT}/mcp" \
  -H "Authorization: Bearer ${IJMCP_TOKEN}" \
  -H "MCP-Protocol-Version: 2025-11-25" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
```

Expected result:

* `initialize` returns `protocolVersion` `2025-11-25`
* `tools/list` returns all 8 v1 tools

## 4. Verify navigation tools

Open a file:

```bash
curl -s "http://127.0.0.1:${IJMCP_PORT}/mcp" \
  -H "Authorization: Bearer ${IJMCP_TOKEN}" \
  -H "MCP-Protocol-Version: 2025-11-25" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"open_file","arguments":{"path":"src/main/kotlin/ai/plyxal/ijmcp/app/IjMcpAppService.kt","line":1,"column":1}}}'
```

Read active editor context:

```bash
curl -s "http://127.0.0.1:${IJMCP_PORT}/mcp" \
  -H "Authorization: Bearer ${IJMCP_TOKEN}" \
  -H "MCP-Protocol-Version: 2025-11-25" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"get_active_editor_context","arguments":{}}}'
```

List tabs:

```bash
curl -s "http://127.0.0.1:${IJMCP_PORT}/mcp" \
  -H "Authorization: Bearer ${IJMCP_TOKEN}" \
  -H "MCP-Protocol-Version: 2025-11-25" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"list_open_tabs","arguments":{}}}'
```

## 5. Verify search tools

Search files:

```bash
curl -s "http://127.0.0.1:${IJMCP_PORT}/mcp" \
  -H "Authorization: Bearer ${IJMCP_TOKEN}" \
  -H "MCP-Protocol-Version: 2025-11-25" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"search_files","arguments":{"query":"IjMcpAppService.kt","limit":5}}}'
```

Search symbols:

```bash
curl -s "http://127.0.0.1:${IJMCP_PORT}/mcp" \
  -H "Authorization: Bearer ${IJMCP_TOKEN}" \
  -H "MCP-Protocol-Version: 2025-11-25" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"search_symbols","arguments":{"query":"applyConfiguredState","limit":5}}}'
```

Expected result:

* both calls return JSON-RPC success payloads
* successful search results include `structuredContent.results`
* misses return `result.isError: true` with `file_not_found` or `symbol_not_found`

## 6. Verify fail-closed behavior

Missing token should return HTTP `401`:

```bash
curl -i "http://127.0.0.1:${IJMCP_PORT}/mcp" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":8,"method":"initialize","params":{}}'
```

Outside-project file access should return a tool error:

```bash
curl -s "http://127.0.0.1:${IJMCP_PORT}/mcp" \
  -H "Authorization: Bearer ${IJMCP_TOKEN}" \
  -H "MCP-Protocol-Version: 2025-11-25" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"open_file","arguments":{"path":"/tmp/outside-project.txt"}}}'
```

Expected result:

* `structuredContent.status` is `error`
* `structuredContent.errorCode` is `outside_project` if the file exists outside the project, or `file_not_found` if it does not exist

If multiple IntelliJ projects are open, project-scoped tools should fail closed with `ambiguous_project`.
