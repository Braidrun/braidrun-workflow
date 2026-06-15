# Security Policy

## Reporting a vulnerability

Please do **not** open a public issue for security vulnerabilities.

Once this repository is published on GitHub, use **GitHub Security
Advisories** ("Report a vulnerability" on the Security tab) to report
privately. We will acknowledge reports as quickly as we can and keep you
informed of the fix progress.

## Scope notes

This library can execute workflow-defined shell commands and code steps,
access databases, browsers and the local filesystem **by design**. Operating
it safely requires configuring the guardrails described in
[docs/SECURITY_HARDENING.md](docs/SECURITY_HARDENING.md), including:

- code-step sandboxing (Docker-per-step execution)
- environment-variable allowlists for native subprocesses
- MCP tool allowlists, rate limits and input size limits
- read-only database mode

Reports about workflows doing dangerous things **when these guardrails are
deliberately disabled** are generally not considered vulnerabilities; reports
about escaping the guardrails are.
