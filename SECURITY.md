# Security policy

## Supported version

Security fixes are applied to the latest SUNDER release.

## Reporting a vulnerability

Do not open a public issue containing credentials, authentication tokens,
private target information, captured HTTP traffic, or an unpatched
vulnerability in SUNDER.

Use GitHub's private vulnerability reporting feature for this repository:

1. Open the repository's **Security** tab.
2. Select **Advisories**.
3. Select **Report a vulnerability**.

Include the affected version, reproduction steps, impact, and any suggested
fix. Remove secrets and third-party target data before attaching logs.

## Operational safety

SUNDER can issue HTTP requests and start Burp Professional crawl or audit tasks.
Use it only on systems you own or are explicitly authorized to test. Keep MCP
authentication enabled, bind the server to loopback, configure Burp scope or an
explicit allowlist, and do not publish Claude configuration files or MCP tokens.
