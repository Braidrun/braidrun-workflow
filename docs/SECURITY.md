# Security

Use Docker mode for workflows that execute code, shell commands, browser automation, Git operations, or external agents on untrusted input.

Recommended defaults:

- Run production workflows with `subprocess_mode=docker`.
- Set explicit `working_dir` and `output_dir`.
- Avoid placing API keys in workflow YAML.
- Use MCP allowlists when exposing tool groups.
- Keep browser and shell tools out of presets that do not need them.
- Validate workflow YAML before storing or executing it.

Native mode is intended for local development and trusted automation.
