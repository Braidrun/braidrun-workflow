# Braidrun Workflow Debug Guide

> **关于命令行示例**：文中 `braidrun-agent-cli` / `:braidrun-agent-cli:run` 形式的
> 命令来自本库早期配套的内置 CLI，当前开源版本只包含库本身、暂不附带 CLI 发行版。
> 这些示例中的参数与行为语义仍然准确，可作为理解功能的参考；以编程方式运行
> agent / workflow 请见 [GETTING_STARTED.md](GETTING_STARTED.md)。


## Overview

工作流引擎支持运行时调试（Phase 2）。

Phase 2 在 Phase 1 基础上进行了全面增强：

- 条件断点（仅当表达式为真时暂停）
- Break on Error（步骤失败时自动暂停）
- 快照历史回溯（查看任意历史断点处的状态）
- 变量 Diff 视图（对比相邻快照间的变量变化）
- 暂停时变量编辑（修改变量后继续执行）
- DAG 图调试可视化（断点标记、暂停高亮）
- 执行日志流面板（类终端的实时日志）
- Dry-run 预演模式（验证执行计划但不实际运行）

原有能力：

- 在工作流关键 checkpoint 暂停
- 查看当前上下文快照
- 继续执行
- 单步执行到下一个 checkpoint
- 配置和更新步骤断点

当前调试能力覆盖两条入口：

- CLI：通过 `braidrun-agent` 命令行进入交互式调试台

## Scope

Phase 1 当前覆盖的是“步骤级”和“复合步骤关键节点级”调试，不是任意粒度的执行内省。

支持暂停的点位如下：

- `before_step`
- `after_step`
- `step_error`
- `state_machine_state`
- `group_chat_round`
- `iteration_item`
- `agent_based_delegation`
- `sub_workflow_entry`
- `sub_workflow_exit`

当前明确不支持：

- 在工具调用内部挂起
- 在 LLM 请求或 token streaming 内部挂起
- 从某个断点恢复后”回放”之前步骤
- 保持工作流顶层并发调试

Phase 2 新增支持（不再受限）：

- ~~任意变量修改并回写执行现场~~ → 现在暂停时可编辑变量

注意：开启 Debug 后，工作流顶层 DAG 自动并发会被强制降为串行执行，以保证断点和单步语义稳定、可预测。

## Concepts

### 1. Checkpoint

Checkpoint 是执行器在关键时刻暴露出来的暂停点。只有执行流走到 checkpoint，调试器才有机会暂停。

例如：

- 某个普通步骤真正开始执行前
- 某个步骤执行完成后
- 某个 `state_machine` 状态刚进入时
- 某个 `group_chat` 轮次准备开始时

### 2. Breakpoint

Breakpoint 用于声明“当执行命中某个 checkpoint 时暂停”。

Phase 1 中，一个断点由以下维度组成：

- `point`：断点点位，例如 `before_step`
- `stepName`：目标步骤名，例如 `review_article`
- `once`（可选）：设为 `true` 时，断点仅触发一次后自动移除

如果未显式指定 `point`，默认视为 `before_step`。

### 3. Pause Reason

一次暂停一定有暂停原因。当前常见原因有：

- `stop_on_entry`：入口暂停
- `breakpoint`：命中断点
- `step`：单步执行后在下一个 checkpoint 停住
- `pause_request`：用户请求“在下一个可暂停点暂停”

### 4. Snapshot

每次暂停时都会捕获当前调试快照。Phase 1 快照包含：

- `variables`
- `stepOutputs`
- `stepResults`
- `skippedSteps`
- 当前 `location`

快照用于理解现场，不等价于完整执行内存转储。

## Breakpoint Semantics

### `before_step`

最常用的断点。步骤进入执行前暂停。

适合用于：

- 检查变量模板是否已正确渲染
- 确认某一步是否真的会执行
- 在执行高风险步骤前停住

### `after_step`

步骤成功完成并写回结果后暂停。

适合用于：

- 查看该步骤输出是否符合预期
- 检查 `extract` / `aggregate` / 变量写回效果
- 单步观察 DAG 后续条件变化

### `step_error`

步骤失败后暂停。

适合用于：

- 第一时间查看失败现场
- 检查错误信息、上游变量和部分结果

### `state_machine_state`

`state_machine` 外层步骤进入某个内部状态时暂停，`label` 会标识当前状态名。

适合用于：

- 调试状态迁移逻辑
- 检查状态机是否走到了预期状态

### `group_chat_round`

`group_chat` 某一轮某个发言者开始前暂停，`label` 类似 `round=2 speaker=reviewer`。

适合用于：

- 观察每轮对话推进
- 确认参与者顺序和终止条件

