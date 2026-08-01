# SUNDER

[![CI](https://github.com/muhamed97/sunder-burp-mcp/actions/workflows/ci.yml/badge.svg)](https://github.com/muhamed97/sunder-burp-mcp/actions/workflows/ci.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

**SUNDER** is an autonomous offensive MCP operator for Burp Suite. It turns an
MCP-capable AI assistant into a second operator inside Burp, preserves the tools
from PortSwigger's MCP extension, adds a session-only live feed across Burp
tools, and adds action tools for replaying, editing, sending, organizing,
crawling, and auditing requests.

The extension does **not** create a traffic database. HTTP exchanges and
WebSocket messages are held only in memory for the current extension session
and disappear when the extension is unloaded or Burp closes. Burp's own project
and history remain the source of truth.

Use this extension only on systems you own or are explicitly authorized to
test.

## What it does

- Captures HTTP requests and responses emitted by every Burp tool through the
  Montoya API.
- Captures text and binary WebSocket messages from Burp-created connections.
- Imports the Proxy history already present when the extension loads, then
  continues with live events.
- Lets an AI poll one combined HTTP/WebSocket event stream using independent
  cursors.
- Searches and retrieves complete exchanges without putting the whole session
  into the model context.
- Lets an AI edit and send requests directly through Burp, replay stored
  requests, send batches, create Repeater tabs, add Organizer entries, and
  manage cookies.
- On Burp Suite Professional, lets an AI start, inspect, and stop crawl and
  audit tasks.
- Preserves the official request, Repeater, Intruder, Collaborator, Proxy,
  configuration, encoding, and utility tools.
- Protects the MCP endpoint with a random bearer/API-key token by default.

## Autonomous safety model

When **Autonomous agent mode** is enabled, the extension does not show manual
approval dialogs. An outgoing target must instead be either:

1. in Burp's suite scope; or
2. matched by an explicit MCP auto-approve target.

Requests outside both boundaries are rejected. The AI can check scope and
remove URLs from scope, but it cannot add new targets to scope through MCP. This
prevents the agent from expanding its own authority.

The MCP server listens on `127.0.0.1:9987` by default. Authentication is enabled
by default and accepts either:

```text
Authorization: Bearer <token>
```

or:

```text
X-API-Key: <token>
```

## Session-only live feed

The extension creates no traffic database and does not persist captured
requests. The **SUNDER Traffic** tab displays the in-memory session buffer.
You can clear that buffer without deleting or changing Burp's own history.

`Maximum session events` defaults to `0`, meaning unlimited for the current
session. This avoids an artificial history offset but may consume substantial
RAM during long or high-volume tests. Set a positive value to retain only the
newest events in the extension buffer.

MCP is request/response based. The extension captures events immediately, but
an AI client must repeatedly call `agent_get_events` to notice new traffic.
The extension cannot wake an idle Claude, Codex, or other MCP client by itself.

## MCP tools added by SUNDER

### Live traffic

| Tool | Purpose |
| --- | --- |
| `agent_get_events` | Poll combined HTTP and WebSocket events with independent cursors |
| `traffic_get_latest` | Return compact metadata for recent HTTP exchanges |
| `traffic_get_since` | Return HTTP exchanges after a sequence number |
| `traffic_search` | Search URLs, hosts, methods, requests, and responses |
| `traffic_get_exchange` | Retrieve one complete exchange; sensitive headers are redacted by default |
| `traffic_list_endpoints` | Aggregate endpoints and response variants |
| `traffic_get_changed_endpoints` | Find endpoints whose responses changed after a sequence |
| `traffic_create_repeater_tab` | Create a Repeater tab from a captured or edited request |
| `traffic_statistics` | Show session counts, memory estimate, cursors, and dropped events |
| `web_socket_get_latest` | Retrieve WebSocket message metadata incrementally |
| `web_socket_search` | Search WebSocket URLs and text payloads |
| `web_socket_get_message` | Retrieve a complete text or Base64 binary message |

Metadata calls are capped to keep responses manageable, while full messages are
retrieved only when needed. Full-message responses are capped at 2 MB.

### Autonomous actions

| Tool | Purpose |
| --- | --- |
| `request_replay` | Replay a captured request through Burp |
| `request_modify_and_send` | Edit a captured request and send it through Burp |
| `request_send_batch` | Send up to 25 requests in one operation |
| `scope_check` | Check whether a URL is in Burp scope |
| `scope_exclude` | Remove a URL from Burp scope |
| `session_get_cookies` | Read Burp's cookie jar |
| `session_set_cookie` | Add or update a cookie for an authorized target |
| `organizer_add_exchange` | Add a captured exchange to Organizer |
| `scanner_start_audit` | Start a Burp Professional audit |
| `scanner_start_crawl` | Start a Burp Professional crawl |
| `scanner_task_status` | Inspect an AI-started Scanner task |
| `scanner_task_stop` | Stop an AI-started Scanner task |

The Montoya API can prepare a request in Intruder but does not expose a way to
press Intruder's attack-start button. Repeater tabs are available for visibility
and inspection; autonomous sending uses Burp's HTTP stack directly.

## Build and install

Requirements: Java 21 and a working `jar` command.

On Windows:

```powershell
.\gradlew.bat embedProxyJar
```

On Linux or macOS:

```bash
./gradlew embedProxyJar
```

Load `build/libs/sunder-all.jar` in Burp under
**Extensions → Installed → Add**, with extension type **Java**.

In the **SUNDER** tab:

1. Keep authentication enabled and copy or rotate the generated token.
2. Define Burp scope and/or explicit auto-approve targets.
3. Enable Autonomous agent mode.
4. Enable the MCP server.
5. Configure the client using the built-in Claude installer, the SSE endpoint,
   or the bundled stdio proxy.

The default SSE endpoint is:

```text
http://127.0.0.1:9987
```

The bundled Claude Desktop installer supplies the token as
`MCP_AUTH_TOKEN`; it is not placed in command-line arguments. If you rotate the
token, reinstall or update the client configuration.

## Sharing and license

SUNDER is an independent project derived from PortSwigger's MCP server and
remains licensed under GPLv3. It is not an official PortSwigger product and is
not endorsed by PortSwigger. If you publish binaries or source, preserve the
license and copyright notices, publish the corresponding source, and clearly
identify your changes.

The fork is based on PortSwigger MCP Server v1.3.0, commit
[`5f76126`](https://github.com/PortSwigger/mcp-server/commit/5f76126409780ecba2b766c7f7388f465c5b5f94).
See [NOTICE.md](NOTICE.md) for attribution and a summary of the fork's changes.

Security issues should be reported according to [SECURITY.md](SECURITY.md), not
through a public issue containing credentials or private target traffic.
