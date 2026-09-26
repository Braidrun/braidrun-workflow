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

## Image results from MCP tools

When an MCP tool that an agent calls returns `ImageContent`, the image reaches
the model as an image part instead of base64 JSON text, provided the model can
view tool-result images (Anthropic vision models, Gemini 3+ vision models, and
OpenAI models outside Azure that use only the Responses API, e.g. the `-pro` and
`-codex` entries; GPT models that also declare `openai.completions` use Chat
Completions and get placeholders). Other models, and images beyond the limits,
get a short placeholder such as
`[image 1 omitted (image/png, 245.3 KB): <reason>]`. Only the first 4 images of
one MCP result are attached; the rest become placeholders. Audio and blob
base64 is always replaced by a placeholder, in the tool output and in the
`tool_call_completed` event payload.

Images count against the agent's `tool_result_images_max_per_run` budget and
the `tool_result_images_max_per_request` window; `tool_result_images_enabled:
false` turns them off. See
[Tool Result Images](WORKFLOW_GUIDE.md#tool-result-images) for defaults and
size limits.

## MCP servers inside a hosted server

An agent's `mcp_servers` entry without a `url` is a stdio server: the engine starts its `command` as a local process. A host that runs other users' workflows in its own JVM declares `WorkflowHostPolicy.refuseStdioMcpServers()`, and such an agent then fails to build. Under `WorkflowHostPolicy.requirePublicServiceEndpoints()`, a `url` must be `https` (`wss` for `type: websocket`) on a public host. See [SECURITY.md](SECURITY.md#parameters-that-name-host-resources).