### `iteration_item`

`iterate_over` 的某个迭代项开始执行前暂停，`label` 类似 `3/10`。

适合用于：

- 检查当前 item 注入是否正确
- 定位某个特定迭代项的异常

### `agent_based_delegation`

`agent_based` 工作流在将任务真正委派给某个 worker agent 之前暂停，`label` 类似 `coder:task_2`。

适合用于：

- 观察 orchestrator 的委派行为
- 排查错误的 agent 分工

### `sub_workflow_entry`

父工作流即将进入某个 `sub_workflow` 步骤时暂停，适合在进入 child workflow 前确认 inputs 映射、继承策略和当前父上下文。

适合用于：

- 检查传给 child workflow 的输入是否完整
- 确认 `kb_scope` / `agent_inheritance` 是否符合预期

### `sub_workflow_exit`

child workflow 执行完成并准备把 outputs 映射回父工作流时暂停，适合检查 child 返回值和父变量写回结果。

适合用于：

- 确认 outputs 映射是否正确
- 检查 child workflow 返回后父流程是否能继续按预期执行

## CLI Debug

### 1. CLI 参数

| 参数 | 说明 |
|------|------|
| `--debug` | 启用工作流调试模式 |
| `--debug-stop-on-entry` | 在第一个 checkpoint 暂停（隐含 `--debug`） |
| `--debug-break-on-error` | 任何步骤失败时自动暂停（隐含 `--debug`） |
| `--debug-breakpoint <spec>` | 设置断点，可多次使用（隐含 `--debug`） |

### 2. 启动方式

最基础的启动方式：

```bash
braidrun-agent -w workflows/my-first-workflow.yaml --debug
```

入口暂停：

```bash
braidrun-agent -w workflows/my-first-workflow.yaml --debug-stop-on-entry
```

异常自动暂停：

```bash
braidrun-agent -w workflows/my-first-workflow.yaml --debug-break-on-error
```

指定步骤断点：

```bash
braidrun-agent -w workflows/my-first-workflow.yaml \
  --debug-breakpoint review_article
```

多种断点组合：

```bash
braidrun-agent -w workflows/my-first-workflow.yaml \
  --debug-break-on-error \
  --debug-breakpoint before_step:review_article \
  --debug-breakpoint after_step:publish_article \
  --debug-breakpoint state_machine_state:approval_flow
```

条件断点：

```bash
braidrun-agent -w workflows/my-first-workflow.yaml \
  --debug-breakpoint 'after_step:review?quality_score<5' \
  --debug-breakpoint 'before_step:publish?status==approved'
```

与 `-a` / `-c` 组合使用：

```bash
braidrun-agent -a coder -c my-agent.yaml \
  -w workflows/release-pipeline.yaml \
  --debug-stop-on-entry \
  --debug-break-on-error \
  -v topic=release
```

### 3. 断点参数格式

`--debug-breakpoint` 的完整格式为：

```text
[point:]stepName[?condition]
```

**规则：**

- 只写 `review`，等价于 `before_step:review`
- `point` 必须是已支持的调试点位
- `stepName` 必须是工作流实际步骤名
- `?condition` 可选，指定条件表达式（仅当条件为真时暂停）

**条件表达式语法：** `变量名 运算符 值`

支持的运算符：`==`, `!=`, `>`, `<`, `>=`, `<=`, `contains`

**示例：**

```text
review                                  → before_step:review
before_step:review                      → before_step:review
after_step:review?quality_score>=8      → 仅当 quality_score >= 8 时暂停
review?status==failed                   → 仅当 status == failed 时暂停
iteration_item:process?current_index>5  → 迭代到第 6 项后才暂停
```

### 4. 交互式调试台命令

CLI 命中断点后会进入交互式调试台，提供完整的调试能力。

#### 执行控制

| 命令 | 说明 |
|------|------|
| `c` / `continue` | 继续运行，直到下一个断点或结束 |
| `n` / `next` | 单步到下一个 checkpoint |

#### 信息查看

| 命令 | 说明 |
|------|------|
| `s` / `snapshot` | 打印完整当前快照（变量、输出、结果、跳过步骤） |
| `v` / `vars [filter]` | 显示所有变量（可选子串过滤） |
| `o` / `outputs [step]` | 显示步骤输出（可选步骤名过滤） |
| `r` / `results` | 显示所有步骤执行结果 |
| `history` | 列出所有快照历史（最多 50 条） |
| `history <index>` | 查看指定历史快照的完整内容 |

#### 断点管理

| 命令 | 说明 |
|------|------|
| `b` / `breakpoints` | 列出所有断点和 break-on-error 状态 |
| `ba <spec>` | 动态添加断点（格式同 `--debug-breakpoint`） |
| `br <step\|index>` | 按步骤名或索引删除断点 |
| `boe` | 切换 break-on-error 开关 |

