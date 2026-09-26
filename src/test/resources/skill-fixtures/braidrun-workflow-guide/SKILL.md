---
version: "3.2.0"
author: braidrun
tags:
  - guide
  - workflow
  - yaml
  - cli
  - mcp
  - docker
  - jev
attachments:
  - config-template.yaml
  - workflow-template.yaml
  - workflow-capability-reference.md
translations:
  zh:
    name: "Braidrun 工作流指南"
    description: >-
      braidrun-workflow 运行时、workflow YAML 编写、workflow 工具、校验流程和最新运行时约束的当前指南。当用户询问如何生成 workflow YAML，或确认当前解析器/执行器真正支持的能力时使用。
  en:
    name: "Braidrun Workflow Guide"
    description: >-
      Current guide for the braidrun-workflow runtime, workflow YAML authoring, workflow tools, validation, and the latest runtime constraints. Use when the user asks how to generate workflow YAML or check what the current parser/executor truly supports.
  zhHant:
    name: "Braidrun 工作流程指南"
    description: >-
      braidrun-workflow 執行階段、workflow YAML 編寫、workflow 工具、驗證流程和最新執行階段限制的現行指南。當使用者詢問如何產生 workflow YAML，或確認目前剖析器／執行器真正支援的功能時使用。
  ja:
    name: "Braidrun ワークフローガイド"
    description: >-
      braidrun-workflow ランタイム、workflow YAML の作成、workflow ツール、検証、最新のランタイム制約に関する現在のガイドです。ユーザーが workflow YAML の生成、または現在のパーサー/実行器が実際に何をサポートしているかを確認したい場合に使用します。
  ko:
    name: "Braidrun 워크플로 가이드"
    description: >-
      braidrun-workflow 런타임, workflow YAML 작성, workflow 도구, 검증 및 최신 런타임 제약 사항에 대한 최신 가이드입니다. 사용자가 workflow YAML 생성 방법이나 현재 파서/실행기가 실제로 지원하는 기능을 확인하려 할 때 사용합니다.
  es:
    name: "Guía de workflows de Braidrun"
    description: >-
      Guía actual del entorno de ejecución de braidrun-workflow, la creación de YAML de workflow, las herramientas de workflow, la validación y las restricciones más recientes del entorno. Úsala cuando el usuario pregunte cómo generar YAML de workflow o quiera comprobar qué admite realmente el analizador o ejecutor actual.
  fr:
    name: "Guide des workflows Braidrun"
    description: >-
      Guide actuel de l'environnement d'exécution braidrun-workflow, de la rédaction de fichiers YAML de workflow, des outils de workflow, de la validation et des dernières contraintes d'exécution. À utiliser lorsque l'utilisateur demande comment générer un YAML de workflow ou vérifier ce que l'analyseur ou l'exécuteur actuel prend réellement en charge.
  de:
    name: "Braidrun-Workflow-Leitfaden"
    description: >-
      Aktueller Leitfaden für die braidrun-workflow-Laufzeit, das Verfassen von Workflow-YAML, Workflow-Werkzeuge, Validierung und die neuesten Laufzeitbeschränkungen. Verwenden, wenn der Benutzer fragt, wie Workflow-YAML erzeugt wird, oder prüfen möchte, was der aktuelle Parser bzw. Executor tatsächlich unterstützt.
  vi:
    name: "Hướng dẫn quy trình Braidrun"
    description: >-
      Hướng dẫn hiện hành về môi trường chạy braidrun-workflow, cách biên soạn workflow YAML, các công cụ workflow, quy trình xác thực và những giới hạn mới nhất của môi trường chạy. Sử dụng khi người dùng hỏi cách tạo workflow YAML hoặc muốn kiểm tra trình phân tích hay trình thực thi hiện tại thực sự hỗ trợ những gì.
  ar:
    name: "دليل سير عمل Braidrun"
    description: >-
      الدليل الحالي لبيئة تشغيل braidrun-workflow وتأليف workflow YAML وأدوات workflow والتحقق وأحدث قيود بيئة التشغيل. يُستخدم عندما يسأل المستخدم عن كيفية إنشاء workflow YAML أو التحقق مما يدعمه المحلل أو المنفذ الحالي فعليًا.
  pt:
    name: "Guia de workflows do Braidrun"
    description: >-
      Guia atual do ambiente de execução braidrun-workflow, da criação de YAML de workflow, das ferramentas de workflow, da validação e das restrições mais recentes do ambiente. Utilize quando o utilizador perguntar como gerar YAML de workflow ou quiser verificar o que o analisador ou executor atual realmente suporta.
