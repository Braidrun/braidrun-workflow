---
name: braidrun-agent-guide
description: Current guide for braidrun-agent CLI, workflow YAML authoring, workflow tools, validation, and the latest runtime constraints. Use when the user asks how to launch braidrun-agent, generate workflow YAML, or check what the current parser/executor truly supports.
version: "2.8.0"
author: braidrun
tags:
  - guide
  - configuration
  - workflow
  - yaml
  - cli
  - mcp
  - workflow-tools
  - braidrun-agent
attachments:
  - config-template.yaml
  - workflow-template.yaml
  - workflow-capability-reference.md
---

# Braidrun Agent Guide

## 何时使用这个 skill

当用户、上层 agent 或工作流生成器需要确认以下内容时使用：

- 如何启动 `braidrun-agent`
- `braidrun-agent` 当前 CLI 参数、preset ID、debug 入口是什么
- 如何编写、校验、执行 workflow YAML
- 当前 workflow YAML 真正支持哪些顶层字段、步骤模式和增强能力
- 当前 parser / executor 的组合约束是什么
- 如何通过 `workflow` tool set 查看模板、校验 workflow、执行 workflow
- 如何通过 CLI 批量并发执行多个独立 workflow
- 宿主服务 DTO 与 agent 侧 YAML 的关系
- 如何以 MCP server 模式启动 `braidrun-agent`
- 如何通过 CLI 控制 skill 白名单 / 黑名单

附件：

- `config-template.yaml`：当前可直接复制的 Agent 配置模板
- `workflow-template.yaml`：当前可直接改写的 workflow 模板
- `workflow-capability-reference.md`：当前 workflow 能力与参考模板速查表

## 回答原则

1. workflow YAML 的权威语法以 `braidrun-agent` 的 `WorkflowDefinition` / `WorkflowParser` / `WorkflowExecutor` 为准。
2. 如果问题涉及 workflow 生成、复杂步骤设计或 schema 不确定性，先给当前确认为真的能力和约束，不要把历史字段或猜测写成正式能力。
3. 对新内容，Agent 定义优先使用 `preset + overrides`，不要默认回到旧 `type: universal_agent` 风格。
4. 对复杂 workflow，不要只靠口头规则；应优先参考 `workflows/templates/` 中的 `test-workflow-*.yaml` 与其中的代表性生产模板。
5. 需要权威校验时，优先使用 `braidrun-agent -V workflow.yaml` 或 `workflow.validateWorkflow(...)`，不要只做 YAML 字符串检查。
6. 只要是在“生成 workflow”或“改写 workflow”，就把 session 策略视为必填项，而不是运行时默认值：
   - 所有顶层 `agents.*`
   - 所有 `agent_based.orchestrator`
   - 所有 `global_agent.agent`
   都应显式写 `session_id_strategy`。

## Session 策略 authoring 基线

生成或审查 workflow 时，默认遵守下面这套基线：

- `auto`：`{executionId}:{agentName}:{stepName}`，适合并行分支、`iterate_over`、step-level `parallel`、fan-out/fan-in 这类“每个步骤或每个分支应彼此隔离”的场景
- `per_execution`：`{executionId}:{agentName}`，适合同一 agent 在一次执行内跨多个阶段连续复用，需要保留上下文的场景
- `per_agent`：`{workflowName}:{agentName}`，跨 execution 共享长期记忆；只有用户明确要长期助手、跨运行累积记忆或稳定会话身份时才使用
- `fixed`：使用手工指定 `session_id`；只有用户明确要求固定 session id 或外部系统约束必须绑定固定会话时才使用

强制规则：

- 不要把“省略 `session_id_strategy`，交给默认 `auto`”当作合格输出
- `global_agent.agent` 默认应写 `per_execution`
- `agent_based.orchestrator` 默认应写 `per_execution`
- 顺序复用、评审-修订-复检这类链路里重复出现的角色，优先写 `per_execution`
- `iterate_over`、step-level `parallel`、明显的并行分支或扇出处理里使用的 agent，优先写 `auto`
- 除非用户明确提出长期记忆或固定会话要求，否则不要默认生成 `per_agent` 或 `fixed`

---

## 当前 CLI

当前版本：`v1.1.0`（见 `AGENT_VERSION`）

### 版本与帮助

```bash
braidrun-agent --version          # 打印版本号 (v1.1.0) 并退出
braidrun-agent --help
braidrun-agent --list-presets     # 列出所有可用 preset
braidrun-agent --list-strategies  # 列出所有可用执行策略
braidrun-agent --list-tools       # 列出所有可用工具类别
```

当前内置 preset ID（与 `AgentPresetRegistry.builtinPresetFiles` 对齐）：

通用类：

- `universal`
- `universal_reasoning`
- `chat`
- `lightweight`

技术类：

- `coder`
- `researcher`
- `writer`
- `data_analyst`
- `devops`
- `web_scraper`
- `computer_operator`

营销/广告类：

- `asa`

文档处理类：

- `pdf_processor`
- `office_document`
- `word_document`
- `excel_workbook`
- `powerpoint_presentation`

