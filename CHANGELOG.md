# Changelog

## 3.0.0

- Renamed the independent fork to SUNDER, an autonomous offensive MCP operator
  for Burp Suite.
- Added session-only live capture across all Burp HTTP tools and Burp-created
  WebSocket connections.
- Added combined cursor polling through `agent_get_events`.
- Added in-memory traffic search, complete exchange retrieval, endpoint
  aggregation, and response-variant detection.
- Added autonomous replay, mutation, batch sending, cookie, Organizer, scope,
  crawl, and audit tools.
- Added autonomous safety enforcement using Burp scope and explicit target
  allowlists, without interactive approval dialogs.
- Added a SUNDER Traffic tab for the in-memory session buffer.
- Added an optional maximum session-event count; zero means unlimited.
- Added bearer/API-key authentication enabled by default.
- Preserved the PortSwigger MCP tools, SSE transport, and bundled stdio proxy.
- Vendored the official PortSwigger stdio proxy source and fixed stale SSE
  transports so Claude tool calls reconnect and retry after Burp or the
  extension restarts.
- Restored secure `MCP_AUTH_TOKEN` and `MCP_API_KEY` forwarding in the
  vendored proxy and prevented an unavailable Burp endpoint from crashing the
  stdio process during capability registration.
- Added proxy restart, uninitialized-transport, and Windows E2E regression
  coverage; the main build now compiles and embeds the repaired proxy.
- The extension creates no traffic database and clears its capture buffer on
  unload.
