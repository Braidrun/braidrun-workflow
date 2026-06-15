# Braidrun Agent Workflow Guide

> **关于命令行示例**：文中 `braidrun-agent-cli` / `:braidrun-agent-cli:run` 形式的
> 命令来自本库早期配套的内置 CLI，当前开源版本只包含库本身、暂不附带 CLI 发行版。
> 这些示例中的参数与行为语义仍然准确，可作为理解功能的参考；以编程方式运行
> agent / workflow 请见 [GETTING_STARTED.md](GETTING_STARTED.md)。


本文档聚焦当前可执行的 workflow YAML 语法。权威定义仍以代码为准：

- `WorkflowDefinition`
- `WorkflowStep`
- `WorkflowParser`
- `WorkflowExecutor`

## 第一原则

1. YAML 字段名必须使用 `snake_case`
2. 每个步骤必须且只能使用一种主模式
3. 主流程优先用 `depends_on + condition`，`on_success` / `on_failure` 只做补充动作
4. 宿主应用（如 Web 平台）的 DTO 通常是 `camelCase`，不要把它直接抄成 YAML

常见映射：

| Web / DTO | YAML |
|---|---|
| `groupChat` | `group_chat` |
| `agentBased` | `agent_based` |
| `stateMachine` | `state_machine` |
| `repeatUntil` | `repeat_until` |
| `iterateOver` | `iterate_over` |
| `manualApproval` | `manual_approval` |
| `dependsOn` | `depends_on` |
| `outputVariable` | `output_variable` |

## 执行、校验、调试

```bash
# 执行
braidrun-agent -w workflow.yaml

# 批量并发执行多个 workflow
braidrun-agent -w workflow-a.yaml -w workflow-b.yaml -w workflow-c.yaml --workflow-max-concurrency 2

# 传变量
braidrun-agent -w workflow.yaml -v topic=AI -v language=zh

# 在 workflow 模式下叠加基础 preset/config
braidrun-agent -a coder -c agent.yaml -w workflow.yaml

# 只校验
braidrun-agent -V workflow.yaml

# Dry-run：校验并打印执行计划，不实际运行
braidrun-agent -w workflow.yaml --dry-run

# Debug：基本调试
braidrun-agent -w workflow.yaml --debug --debug-stop-on-entry

# Debug：步骤断点
braidrun-agent -w workflow.yaml --debug-breakpoint before_step:review

# Debug：条件断点（仅当条件成立时暂停）
braidrun-agent -w workflow.yaml --debug-breakpoint 'after_step:review?quality_score<5'

# Debug：步骤失败时自动暂停
braidrun-agent -w workflow.yaml --debug-break-on-error
```

说明：

- 多次传入 `-w/--workflow` 时，会进入”多工作流批量执行”模式
- `--workflow-max-concurrency` 控制这一批 workflow 同时最多运行多少个；`0` 表示不限制
- `-v/--var` 传入的是共享变量，会同时应用到整批 workflow
- `--debug` 只支持单个 workflow，不支持批量模式
- `--debug-stop-on-entry`、`--debug-breakpoint`、`--debug-break-on-error` 单独出现时，也会自动开启 debug 模式

需要特别区分两层并发：

- 顶层 `concurrency` 字段：单个 workflow 内部的 DAG 分层并发
- CLI 的 `--workflow-max-concurrency`：多个独立 workflow 执行任务之间的并发

### Debug 能力概览

调试模式同时支持 CLI 和 Web 两个入口，提供完整的工作流运行时调试能力：

| 能力 | CLI | Web |
|------|-----|-----|
| 步骤级断点（9 种点位） | `--debug-breakpoint` | 断点面板 |
| 条件断点 | `step?condition` 语法 | 条件输入框 |
| Break on Error | `--debug-break-on-error` | 异常时暂停开关 |
| 暂停 / 继续 / 单步 | `c` / `n` | 按钮 |
| 变量查看与过滤 | `v [filter]` | 快照面板 |
| 步骤输出查看 | `o [step]` | 快照面板 |
| 暂停时变量编辑 | `edit var=val` | 双击编辑 |
| 快照历史回溯 | `history [N]` | 时间轴滑块 |
| 动态断点增删 | `ba` / `br` | 面板编辑 |
| 变量 Diff | — | Diff 开关 |
| DAG 可视化 | — | 断点标记 + 暂停高亮 |
| Dry-run 预演 | `--dry-run` | 执行弹窗预演按钮 |
| 日志流 | 终端输出 | 日志面板 |