多媒体与协作：

- `multimedia_creator`
- `communication`

> 注意：`word_document`、`excel_workbook`、`powerpoint_presentation` 是当前已经内置的独立预设，比 `office_document` 更专注。生成文档相关 workflow 时优先使用对应的细分预设，而不是混用 `office_document`。

### 直接执行 prompt

```bash
braidrun-agent -a universal -p "分析当前目录结构"
```

### 通过配置文件运行

```bash
braidrun-agent -c agent.yaml
braidrun-agent -a universal -c agent.yaml
braidrun-agent -a coder -c agent.yaml
```

含义：

- 只传 `-c`：把配置文件当作完整 Agent 配置
- 显式传 `-a` + `-c`：先加载 preset，再用配置文件覆盖
- 如果用户只想按配置文件原样运行，提示他省略 `-a`

### 执行 workflow

```bash
braidrun-agent -w workflow.yaml
braidrun-agent -w workflow.yaml -v topic=AI -v language=zh
braidrun-agent -a universal -c agent.yaml -w workflow.yaml -v topic=AI
```

### 批量并发执行多个 workflow

```bash
braidrun-agent \
  -w workflow-a.yaml \
  -w workflow-b.yaml \
  -w workflow-c.yaml \
  --workflow-max-concurrency 2 \
  -v topic=AI
```

规则：

- 多次传入 `-w/--workflow` 时进入批量模式
- `--workflow-max-concurrency` 只控制多个独立 workflow 执行任务之间的并发，`0` 表示不限制
- `-v/--var` 传入的变量会作为共享输入应用到这一批 workflow
- `--debug` / `--debug-stop-on-entry` / `--debug-breakpoint` / `--debug-break-on-error` 都只支持单个 workflow，不支持批量模式

### Skill 白名单与黑名单覆盖

CLI 直接覆盖配置文件中的 `skills_config`，无需修改 YAML：

```bash
braidrun-agent -a coder \
  --skill-whitelist-mode \
  --enabled-skill braidrun-agent-guide \
  --enabled-skill workflow-builder

braidrun-agent -a writer \
  --disabled-skill heavy-skill,another-skill
```

规则：

- `--enabled-skill` 与 `--disabled-skill` 都可以重复传入或使用逗号分隔
- 只要传入了 `--enabled-skill`，就会自动启用 `skill_whitelist_mode = true`
- `--skill-whitelist-mode` 显式控制是否开启白名单模式
- 这些参数会与配置文件中已有的 `skills_config.enabledSkills` / `disabledSkills` 求并集合并，而非整体替换

### 输出到文件与静默模式

```bash
braidrun-agent -a universal -p "生成报告" -o report.txt   # 输出写入文件
braidrun-agent -a coder -p "fix bug" -q                   # 静默模式，只显示最终输出
braidrun-agent -a universal -p "task" -o result.txt -q    # 组合使用
braidrun-agent -w workflow.yaml -o run_summary.json       # workflow 模式下写出 JSON 摘要
```

### 执行超时

```bash
braidrun-agent -a universal -p "complex task" --timeout 120   # agent 模式 120 秒超时
```

> 注意：`--timeout` 仅对 agent 模式有效。workflow 的总超时与单步超时由 YAML 中的 `timeout`、`timeout_seconds` 控制。

### 从 stdin 读取 prompt

```bash
echo "分析这段文本" | braidrun-agent --stdin -a researcher
cat document.txt | braidrun-agent --stdin -a writer -o summary.txt
```

`--stdin` 与 `-p/--prompt` 互斥，不能同时使用。

### 只校验 workflow

```bash
braidrun-agent -V workflow.yaml
braidrun-agent -V workflow-a.yaml -V workflow-b.yaml   # 校验多个文件
```

### Workflow dry-run（只显示执行计划，不实际运行）

```bash
braidrun-agent -w workflow.yaml --dry-run
braidrun-agent -w workflow.yaml -v topic=AI --dry-run
```

`--dry-run` 仅在 workflow 模式下有效。

### workflow Debug

```bash
braidrun-agent -w workflow.yaml --debug --debug-stop-on-entry
braidrun-agent -w workflow.yaml --debug --debug-breakpoint before_step:review
braidrun-agent -w workflow.yaml --debug --debug-breakpoint after_step:publish?quality_score>=8
braidrun-agent -w workflow.yaml --debug --debug-break-on-error
braidrun-agent -w workflow.yaml --debug --debug-breakpoint state_machine_state:approval_flow
```

当前 debug checkpoint：

- `before_step`
- `after_step`
- `step_error`
- `state_machine_state`
- `group_chat_round`
- `iteration_item`
- `agent_based_delegation`

断点格式：`[point:]stepName[?condition]`，省略 `point:` 时默认为 `before_step:`。条件遵循运行时 condition 语法（`var op value`）。

进入交互式 debug 控制台后可用命令：

