# MCP Proxy

A Stdio MCP server which proxies an SSE MCP server.

## Overview

This proxy acts as a bridge between:
- An SSE-based MCP server endpoint
- Standard input/output for MCP client communication

It enables tools that use standard I/O for communication such as Claude Desktop (as of 2025-04-01) to interact with MCP implementations through an SSE interface.

SUNDER vendors the official PortSwigger proxy source under GPLv3 and adds
transparent reconnect-and-retry behavior for stale or uninitialized SSE
transports. The root `embedProxyJar` task builds and embeds this proxy
automatically.

## Requirements

- JDK 21 or higher
- Gradle 8.0+ (Wrapper included)


## Build Jar
```bash
./gradlew shadowJar
```

## Testing

```bash
# Run all tests
./gradlew test

# Run a specific test
./gradlew test --tests "net.portswigger.TestName"
```
