# MCP

Braidrun Workflow can consume MCP tools through agent configuration and can expose its own built-in tool groups over stdio.

Start the built-in MCP server:

```bash
braidrun-workflow mcp-server --tool-group file_system,shell,git
```

Use `all` or omit `--tool-group` to expose all supported groups.

List groups:

```bash
braidrun-workflow list-tools
```

Security settings:

```bash
export BRAIDRUN_MCP_ALLOWED_TOOLS=readFile,listDirectory
export BRAIDRUN_MCP_RATE_LIMIT_PER_MIN=120
export BRAIDRUN_MCP_MAX_INPUT_BYTES=1048576
```