| 命令 | 说明 |
|---|---|
| `c` / `continue` | 继续执行直到下一个断点 |
| `n` / `next` | 单步走到下一个 checkpoint |
| `s` / `snapshot` | 打印当前完整快照（变量、步骤输出、步骤结果） |
| `v [filter]` / `vars` | 列出变量（可按子串过滤） |
| `o [step]` / `outputs` | 列出步骤输出（可按步骤名过滤） |
| `r` / `results` | 列出所有步骤结果 |
| `b` / `breakpoints` | 列出当前所有断点 |
| `ba <spec>` | 添加断点：`[point:]step[?condition]` |
| `br <step|index>` | 删除断点 |
| `boe` / `break-on-error` | 切换 break-on-error 开关 |
| `edit <var>=<value>` | 修改工作流变量 |
| `history [index]` | 查看快照历史 / 查看指定快照 |
| `h` / `help` | 显示帮助 |

### MCP server 模式

```bash
braidrun-agent --list-mcp-tool-groups
braidrun-agent --mcp-server
braidrun-agent --mcp-server --mcp-tool-group workflow --mcp-tool-group file_system
braidrun-agent --mcp-server --mcp-tool-group workflow,file_system
```

`--mcp-tool-group` 可重复传入或用逗号分隔，省略时默认导出所有支持的工具组。`--mcp-server` 与 `--workflow` 互斥。

### 主要参数

| 参数 | 说明 |
|---|---|
| `-a`, `--agent` | preset ID，默认 `universal` |
| `-c`, `--configuration` | Agent 配置文件，支持完整配置格式或 preset-format |
| `-p`, `--prompt` | prompt 模式 |
| `--stdin` | 从 stdin 读取 prompt（与 `-p` 互斥） |
| `-w`, `--workflow` | workflow YAML 文件，可重复传入触发批量模式 |
| `--workflow-max-concurrency` | 多个 `--workflow` 同时传入时的最大并发数，`0` 表示不限制 |
| `-v`, `--var` | workflow 变量，格式 `key=value` |
| `-V`, `--validate-workflow` | 只校验 workflow（可重复） |
| `-m`, `--monitoring` | workflow 监控开关，默认 `true` |
| `--dry-run` | workflow 模式下只打印执行计划 |
| `--debug` | 开启 workflow 步骤级调试 |
| `--debug-stop-on-entry` | 在第一个 checkpoint 暂停 |
| `--debug-breakpoint` | 指定断点 `[point:]step[?condition]`（可重复） |
| `--debug-break-on-error` | 任意步骤失败自动暂停（隐含 `--debug`） |
| `--env` | 环境名，默认 `braidrun`（可选 `test`） |
| `-x`, `--proxy` | HTTP 代理 |
| `-o`, `--output` | 输出写入文件 |
| `-q`, `--quiet` | 静默模式 |
| `--timeout` | agent 模式执行超时（秒） |
| `--mcp-server` | 以 MCP stdio server 模式启动 |
| `--list-mcp-tool-groups` | 列出可暴露的 MCP 工具组 |
| `--mcp-tool-group` | 指定 MCP 工具组（可重复或逗号分隔） |
| `--list-presets` | 列出所有 preset 并退出 |
| `--list-strategies` | 列出所有执行策略并退出 |
| `--list-tools` | 列出所有 tool 类别并退出 |
| `--skill-whitelist-mode` | 启用 / 禁用 skill 白名单模式 |
| `--enabled-skill` | 在白名单模式下允许的 skill 名（可重复或逗号分隔） |
| `--disabled-skill` | 直接屏蔽的 skill 名（可重复或逗号分隔） |
| `--version` | 打印版本号 |

### 明确不要再这样写

- `braidrun-agent -a universal_agent ...`
- `braidrun-agent -a pdf_agent ...`
- 把宿主服务的 camelCase DTO 字段名直接写进 YAML
- 把 `--debug` / `--dry-run` 与多个 `-w` 同时使用

---

## workflow 工具集

如果 Agent 开启 `workflow` tool set，可优先使用这些工具做“查模板 + 校验 + 执行”的闭环：

- `listWorkflowTemplates`
- `createWorkflowFromTemplate`
- `validateWorkflow`
- `describeWorkflow`
- `executeWorkflow`
- `visualizeWorkflow`
- `getWorkflowMetrics`
- `generateExecutionReport`
- `getWorkflowStats`
- `saveWorkflowVersion`
- `listWorkflowVersions`
- `rollbackWorkflow`
- `compareWorkflowVersions`

对复杂 workflow 生成器或编辑辅助来说，推荐流程是：

1. 先 `listWorkflowTemplates`
2. 再读 `workflows/templates/` 中相关的 `test-workflow-*.yaml`
3. 生成后调用 `validateWorkflow`
4. 需要解释结构时再调用 `describeWorkflow`

---

## 可用策略 ID

在配置文件或 workflow YAML 中通过 `strategy` 字段指定：