支持的 debug checkpoint 点位：

- `before_step` — 步骤执行前
- `after_step` — 步骤执行后
- `step_error` — 步骤失败时
- `state_machine_state` — 状态机进入新状态
- `group_chat_round` — 群聊新轮次
- `iteration_item` — 遍历新项
- `agent_based_delegation` — 委派给 worker agent
- `sub_workflow_entry` — 进入子工作流前
- `sub_workflow_exit` — 子工作流返回父工作流时

更完整的调试说明请看 `WORKFLOW_DEBUG_GUIDE.md`。

## 顶层结构

最小骨架：

```yaml
name: hello-workflow
version: "1.0.0"
description: 最小可运行示例

variables:
  topic: AI Agent

agents:
  writer:
    preset: writer
    overrides:
      tool_set: [exit]

workflow:
  - step: draft
    agent: writer
    input: "请写一段关于 {{var:topic}} 的简介。"
```

常用顶层字段：

- `name`
- `version`
- `description`
- `variables`
- `agents`
- `workflow`
- `error_handling`
- `timeout`
- `directory_isolation`
- `concurrency`
- `knowledge_base`
- `tags`
- `category`
- `recovery` —— **仅宿主服务使用**，库/CLI 执行时忽略；详见 §服务重启与自动恢复

`category` 用于 Web 端分组筛选（如 `automation`、`analysis`）。

`global_agent` 目前主要用于编辑器 / round-trip 元数据，不是 CLI 执行的核心依赖。

`recovery` 仅在宿主服务启用了「自动恢复中断执行」能力时生效，用来声明该 workflow 的中断执行是否可被自动恢复。库/CLI 模式不会读取此字段。完整 schema 见本文档末尾「服务重启与自动恢复」一节。

## 顶层 `concurrency` 的边界

`concurrency` 仍然只负责“一个 workflow 内部”的执行图并发，例如：

```yaml
concurrency:
  enabled: true
  max_concurrency: 4
```

它不会自动让多个 YAML 文件一起跑。  
如果你的目标是“一次命令同时跑多个 workflow 文件”，需要使用 CLI 的多个 `-w` 加 `--workflow-max-concurrency`。

## Agent 定义

### 推荐：`preset + overrides`

```yaml
agents:
  reviewer:
    preset: universal
    overrides:
      system_prompt: "You are a strict reviewer."
      tool_set:
        - exit
        - file_system
      llm_config:
        models:
          - model: your-model-id
            provider: open_router
        fallback:
          model: your-model-id
          provider: open_router
        temperature: 0.3
      llm_provider_keys:
        openrouter: "sk-or-v1-..."
```

### 兼容旧 YAML：legacy inline agent

```yaml
agents:
  legacy_agent:
    type: universal_agent
    strategy: just_work_parallel
    system_prompt: "兼容旧配置时使用"
    tools:
      - exit
      - file_system
```

旧格式仍兼容，但新 YAML 不应再继续扩散这种写法。

## 步骤主模式

### 1. 单 Agent

```yaml
- step: draft
  agent: writer
  input: "Write about {{var:topic}}."
```

### 2. `group_chat`

```yaml
- step: discussion
  group_chat:
    participants: [pm, developer, tester]
    moderator: pm
    max_rounds: 4
    speaker_selection: round_robin
    termination_keyword: "CONSENSUS_REACHED"
    initial_message: "Discuss {{var:topic}}."
    summary_agent: pm
```

### 3. `agent_based`

```yaml
- step: orchestration
  agent_based:
    orchestrator:
      preset: universal
      overrides:
        system_prompt: "You are a workflow orchestrator."
    participants: [researcher, writer]
    goal: "Plan and delegate the work for {{var:topic}}."
    max_steps: 12
    budget_tokens: 200000
    timeout_seconds: 300
```

### 4. `code`

```yaml
- step: prepare_data
  code:
    language: python
    script: |
      import os
      print("topic=" + os.environ.get("WF_VAR_TOPIC", ""))
    timeout: 30
```

支持语言：

- `python`
- `javascript`
- `typescript`
- `bash`
- `ruby`
- `lua`
- `cli`

`code` 步骤的输入输出约定：

- **输入**：通过环境变量注入 — `STEP_INPUTS`（上游步骤输出拼接）、`WF_VAR_xxx`（工作流变量，键名大写）、`STEP_OUTPUT_xxx`（指定步骤输出，键名大写）
- **输出**：脚本的 `stdout` 作为步骤输出
- `script` 和 `script_file` 二选一，不能同时使用

