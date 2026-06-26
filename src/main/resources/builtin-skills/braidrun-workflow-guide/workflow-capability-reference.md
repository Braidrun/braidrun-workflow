# Workflow Capability Reference

Supported top-level fields include:

- `name`
- `version`
- `description`
- `agents`
- `workflow`
- `variables`
- `timeout`
- `error_handling`
- `directory_isolation`
- `concurrency`
- `knowledge_base`
- `global_agent`
- `tags`
- `code_preamble`
- `category`
- `module`

Supported step modes:

- `agent` + `input`
- `code`
- `group_chat`
- `agent_based`
- `classifier`
- `state_machine`
- `sub_workflow`

Supported code languages:

- `python`
- `javascript`
- `typescript`
- `bash`
- `ruby`
- `lua`
- `cli`

Use `braidrun-workflow validate workflow.yaml` as the authority for whether a workflow is accepted by the current parser.