| 策略 ID | 说明 |
|---|---|
| `chat` | 基础聊天，无工具调用 |
| `chat_with_summary` | 聊天 + 工具 + 结束摘要 |
| `continue_chat` | 持续对话，维护完整历史 |
| `just_work` | 极简执行，串行工具 |
| `just_work_parallel` | 极简执行，并行工具（多数预设的默认策略） |
| `just_work_parallel_reasoning` | 并行执行 + 推理步骤 |
| `react` | ReAct 推理模式 |
| `react_original` | koog 内置原版 ReAct |
| `plan_solve` | 计划-求解模式 |
| `plan_solve_reasoning` | 计划-求解 + 推理 |
| `tone` | 语气控制策略 |
| `tone_reasoning` | 语气 + 推理 |
| `single_run` | 单次运行，串行 |
| `single_run_parallel` | 单次运行，并行 |
| `single_run_reasoning` | 单次运行 + 推理 |
| `single_run_parallel_reasoning` | 单次运行并行 + 推理 |

> 推理类策略（带 `_reasoning` 后缀）依赖 `reasoning_interval` 与 `show_reasoning` 等参数，可在 agent 配置或 `agents.*.overrides` 中调节。

---

## Agent 配置文件

推荐使用附件 `config-template.yaml` 对应的“参数列表”格式。

如果用户只是要“可运行模板”，优先直接给附件，不要现场另起一套格式。

---

## workflow YAML 编写规则

### 顶层结构

最小骨架：

```yaml
name: my-workflow
version: "1.0.0"
description: "..."

agents:
  writer:
    preset: universal
    session_id_strategy: per_execution
    overrides:
      tool_set: [exit]

variables:
  topic: "AI"

workflow:
  - step: draft
    agent: writer
    input: "Write about {{var:topic}}."
```

当前常用顶层字段（与 `WorkflowDefinition` 对齐）：

- `name`
- `version`
- `description`
- `tags`
- `category`
- `variables`
- `variable_types`
- `agents`
- `workflow`
- `error_handling`
- `timeout`
- `directory_isolation`
- `concurrency`
- `knowledge_base`
- `code_preamble`
- `global_agent`

说明：

- `variables` 当前是扁平字符串映射，形如 `key: "value"`
- `variable_types` 是配套的可选类型映射（如 `topic: "string"`、`max_items: "int"`），主要供 Web 编辑器展示和校验
- `category` 是可选分类字段，主要供 web 端分组和筛选
- `code_preamble` **当前是按语言分组的 Map**：键是语言名（`python` / `bash` / `javascript` ...），值是 `{ inline | ref, description? }`，引擎会自动把对应语言的 preamble 拼接到该语言每个 `code` 步骤脚本头部
- `global_agent` 主要用于编辑器 / round-trip / Web 辅助，不是 CLI 正常执行调度的主入口

### Agent 定义

推荐写法：

```yaml
agents:
  reviewer:
    preset: universal
    session_id_strategy: per_execution
    overrides:
      system_prompt: "You are a strict reviewer."
      tool_set:
        - exit
        - file_system
        - skill_tools
```

兼容旧 YAML 的写法仍可解析，但新内容优先使用 `preset + overrides`。

#### 在 `agent_based.orchestrator` 中复用顶层 agent

当前 `AgentDefinition` 支持 `agent: <name>` 字段（在内部映射为 `agentRef`），用于在 `agent_based.orchestrator` 里直接引用 `agents.*` 中已声明好的 agent，避免重复写一份 orchestrator 配置：

```yaml
agents:
  orchestrator_agent:
    preset: universal_reasoning
    session_id_strategy: per_execution
    overrides:
      system_prompt: "You are the orchestrator."

workflow:
  - step: dynamic_run
    agent_based:
      orchestrator:
        agent: orchestrator_agent     # 复用已声明的 agent
      participants: [worker, reviewer]
      goal: "Complete the task for {{var:topic}}"
      max_steps: 12
```

约束：

- 不能在同一个 orchestrator 下同时设置 `agent` 与 `preset`，校验阶段会报错
- `agent` 必须指向 `workflow.agents` 中已存在的 key
- 不允许循环引用，存在循环时 `resolveParameters` 会抛 `Circular agent reference detected`

### 步骤主模式

每个步骤必须且只能使用一种主模式：

1. 单 Agent：`agent + input`
2. `group_chat`
3. `agent_based`
4. `code`
5. `classifier`
6. `state_machine`
7. `sub_workflow` — 引用另一个已保存的 workflow（"工作流模块"）作为一个原子步骤

> 注意：`manual_approval` 在 agent 解析器层面是步骤的**增强属性**，不是独立的主模式；七选一仍然以上述七种为准。宿主编辑器可以把"纯人工审批节点"呈现为独立类型，落地到 YAML 时是一个 `manual_approval.enabled = true` 且不挂载其他主模式的步骤（无 agent / code / groupChat 等）。
>
> `sub_workflow` 步骤需要 [WorkflowResolver] 才能解析:文件系统场景用 `FileSystemWorkflowResolver`,宿主服务可注入自定义 resolver。完整契约 / 调用语法 / 隔离行为见下方"sub_workflow 与工作流模块"章节。

### 常用增强能力

- `depends_on`
- `condition`
- `priority`
- `retry`
- `manual_approval`
- `timeout_seconds`
- `timeout`（兼容旧字符串格式，如 `"300s"`）
- `on_success`
- `on_failure`
- `parallel`
- `repeat_until`
- `extract`
- `iterate_over`
- `aggregate`