### 5. `classifier`

```yaml
- step: classify
  classifier:
    agent: router
    input: "Classify: {{var:user_message}}"
    categories:
      - name: technical
        description: "Technical issue"
      - name: billing
        description: "Billing issue"
    output_variable: ticket_category
    default_category: technical
```

### 6. `state_machine`

```yaml
- step: review_flow
  state_machine:
    initial_state: classify
    final_states: [done]
    states:
      classify:
        name: classify
        step:
          classifier:
            agent: router
            input: "{{var:user_message}}"
            output_variable: route
            categories:
              - name: revise
                description: "Needs revision"
              - name: done
                description: "Can finish directly"
        transitions:
          - event: complete
            target: revise
            condition: "route == revise"
          - event: complete
            target: done
      revise:
        name: revise
        step:
          agent: writer
          input: "Revise based on {{steps.review_flow.classify.output}}"
        transitions:
          - event: complete
            target: done
      done:
        name: done
        transitions: []
```

## 通用增强能力

这些字段通常可与主模式组合：

- `depends_on`
- `condition`
- `priority`
- `timeout_seconds`
- `retry`
- `manual_approval`
- `on_success`
- `on_failure`
- `extract`
- `aggregate`
- `repeat_until`
- `iterate_over`
- `parallel`
- `idempotent` —— **仅宿主服务使用**，库/CLI 执行时忽略；声明该步骤是否可被重复执行而不产生外部副作用，作为服务重启自动恢复的安全标注。详见末尾章节。

### 当前组合约束

- `repeat_until` 当前主要用于单 Agent 或 `group_chat`
- `code` / `agent_based` 步骤不能配置 `repeat_until`
- `iterate_over` 当前只支持单 Agent 步骤或 `code` 步骤
- `iterate_over` 不能和步骤级 `parallel`、`retry`、`repeat_until`、`manual_approval`、`timeout_seconds` 组合
- `parallel` 当前只适用于单 Agent 步骤
- `state_machine` 外层不要再配 `parallel`

### Agent Session ID 策略

在 agent 定义的 overrides 中可配置 `session_id_strategy`：

```yaml
agents:
  writer:
    preset: writer
    overrides:
      session_id_strategy: per_execution
```

可选值：

| 策略 | 生成规则 | 适用场景 |
|---|---|---|
| `auto`（默认） | `{executionId}:{agentName}:{stepName}` | 每步完全隔离 |
| `per_execution` | `{executionId}:{agentName}` | 同一次执行内共享 agent 上下文 |
| `per_agent` | `{workflowName}:{agentName}` | 跨执行共享，适合长期记忆积累 |
| `fixed` | 使用 `sessionId` 字段的固定值 | 固定会话标识 |

### History Compression

在 agent 定义中可配置 `history_compression` 用于压缩消息历史，减少 token 消耗：

```yaml
agents:
  writer:
    preset: writer
    overrides:
      history_compression:
        enabled: true
```

## 条件表达式

当前条件求值器只支持简单三段式：

```text
变量名 操作符 字面量
```

支持的操作符：

- `==`
- `!=`
- `>`
- `<`
- `>=`
- `<=`
- `contains`

可用示例：

```yaml
condition: route == coding
condition: score >= 8
condition: summary contains urgent
condition: route == "content"
```

不要写：

```yaml
condition: "{{var:route}} == coding"
condition: a && b
condition: a || b
condition: (a == b)
```

## 模板变量

当前模板替换支持：

- `{{var:name}}`
- `{{steps.step_name.output}}`
- `{{name}}`
- `{{step_name}}`

建议优先写显式形式：

- 工作流变量：`{{var:topic}}`
- 步骤输出：`{{steps.classify.output}}`

## 目录隔离与知识库

常见配置：

```yaml
directory_isolation:
  enabled: true
  base_dir: ".workflow-runs"
  working_dir_pattern: "{base_dir}/{execution_id}/{step_name}/workspace"
  output_dir_pattern: "{base_dir}/{execution_id}/output"
  persistence_dir_pattern: "{base_dir}/{execution_id}/.snapshots"

knowledge_base:
  enabled: true
  storage_dir: "./workflow-kb"
  embedding_model: "text-embedding-3-small"
  embedding_provider: "openai"
  auto_index_outputs: true
```

`knowledge_base.source_files` 会在执行前预加载；`auto_index_outputs` 会把步骤输出自动写入共享知识库。

### `iterate_over` 详细配置