#### 变量修改

| 命令 | 说明 |
|------|------|
| `edit <var>=<value>` | 修改工作流变量并立即生效 |

#### 帮助

| 命令 | 说明 |
|------|------|
| `h` / `help` | 显示帮助 |

### 5. 交互式调试示例

```
[debug] ⏸  paused at before_step:review  reason=breakpoint
[debug]    vars=5  outputs=1  steps=1  skipped=0

[debug] > v
[debug] variables (5):
[debug]   language = English
[debug]   max_results = 10
[debug]   query_term = AI market analysis
[debug]   quality_score = 3
[debug]   status = draft

[debug] > o draft
[debug] ── draft ──
这是初稿内容，讨论了 AI 市场的主要趋势...

[debug] > edit quality_score=8
[debug] variable set: quality_score = 8

[debug] > ba after_step:review?quality_score<5
[debug] breakpoint added: after_step:review?quality_score<5

[debug] > b
[debug] break-on-error: OFF
[debug] breakpoints (2):
[debug]   [0] before_step:review
[debug]   [1] after_step:review?quality_score<5

[debug] > boe
[debug] break-on-error: ON

[debug] > history
[debug] snapshot history (2 entries):
[debug]   [0] 14:32:01.234  before_step:draft  vars=3
[debug]   [1] 14:32:15.789  before_step:review  vars=5 ← current
[debug] use 'history <index>' to view a specific snapshot

[debug] > c
```

### 6. 单步语义说明

`next` 不是”源码级单行单步”，而是”checkpoint 级单步”。

例如：

- 从 `before_step:step_one` 单步，通常会停到 `after_step:step_one`
- 再单步一次，通常会停到 `before_step:step_two`

如果当前步骤内部是复合步骤，则也可能停到：

- `state_machine_state`
- `group_chat_round`
- `iteration_item`
- `agent_based_delegation`
- `sub_workflow_entry`
- `sub_workflow_exit`

### 7. CLI 调试建议

- 初次排障：`--debug-stop-on-entry`，先整体了解执行流
- 异常排查：`--debug-break-on-error`，直接停在出错现场
- 精确定位：`--debug-breakpoint before_step:<step>` + `after_step:<step>`
- 条件命中：`--debug-breakpoint 'after_step:step?var<threshold'`
- 运行中调整：进入调试台后用 `ba` / `br` 动态增删断点
- 变量修正：暂停后用 `edit var=value` 修改变量再继续
- 历史对比：用 `history` 回溯查看之前的执行状态

## Debug Workflow

推荐按下面顺序排障：

1. 先确认是否是“步骤没跑到”，还是“步骤跑了但结果不对”
2. 如果怀疑步骤没跑到：
   使用 `before_step:<step>`
3. 如果怀疑步骤结果不对：
   使用 `after_step:<step>`
4. 如果某一步直接失败：
   使用 `step_error:<step>`
5. 如果是复合步骤异常：
   对应使用 `state_machine_state`、`group_chat_round`、`iteration_item`、`agent_based_delegation`、`sub_workflow_entry`、`sub_workflow_exit`

一个常见组合是：

- 入口暂停
- 对目标步骤配置 `before_step`
- 对目标步骤再配置 `after_step`

这样可以同时看到“执行前状态”和“执行后结果”。

## Troubleshooting

### Q1. 开启了 Debug，但执行没有暂停

优先检查：

- 是否真的传入了 `--debug` 或 Web 的 `debug.enabled = true`
- 是否命中的步骤名正确
- 是否断点点位写对了
- 是否该步骤其实因为 `condition` 未满足而被跳过

### Q2. 为什么单步后停在复合步骤内部，而不是下一个普通步骤？

因为 Phase 1 的单步是“checkpoint 级”而不是“DAG 步骤级”。

复合步骤内部同样会暴露 checkpoint，所以 `next` 可能停在：

- 某个状态机状态
- 某个 group chat 轮次
- 某个 iterate_over 项
- 某个 agent_based 委派点

### Q3. 为什么开启 Debug 后并发没了？

这是当前设计使然。

如果顶层 DAG 仍并发执行，多个步骤会同时命中断点，单步和继续的语义会明显变乱，因此 Phase 1 强制把顶层并发降为串行。

### Q4. 条件断点为什么没有触发暂停？

优先检查：

- 条件表达式是否符合 `变量名 运算符 值` 格式
- 变量是否已存在于当前上下文中（可用 `v` 命令查看）
- 数值比较时，变量值是否确实是数字
- 如果条件求值失败，断点会降级为无条件暂停（而非跳过）