---

## 当前组合约束

- `parallel`：仅适用于单 Agent 步骤
- `repeat_until`：当前主要适用于单 Agent 或 `group_chat`；`code` / `agent_based` / `sub_workflow` 不支持
- `iterate_over`：仅适用于单 Agent 或 `code`
- `iterate_over`：不能与步骤级 `parallel`、`retry`、`repeat_until`、`manual_approval`、`timeout_seconds` 组合
- `state_machine`：外层不要再配置 `parallel`
- `state_machine` 内部状态步骤只能使用单 Agent、`group_chat`、`agent_based`、`code`、`classifier` 与 `extract`，不支持外层 `retry / parallel / manual_approval`
- `sub_workflow`：不能同 step 配 `iterate_over` / `repeat_until` / `parallel`(v1);允许 `extract` / `manual_approval` / `retry`
- `condition`：运行时只支持简单三段式表达式

---

## 条件表达式

运行时真正支持的操作符：

- `==`
- `!=`
- `>`
- `<`
- `>=`
- `<=`
- `contains`

正确示例：

```yaml
condition: route == coding
condition: score >= 8
condition: summary contains urgent
condition: route == "content"
```

错误示例：

```yaml
condition: "{{var:route}} == coding"   # condition 内部不写 {{...}} 模板
condition: a && b                       # 不支持 && / ||
condition: a || b
condition: (a == b)                     # 不支持括号
```

注意：

- `WorkflowParser` 对 `condition` 的静态检查保证“非空”、且对不含 `{{` 的 condition 检查“至少三段式”
- 真正的语义边界以运行时 `ConditionEvaluator` 为准
- 数字比较（`>` / `<` / `>=` / `<=`）会要求两端都是合法数字，否则记 warn 并视为 false
- `contains` 默认大小写不敏感
- `condition` 也会自动展开 `{{var:name}}` / `{{steps.x.output}}` 模板，但在 condition 内显式写模板会让结果出现引号或多余空白；推荐直接用变量名，如 `route == coding`

---

## 模板变量

支持：

- `{{var:name}}`
- `{{steps.step_name.output}}`
- `{{name}}`（无前缀写法，会先匹配步骤输出，再回退到变量）
- `{{step_name}}`（同上）

推荐优先使用显式形式：

- 工作流变量：`{{var:topic}}`
- 步骤输出：`{{steps.classify.output}}`

---

## 各类能力的当前写法

### `repeat_until`

- 使用 `condition`
- 使用 `max_iterations`
- 可选 `evaluate_agent`
- 可选 `evaluate_prompt`
- 可选 `extract_pattern + extract_variable`（必须同时提供或同时省略）
- 默认 `max_iterations = 5`

### `code`

当前支持语言（`CodeStepConfig.SUPPORTED_LANGUAGES`）：

- `python`
- `javascript`
- `typescript`
- `bash`
- `ruby`
- `lua`
- `cli`

字段：

- `language`（必填）
- `script`（与 `script_file` 二选一）
- `script_file`（与 `script` 二选一，运行时检查文件存在）
- `timeout`（默认 30 秒）
- `working_directory`（可选）

常见输入环境变量：

- `STEP_INPUTS`：所有上游步骤输出的 JSON 字符串
- `WF_VAR_xxx`：工作流变量（变量名转大写并把非字母数字转为 `_`）
- `STEP_OUTPUT_xxx`：上游步骤输出（步骤名按相同规则转换）

代码步骤通过 stdout 产出输出。

### `code_preamble`（按语言分组）

`code_preamble` 是 **顶层字段**，按语言分组，对应语言下的每个 `code` 步骤会自动拼接对应 preamble 到脚本头部。

```yaml
code_preamble:
  python:
    ref: ./skills/braidrun_workflow_utils.py    # 引用外部文件
    description: "共享 Python 工具函数"
  bash:
    inline: |                                    # 直接内联
      set -euo pipefail
      export LANG=en_US.UTF-8
    description: "Bash 安全默认设置"
```

每条 preamble 必须是 `inline` 或 `ref` 二选一，二者互斥。`ref` 文件在执行 `code` 步骤时才会被读取，运行时找不到文件会抛 `WorkflowExecutionException`。

> 不要再使用 “`code_preamble: { ref: ... }`” 这种顶层未分组的旧写法。当前 schema 必须按语言分桶。

### `classifier`

- 必须提供 `agent`
- 必须提供 `input`
- 至少 2 个 `categories`
- 必须提供 `output_variable`（默认 `classification`）
- `default_category` 必须属于已声明类别

### `extract`

支持两种方式，二选一：

- `pattern`：正则，捕获组 1 的值会被写入 `variable`
- `json_path`：JSONPath，必须以 `$` 开头

每个步骤的 extract 列表中变量名不得重复。

### `iterate_over`

常用字段：