```yaml
- step: process_items
  agent: processor
  input: "处理 {{current_item}}"
  iterate_over:
    source: "{{steps.collect.output}}"
    delimiter: "\n"           # 默认 "\n"，也支持 "json_array"
    item_variable: current_item
    index_variable: current_index
    max_items: 0              # 0 = 不限制
    parallel: false           # 是否并行处理
    max_parallel: 4           # 并行时的最大并发数
    results_variable: all_results  # 聚合结果变量名
```

### `repeat_until` 详细配置

```yaml
- step: refine
  agent: writer
  input: "改进内容：{{steps.draft.output}}"
  repeat_until:
    condition: "quality_score >= 8"
    max_iterations: 5
    evaluate_agent: evaluator
    evaluate_prompt: "请评估质量并返回分数（0-10）"
    extract_pattern: "score[：:](\\d+)"
    extract_variable: quality_score
```

### `aggregate` 详细配置

```yaml
- step: combine
  agent: summarizer
  input: "合并以下内容"
  aggregate:
    sources:
      - "{{steps.research.output}}"
      - "{{steps.analysis.output}}"
    strategy: concat          # concat, json_array, numbered_list, pick_longest, pick_shortest
    separator: "\n\n---\n\n"
    output_variable: combined_result
```

### `directory_isolation` 完整配置

```yaml
directory_isolation:
  enabled: true
  base_dir: ".workflow-runs"
  working_dir_pattern: "{base_dir}/{execution_id}/{step_name}/workspace"
  output_dir_pattern: "{base_dir}/{execution_id}/output"
  persistence_dir_pattern: "{base_dir}/{execution_id}/.snapshots"
  shared_cache_dir: ".workflow-runs/.cache"
  shared_history_dir: ".workflow-runs/.history"
  shared_skills_dir: ".workflow-runs/.skills"
  cleanup_on_completion: false
  cleanup_after_hours: 72
```

模式占位符支持：`{base_dir}`、`{execution_id}`、`{step_name}`、`{agent_name}`、`{workflow_name}`

## 变量类型与文件附件

### variable_types

`variable_types` 是一个可选字段，用于声明工作流变量的输入类型。未声明类型的变量默认为 `string`。

```yaml
variables:
  invoice_text: "示例发票文本..."
  output_format: markdown
variable_types:
  invoice_text: file_or_text
  output_format: string
```

| 类型 | 说明 |
|------|------|
| `string` | 纯文本输入（默认） |
| `file` | 仅支持文件上传，变量值为文件绝对路径 |
| `file_or_text` | 支持文本输入或文件上传，用户执行时可选 |

### 文件变量的运行时行为

当变量被设为文件类型并上传了文件时：

- `{{var:变量名}}` — 解析为文件的**绝对路径**
- `{{var:变量名_content}}` — 自动注入文件**文本内容**（仅当文件为文本类型且小于 100KB）

文本类型文件扩展名：`.txt`, `.md`, `.csv`, `.json`, `.xml`, `.yaml`, `.yml`, `.html`, `.log`, `.py`, `.kt`, `.java`, `.js`, `.ts`, `.sql`

### 示例：发票识别工作流

```yaml
variables:
  invoice_text: "示例发票内容..."
variable_types:
  invoice_text: file_or_text
agents:
  parser:
    preset: universal
    overrides:
      system_prompt: "你是发票解析专家。"
workflow:
- step: parse
  agent: parser
  input: |
    请解析以下发票内容：
    {{var:invoice_text_content}}

    文件路径：{{var:invoice_text}}
```

### CLI 中使用文件变量

通过 CLI 传入文件路径作为变量值：

```bash
./gradlew :braidrun-agent-cli:run --args='-w invoice-workflow.yaml -v invoice_text=/path/to/invoice.pdf'
```

## 与宿主服务的关系

宿主服务（例如基于本库构建的 Web 平台）通常负责：

- 可视化编辑
- YAML round-trip
- 执行与监控
- 调试、调度、协作、skills 管理

但 workflow YAML 最终仍由本库解析与执行。生成 YAML 时，始终以本文件和 `WorkflowModels.kt` 为准。

## 服务重启与自动恢复（仅宿主服务）

CLI 模式（`braidrun-agent run`）不感知此机制 —— 进程退出 = 执行结束。

宿主服务一般会把服务重启时还在运行的执行标记为 `INTERRUPTED` 终态，需要用户手动从中断步骤重跑。宿主可以选择启用「自动从中断步骤恢复」能力，但**每个工作流必须显式 opt-in**，且**每个步骤必须显式标注 `idempotent`**。