name: braidrun-workflow-guide
description: Current English guide for Braidrun Workflow YAML, CLI usage, library usage, tool groups, Docker runtime, external Claude Code / Codex agents, and TypeSafe Jev decision steps.
---

# Braidrun Workflow Guide

Use this skill when a user or agent needs current guidance for authoring or running Braidrun Workflow YAML.

## Current Runtime

- CLI binary: `braidrun-workflow`
- Main commands: `validate`, `dry-run`, `run`, `agent`, `list-presets`, `list-tools`, `mcp-server`
- Library entry points: `WorkflowParser`, `WorkflowExecutor`, `AgentPresetRegistry`
- Recommended agent definition style: `preset + overrides`
- The `asa` preset is a normal `universal_agent` preset for Apple Search Ads research and analysis.

## Authoring Rules

1. Prefer built-in presets over hand-written full agent definitions.
2. Use code steps for deterministic shell, Python, JavaScript, TypeScript, Ruby, Lua, or CLI work.
3. Use Docker subprocess mode for production or untrusted code and shell execution.
4. Keep API keys outside workflow YAML when possible.
5. Validate YAML with `braidrun-workflow validate` before running it.
6. Use `dry-run` to review the step and agent plan without calling models or tools.
7. When a route or quality gate needs a calibrated decision and a TypeSafe credential is available, prefer a Jev step (`classifier.jev`, `repeat_until.jev`) over an LLM judge whose answer is parsed with regex.

## Runtime Contract (violations WILL fail at execution time)

These are hard constraints of the hosted runtime, not style preferences:

1. **Shared output directory paths differ by step type.**
   - External agent steps (`claude_code_agent` / `codex_agent`) see the shared execution output directory mounted at container path `/output`. When a step prompt tells the agent to write or read a shared file, always use `/output/<filename>`.
   - `code` steps do NOT have `/output` mounted. They must resolve the shared directory via `os.environ["BRAIDRUN_OUTPUT_DIR"]` (a host-absolute path pointing at the same directory). The env names `OUTPUT_DIR` / `WORKFLOW_OUTPUT_DIR` do not exist.
   - NEVER put a hand-written absolute path into `variables.output_dir`. Agent steps and code steps resolve it differently, so downstream steps will never find upstream files. Platform validate reports this as the `absolute_output_dir` error.
2. **Agent containers have no `python3` and no `curl`.** Do not instruct an agent to "run python to count characters" or "use curl to call an API". Put deterministic checks (character limits, schema gates, format validation) into `code` steps; agents do networking through their built-in WebFetch / WebSearch tools.
3. **Code-step stdout does not become variables automatically.** After `print("key=value")`, the step must declare `extract: [{pattern: "key=(.+)", variable: "key"}]` before downstream steps can use `{{var:key}}`.
4. **Secrets never go into `variables` defaults.** Webhook URLs, passwords, and API tokens must not be stored as plaintext variable defaults — leave them empty and tell the user to fill them, or route them through the credential manager.
5. **Set `timeout_seconds` on every external-agent step yourself, sized to the workload.** Steps without it inherit the global per-step default (commonly 300s), which reliably kills web-research steps. Guidance: research / multi-tool agent steps 1800-2400, writing & review 900-1200, pure rewriting 600, code steps 60-600.
6. **Model and cost discipline.** Only open-ended web research justifies `opus`; writing, review, and summarization agents should default to `sonnet`. When several agents run in a chain, cap each intermediate artifact's length (e.g. ≤8000 characters, structured bullet points only) — every later turn re-reads those files, so oversized artifacts multiply cost.
7. **Credentials must exist before referencing a provider.** `external_agent_claude_auth_mode: subscription` requires a Claude subscription credential; any `llm.provider` requires a matching key. Jev steps (`classifier.jev`, `repeat_until.jev`) require a `typesafe` credential ("TypeSafe (Jev)" in the credential center); the hosted platform never reads `TYPESAFE_API_KEY` from the server environment and has no platform Jev key. Platform validate surfaces `missing_provider_credential` warnings — relay them to the user instead of ignoring them.