- `source`
- `delimiter`（默认 `\n`，传 `json_array` 表示按 JSON 数组解析）
- `item_variable`（默认 `current_item`）
- `index_variable`（默认 `current_index`）
- `max_items`（默认 `0` 不限制）
- `parallel`（默认 false）
- `max_parallel`
- `results_variable`

### `aggregate`

当前支持策略（`AggregateConfig.SUPPORTED_STRATEGIES`）：

- `concat`
- `json_array`
- `numbered_list`
- `pick_longest`
- `pick_shortest`

至少 2 个 `sources`，必填 `output_variable`。

### `knowledge_base`

当前支持字段：

- `enabled`
- `storage_dir`
- `embedding_model`（默认 `text-embedding-3-small`）
- `embedding_provider`（默认 `openai`）
- `auto_index_outputs`
- `chunk_size`（必须 > 0）
- `chunk_overlap`（必须 < `chunk_size`）
- `max_indexed_documents`（0 = 不限制）
- `max_total_chunks`（0 = 不限制）
- `source_files`（每项含 `path` + 可选 `tags`，路径不能重复）
- `auto_inject_rag_tools`

当前确认的 `embedding_provider`：

- `openai`
- `ollama`

### `global_agent`

当前支持字段：

- `enabled`
- `agent`（一个完整的 `AgentDefinition`，需要带 `session_id_strategy`，建议 `per_execution`）
- `tool_parameters`
- `multimedia`（独立的多媒体工具配置，含 `api_key` / `base_url` / `image_model` / `audio_model`）

它主要用于 Web 编辑器导入导出和辅助编辑，不参与 workflow 正常 DAG 调度。

### `sub_workflow` 与工作流模块 (Workflow Module)

`sub_workflow` 是第 7 种主步骤模式,允许把另一个已保存的 workflow 作为一个原子步骤被引用。可被引用的 workflow 又可以可选地声明 `module` 顶层字段,作为"模块契约"对外公开 inputs / outputs API。

#### 顶层 `module` 块(被引用方/模块作者使用)

```yaml
name: video_generation
version: "2.0.1"

# 顶层 module 块声明这个 workflow 是一个可复用模块
module:
  display_name: "视频生成模块"
  description: "根据话题生成带字幕的短视频"
  category: content
  icon: "🎬"
  tags: [video, short-form]
  contract_version: "1.0.0"
  inputs:
    - name: topic
      type: string
      required: true
      description: "视频主题"
      example: "电池续航"
    - name: style
      type: enum
      required: false
      default: "cinematic"
      allowed_values: [cinematic, minimal, energetic]
  outputs:
    - name: video_path
      type: path
      source: "{{var:final_path}}"
    - name: duration_seconds
      type: number
      source: "{{var:duration_seconds}}"

agents: { ... }
variables:
  topic: ""
  style: "cinematic"
  final_path: ""
  duration_seconds: "0"
workflow: [ ... ]
```

`module.inputs[i].mapsToVariable` 默认等于 `name`,把外部 input 映射到 `workflow.variables` 内部某个 key。`module.outputs[i].source` 是模板表达式,在 child 执行完成时用 `resolveTemplate(source, childContext)` 解析。

#### 父 step `sub_workflow` 块(调用方使用)

```yaml
- step: render_video
  sub_workflow:
    workflow_id: 0d2c-...-ab12     # Web 场景:UUID
    # 或者 path: ./video_generation.yaml   (CLI 场景)
    version_strategy: pinned       # latest / pinned (range 是 Phase 4)
    pinned_version: "2.0.1"
    inputs:
      topic: "{{var:topic}}"       # 父→子映射,模板在父 context 中解析
      style: "energetic"
    outputs:
      final_video_path: video_path # 父变量名: 契约 output 名
      clip_seconds: duration_seconds
    kb_scope: isolated             # isolated (默认) / inherit / extend
    missing_reference_policy: error # error (默认) / warn / skip_step
    max_depth: 4
  depends_on: [prepare]
  extract:
    - json_path: "$.video_path"
      variable: captured_path
```

#### 引用方式

- `workflow_id` / `path` / `name` 三选一,exactly one
- Web 场景默认 `workflow_id`,CLI 场景默认 `path`
- `name` 是低优先级兼容项,多匹配时会 warning

#### 严格 vs 宽松模式

- **child 有 `module` 块** → 严格模式:父的 `inputs` key 必须在契约中,所有 required input 必须提供,enum / number 类型严格校验,父的 `outputs` value 是契约 output 名(不是模板)
- **child 没有 `module` 块** → 宽松模式:父的 `inputs` 直接写到 child variables,父的 `outputs` value 是 child context 模板表达式,父用 `required_inputs` / `optional_inputs` 列表手写契约

#### 默认全部隔离

- 子工作流执行时拥有**独立**的 `WorkflowExecutionContext`(不与父共享 variables / stepOutputs / agents)
- `agent_inheritance.mode` 默认 `none`,opt-in `inherit_all` 或 `inherit_named`
- `kb_scope` 默认 `isolated`,opt-in `inherit` 或 `extend`
- `directory_isolation`、`code_preamble`、`global_agent` 都是 child 自己的

#### 派生 executionId