### 顶层 `recovery` schema

```yaml
recovery:
  autoResumeOnRestart: true                  # 必填，默认 false
  policy: RESUME_FROM_LAST_INCOMPLETE        # 默认；可选 ABORT / RESUME_FROM_START
  maxAutoResumeAttempts: 3                   # 默认 3，范围 1..20
```

| `policy` | 含义 |
|---|---|
| `ABORT`（默认） | 不自动恢复，手动重跑 |
| `RESUME_FROM_LAST_INCOMPLETE` | 从首个非 COMPLETED 步骤继续。要求该步骤标 `idempotent: true` |
| `RESUME_FROM_START` | 整个工作流从头重跑 |

### 步骤级 `idempotent`

```yaml
workflow:
  - step: fetch_data
    agent: fetcher
    input: "..."
    idempotent: true        # 安全可重跑（只读 API 查询）

  - step: transform
    code: {...}
    idempotent: true        # 纯计算

  - step: send_telegram
    agent: telegram_notifier
    # 默认 idempotent: false —— 有外部副作用，不允许自动重跑
```

| 步骤性质 | 推荐 `idempotent` |
|---|---|
| 只读 API 查询、纯计算 | `true` |
| 写本地 artifact（重跑前会清理） | `true` |
| LLM 推理（无外部 tool）| `true`（注意会重新计费 tokens） |
| 发邮件 / Telegram / Slack / Webhook 出站 | `false` |
| 修改外部 DB / 改广告出价 / 创建订单 | `false` |
| `manual_approval` / `user_interaction` | `false` |

### 不会自动恢复的场景

即使 opt-in，以下情况下执行会保持 `INTERRUPTED`：

- 触发源是 webhook 或 scheduler（at-most-once / 自己会 re-fire）
- 处于 `manual_approval` 待审批状态
- 工作流被删除或定义已变化（hash mismatch）
- 已达到 `maxAutoResumeAttempts` 上限

每次跳过都会写一条 `execution.auto_resume.skipped` 审计事件。


## 工作流级 HTTP 代理（仅宿主服务）

CLI 模式不感知此字段——CLI 执行直接用当前进程的网络栈。

宿主服务可以给每个工作流单独配置代理，覆盖全局代理设置。作用域覆盖 **本工作流所有对外流量**：LLM 调用、Agent 工具的 HTTP/MCP/RAG/多媒体、以及 `code:` 步骤子进程（通过 `HTTP_PROXY`/`HTTPS_PROXY`/`ALL_PROXY`/`NO_PROXY` 环境变量透明注入，脚本无需额外配置）。

### 顶层 `proxy` schema

```yaml
proxy:
  mode: custom              # inherit (默认) | disabled | custom
  protocol: socks5          # http | https | socks5
  host: proxy.example.com
  port: 1080
  username: ""              # 可选
  password: ""              # 可选
  no_proxy: "localhost,.internal.corp"   # 可选；localhost/127.0.0.1/::1 始终自动包含
```

| `mode` | 行为 |
|--------|------|
| `inherit`（或省略整个 `proxy` 块） | 沿用管理员全局代理配置；未开则直连 |
| `disabled` | 本工作流显式直连，全局代理即使开启也不走 |
| `custom` | 用本工作流自己的 protocol/host/port/可选鉴权；忽略全局 |

### code 步骤透明代理覆盖面

| 工具 / 语言 | HTTP/HTTPS | SOCKS5 |
|-------------|-----------|--------|
| curl / wget / apt / git | ✓ | ✓ |
| pip / npm | ✓ | ✓ (`ALL_PROXY`) |
| Python `requests` | ✓ | ✓（需 `pip install requests[socks]`） |
| Node.js `fetch`/`undici` | ✓ | ✓ |
| Go `net/http` | ✓ | ✗（需显式 `proxy.SOCKS5` dial） |
| Java `HttpClient`/`HttpURLConnection` | ✓ | ✗（需显式 `ProxySelector`） |

HTTP/HTTPS 的透明度几乎 100%。SOCKS5 在 Go 和 Java 标准库里不自动识别 `ALL_PROXY`，脚本里如果必须走 SOCKS5 需要显式拨号——这是语言标准库的限制。


## 推荐参考

- `WORKFLOW_DEBUG_GUIDE.md`
- `src/main/resources/builtin-skills/braidrun-agent-guide/SKILL.md`
- `src/main/resources/builtin-skills/braidrun-agent-guide/workflow-template.yaml`
- `workflows/templates/`（能力测试模板）