### Q5. 为什么 pause 不是立刻生效？

`pause` 的语义是“在下一个 checkpoint 暂停”，不是异步打断任意代码位置。

如果当前执行正处在：

- 外部工具调用中
- LLM 请求中
- 某段还没走到 checkpoint 的逻辑中

那么会等执行流到达下一个 checkpoint 时再停住。

## Phase 2 新增能力

Phase 2 所有能力在 CLI、Web、API 三个入口全面对齐。

### 1. 条件断点

断点可以附加条件表达式，仅当条件为真时才暂停。

CLI 启动参数：

```bash
braidrun-agent -w workflow.yaml \
  --debug-breakpoint 'after_step:review?quality_score<5' \
  --debug-breakpoint 'before_step:publish?status==approved'
```

CLI 交互式动态添加：

```
[debug] > ba after_step:review?quality_score<5
[debug] breakpoint added: after_step:review?quality_score<5
```

API：

```json
{
  “breakpoints”: [
    {
      “point”: “before_step”,
      “stepName”: “review”,
      “condition”: “quality_score < 5”
    }
  ]
}
```

条件表达式复用工作流条件语法：`变量名 运算符 值`。

支持的运算符：`==`, `!=`, `>`, `<`, `>=`, `<=`, `contains`。

### 2. Break on Error

一键开启"步骤失败时自动暂停"。

CLI 启动参数：

```bash
braidrun-agent -w workflow.yaml --debug-break-on-error
```

CLI 交互式动态切换：

```
[debug] > boe
[debug] break-on-error: ON
```


启用后，任何步骤失败都会在 `step_error` 点位自动暂停，无需手动为每个步骤配 `step_error` 断点。

### 3. 快照历史

每次暂停都会保存快照到历史记录（上限 50 条），可回溯查看任意断点处的状态。

CLI 交互式查看：

```
[debug] > history
[debug] snapshot history (3 entries):
[debug]   [0] 14:32:01.234  before_step:draft  vars=3
[debug]   [1] 14:32:15.789  after_step:draft  vars=4
[debug]   [2] 14:32:16.012  before_step:review  vars=5 <- current
[debug] use 'history <index>' to view a specific snapshot

[debug] > history 0
[debug] --- snapshot @ before_step:draft ---
[debug] variables (3):
[debug]   language = English
[debug]   ...
```


### 4. 变量 Diff

当查看快照历史时，可开启 Diff 模式查看相邻快照间的变量变化：

- 绿色：新增变量
- 黄色：修改变量

### 5. 暂停时变量编辑

在执行暂停时可修改工作流变量再继续执行。

CLI 交互式编辑：

```
[debug] > edit quality_score=8
[debug] variable set: quality_score = 8

[debug] > v quality
[debug] variables (1, filter='quality'):
[debug]   quality_score = 8
```


### 6. 动态断点管理

运行时动态增删断点，无需重启工作流。

CLI 交互式管理：

```
[debug] > b
[debug] break-on-error: OFF
[debug] breakpoints (1):
[debug]   [0] before_step:review

[debug] > ba after_step:review?quality_score<5
[debug] breakpoint added: after_step:review?quality_score<5

[debug] > br 0
[debug] breakpoint removed: before_step:review

[debug] > b
[debug] break-on-error: OFF
[debug] breakpoints (1):
[debug]   [0] after_step:review?quality_score<5
```

### 7. Dry-run 预演模式

不实际运行工作流，仅验证并展示执行计划。

CLI：

```bash
braidrun-agent -w workflow.yaml --dry-run
```

返回包含：

- 执行计划（每步的类型、依赖、条件）
- 条件评估结果（哪些步骤会执行/跳过）
- 缺失变量警告

## Phase 2 能力对齐矩阵（CLI）

| 能力 | CLI |
|------|-----|
| 基本断点 | `--debug-breakpoint` |
| 条件断点 | `step?condition` |
| Break on Error | `--debug-break-on-error` / `boe` |
| Stop on Entry | `--debug-stop-on-entry` |
| 暂停/继续/单步 | `c` / `n` |
| 变量查看 | `v` / `vars` |
| 步骤输出查看 | `o` / `outputs` |
| 变量编辑 | `edit var=val` |
| 快照历史 | `history` |
| 动态断点增删 | `ba` / `br` |
| 日志流 | 终端直接输出 |
| Dry-run | `--dry-run` |

## Recommended References

建议配合以下文档一起看：

- `README.md`
- `WORKFLOW_GUIDE.md`

如果你要排查具体的 workflow 语法问题，请优先回到 `WORKFLOW_GUIDE.md`；如果你要排查"执行过程为何如此推进"，请优先使用本调试指南。