子的 executionId = `${parentExecutionId}__${sanitizedParentStep}`,这样:

- 子拥有独立的 `WorkflowMetrics` 实例(可单独查询)
- 父 step 的 `events` 列表中会写入 `sub_workflow_started` 与 `sub_workflow_completed` 两个事件
- token 用量自动从 child metrics 聚合到父 step 的 completed 事件 detail

#### 循环检测

- **静态**: `WorkflowParser.detectSubWorkflowCycles` 在 validate 时通过 resolver 递归 DFS,检测 A→B→A 这种循环
- **运行时**: `WorkflowExecutor` 维护 `ThreadLocal<MutableList<String>> subWorkflowStack`,每次执行 `executeSubWorkflowStep` 时检查是否在栈中
- `max_sub_workflow_depth` 默认 8,可由 `errorHandling.maxSubWorkflowDepth` 或 step `sub_workflow.max_depth` 覆盖

#### 组合约束

- 不允许同 step 配 `iterate_over` / `repeat_until` / `parallel`(v1)
- 允许同 step 配 `extract`(常用于从 child 的 JSON output 取字段)
- 允许同 step 配 `manual_approval` / `retry`(包装整个子工作流执行)

#### CLI 用法

```bash
# 校验(自动接入 FileSystemWorkflowResolver,从 parent.yaml 所在目录搜索 child)
braidrun-agent -V parent.yaml

# 执行
braidrun-agent -w parent.yaml -v topic=AI

# Dry-run 显示计划(含 sub_workflow 引用标识)
braidrun-agent -w parent.yaml --dry-run
```


### `manual_approval`

当前支持字段：

- `enabled`
- `approvers`
- `timeout`（秒，默认 3600）
- `approval_message`

行为：

- 当 `manual_approval.enabled = true` 时，执行该步骤前会进入 `AWAITING_APPROVAL` 状态，写入到 `WorkflowMonitor`
- 在等待审批期间，宿主服务可以通过 approval API 做批准 / 拒绝
- 超时未决会抛出 `WorkflowExecutionException`
- 拒绝会抛出 `WorkflowApprovalRequiredException`
- 不能与 `iterate_over` 同步骤使用

### `state_machine`

当前支持字段：

- `states`
- `initial_state`
- `final_states`
- `max_transitions`（默认 64，必须 > 0）

约束：

- state key 与 `name` 必须一致
- transition 的 `target` 必须指向已定义 state
- 内部状态步骤不支持外层 `retry / parallel / manual_approval`
- 内部 step 仅可使用单 Agent / `group_chat` / `agent_based` / `code` / `classifier`，并可附带 `extract`

### `directory_isolation`

当前支持字段：

- `enabled`（默认 true）
- `base_dir`（默认 `.workflow-runs`）
- `working_dir_pattern`
- `output_dir_pattern`
- `persistence_dir_pattern`
- `shared_cache_dir`（默认 `.prompt_cache`）
- `shared_history_dir`（默认 `.agent_history`）
- `shared_skills_dir`（默认 `./skills`）
- `cleanup_on_completion`
- `cleanup_after_hours`（默认 72，0 表示关闭自动清理）

`*_pattern` 支持的占位符：`{base_dir}`、`{execution_id}`、`{step_name}`、`{agent_name}`、`{workflow_name}`。

### `concurrency`

当前支持字段：

- `enabled`（默认 false，保持串行）
- `max_concurrency`（默认 0 = 不限制）

控制的是“同一拓扑层内的步骤并发”，与 CLI 的 `--workflow-max-concurrency`（多 workflow 之间并发）是不同维度。

### `on_success` / `on_failure` / `error_handling.on_error`

`TransitionAction` 常见字段：

- `next`
- `parallel`
- `stop`
- `notify`
- `message`
- `rollback`

状态机 action 额外字段：

- `action`
- `key`
- `value`

推荐：

- 主控制流优先用 `depends_on + condition`
- `on_success` / `on_failure` / `on_error` 更适合补充型转移、恢复与通知
- `rollback` 可以执行，但不要把它当成复杂事务系统

### 失败语义与 `result.success`

执行器返回的 `WorkflowExecutionResult.success` 严格反映"是否所有步骤都按用户预期完成":

- `success = true` ⟺ 所有步骤都成功 **OR** 失败的步骤都被 `on_failure` / `errorHandling.onError` **显式恢复路径**处理过
- `success = false` ⟺ 至少有一个步骤失败,且没有被显式恢复路径处理(包括只靠 `errorHandling.continueOnError = true` 让执行继续的情况)

实际影响：

- `braidrun-agent` CLI 在出现"未恢复的失败"时会用非零退出码退出,适合 CI/CD
- Web 执行历史会标 `FAILED`,详情面板里仍能看到所有 step 的真实结果,不再出现"已完成 100%"配上红色失败步骤的视觉冲突
- 如果一个步骤失败但其 `on_failure` 已经把流程导向 `recover`,该失败被视为"已恢复",不会让整体 `success` 退化为 false

---

## 当前参考模板

复杂 workflow 作者或生成器，建议至少参考以下几类模板：