## Useful Commands

```bash
braidrun-workflow validate workflow.yaml
braidrun-workflow dry-run workflow.yaml
braidrun-workflow run workflow.yaml --subprocess-mode docker
braidrun-workflow agent --preset coder --prompt "Inspect this repository."
braidrun-workflow list-presets
braidrun-workflow list-tools
braidrun-workflow mcp-server --tool-group file_system,shell,git
```

## External Agents

Use the `external_agent` tool group when the parent agent should spawn Claude Code or OpenAI Codex.

Common runtime parameters:

- `external_agent_claude_command`
- `external_agent_claude_model`
- `external_agent_codex_command`
- `external_agent_codex_model`
- `external_agent_claude_extra_args`
- `external_agent_codex_extra_args`

Prefer API-key authentication for stable automation.

## Typed Decisions with TypeSafe Jev

Jev is TypeSafe AI's decision model. It answers typed `choice` / `score` / `noul`
questions with calibrated probabilities in one request and cannot write text, so it
is never an agent `llm.provider` and never a chat model.

- `classifier.jev`: add `jev: {}` (optional `model`, `min_confidence`, `questions`,
  `composites`) and omit `agent` (they are mutually exclusive). `input` is sent as the
  Jev state; the optional `instructions` describes the decision (both engines accept it).
  Besides `output_variable` it writes `<out>_confidence`, `<out>_probabilities` and, per
  extra question, `<id>` plus `<id>_confidence` / `<id>_probabilities` (choice),
  `<id>_level` / `<id>_normalized` / `<id>_confidence` / `<id>_probabilities` (score) or
  `<id>_yes` (noul); each composite writes `<name>`.
- `min_confidence` below the threshold routes to `default_category`; without a default the
  step fails. Transient API errors also fall back to `default_category` when it is set;
  a missing or rejected key always fails the step.
- `repeat_until.jev`: typed questions grade each iteration instead of `evaluate_agent`
  (mutually exclusive with `evaluate_agent`, `evaluate_prompt`, `extract_pattern`,
  `extract_variable`). `state` defaults to `{{steps.<this step>.output}}`; the critique is
  written to `{{steps.<this step>:evaluate.output}}` so the next iteration can read it.
- Branch with plain conditions on those variables, e.g. `team_confidence >= 0.8`,
  `is_urgent_yes == true`, `quality >= 0.75`. Conditions never call a model.
- Limits: at most 255 categories or options, 2..10 score levels, question ids and composite
  names match `^[A-Za-z_][A-Za-z0-9_]*$`, and derived variable names must not collide.
  English is most accurate; CJK input works with lower accuracy, so pair it with
  `min_confidence` + `default_category`.
- In the Braidrun web editor and assistant tools the same fields are camelCase DTO keys:
  `classifier.jev.minConfidence`, `questions[].defaultOption` / `minConfidence` /
  `yesDescription` / `noDescription`, `repeatUntil.jev`.

```yaml
- step: triage
  classifier:
    input: "{{var:ticket_text}}"
    instructions: "Which team should handle this support ticket?"
    categories:
      - {name: billing, description: "Payments, invoices, refunds"}
      - {name: technical, description: "Bugs, outages, integrations"}
      - {name: general, description: "Anything else"}
    output_variable: team
    default_category: general
    jev:
      min_confidence: 0.6
      questions:
        - {id: is_urgent, type: noul, instructions: "Is the problem time-sensitive?", threshold: 0.7}
```

See `workflow-capability-reference.md` for the full field tables.
