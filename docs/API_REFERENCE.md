# API Reference

The public API is intentionally small:

- `WorkflowParser.parseFile(path)` parses and validates workflow YAML.
- `WorkflowParser.parseYaml(content)` parses workflow YAML from a string.
- `WorkflowExecutor.execute(workflow, initialInput, externalExecutionId)` runs a parsed workflow.
- `AgentPresetRegistry.getAll()` lists built-in and registered presets.
- `AgentPresetRegistry.get(id)` resolves one preset.
- `startAgentMcpServer(httpAccess, parameters, requestedGroups)` starts the stdio MCP server.

Use [Library Usage](LIBRARY_USAGE.md) for complete examples.