### Minimal 与基础执行

- `braidrun-agent/workflows/templates/test-workflow-minimal.yaml`
- `braidrun-agent/workflows/templates/test-workflow-simple.yaml`
- `braidrun-agent/workflows/templates/test-workflow-sequential.yaml`
- `braidrun-agent/workflows/templates/test-workflow-parallel.yaml`
- `braidrun-agent/workflows/templates/test-workflow-condition.yaml`
- `braidrun-agent/workflows/templates/test-workflow-variables.yaml`

### 6 种主模式

- `braidrun-agent/workflows/templates/test-workflow-group-chat.yaml`
- `braidrun-agent/workflows/templates/test-workflow-agent-based.yaml`
- `braidrun-agent/workflows/templates/test-workflow-code-steps.yaml`
- `workflows/templates/test-workflow-classifier.yaml`
- `workflows/templates/test-workflow-state-machine.yaml`

### 增强能力

- `braidrun-agent/workflows/templates/test-workflow-repeat-until.yaml`
- `braidrun-agent/workflows/templates/test-workflow-extract.yaml`
- `braidrun-agent/workflows/templates/test-workflow-iterate-over.yaml`
- `braidrun-agent/workflows/templates/test-workflow-aggregate.yaml`
- `braidrun-agent/workflows/templates/test-workflow-fan-out.yaml`
- `braidrun-agent/workflows/templates/test-workflow-error-handling.yaml`
- `braidrun-agent/workflows/templates/test-workflow-full-features.yaml`
- `braidrun-agent/workflows/templates/test-workflow-all-features.yaml`

### 基础设施与共享能力

- `braidrun-agent/workflows/templates/test-workflow-knowledge-base.yaml`
- `braidrun-agent/workflows/templates/test-workflow-long-running.yaml`
- `braidrun-agent/workflows/templates/test-workflow-session-sharing.yaml`
- `braidrun-agent/workflows/templates/test-workflow-multi-preset.yaml`
- `braidrun-agent/workflows/templates/test-workflow-with-keys.yaml`
- `braidrun-agent/workflows/templates/test-workflow-skill-testing.yaml`

### 生产参考

- `workflows/templates/code-review.yaml`
- `workflows/templates/prd-review-refinement.yaml`
- `workflows/templates/workflow-generator.yaml`
- `workflows/templates/approval-lifecycle-state-machine.yaml`

> `workflows/templates/` 下有 140+ 模板，覆盖营销、内容、数据、iOS / Android 开发、运维等领域；建议按主题筛选最相关的 1~3 个再参考。

---

## 校验与复杂 workflow 推荐做法

权威校验优先级：

1. `braidrun-agent -V workflow.yaml`
2. `workflow.validateWorkflow(workflowPath = "...")`
3. 程序内 `WorkflowParser.parseFile(...)` + `WorkflowParser.validateWorkflow(...)`

只做这些还不够：

- 不能只检查 YAML 是否能被 `PyYAML` 读取
- 不能只靠字符串搜索 camelCase
- 复杂 workflow 还要检查依赖图、步骤互斥、引用完整性、状态机 target、知识库配置和 runtime 条件表达式

复杂 workflow 生成或改写时，推荐流程：

1. 先读取本 skill
2. 再看当前 workspace 下的 `workflows/templates/`
3. 再看 `workflows/templates/` 中最相关的 `test-workflow-*.yaml`
4. 需要领域范式时，再看 `workflows/templates/` 中 1 到 3 个生产模板
5. 生成后跑权威校验

---

## 与宿主服务（可视化编辑器）的关系

- 宿主 DTO 通常用 camelCase
- Agent 侧 YAML 用 snake_case
- 最终执行语义由本库的 parser 和 executor 决定

最常见的映射错误：

| Web / DTO | YAML |
|---|---|
| `groupChat` | `group_chat` |
| `agentBased` | `agent_based` |
| `stateMachine` | `state_machine` |
| `repeatUntil` | `repeat_until` |
| `iterateOver` | `iterate_over` |
| `manualApproval` | `manual_approval` |
| `onSuccess` | `on_success` |
| `onFailure` | `on_failure` |
| `dependsOn` | `depends_on` |
| `directoryIsolation` | `directory_isolation` |
| `knowledgeBase` | `knowledge_base` |
| `globalAgent` | `global_agent` |
| `outputVariable` | `output_variable` |
| `defaultCategory` | `default_category` |
| `jsonPath` | `json_path` |
| `timeoutSeconds` | `timeout_seconds` |
| `maxConcurrency` | `max_concurrency` |
| `codePreamble` | `code_preamble` |
| `autoInjectRagTools` | `auto_inject_rag_tools` |

---

## 回答时的默认做法

如果用户没有给现成文件而是问“怎么写”，优先：

1. 给 preset ID，而不是旧 agent type 名称
2. 给附件模板的最小改写版
3. 用 snake_case 生成 YAML
4. 显式写 `session_id_strategy`
5. 明确指出组合约束和 `condition` 限制
6. 需要权威判断时提醒使用 `-V` 或 `workflow.validateWorkflow`
