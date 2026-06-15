# Braidrun Agent API 参考文档

本文档详细介绍了 `braidrun-agent` 模块中的核心 API。

## CLI 参数 (v1.1.0 新增)

以下命令行标志在 v1.1.0 中新增：

| 标志 | 说明 |
|------|------|
| `--version` | 打印版本号并退出。 |
| `-o/--output <file>` | 将输出写入指定文件。 |
| `-q/--quiet` | 抑制非必要日志输出，仅显示核心结果。 |
| `--timeout <seconds>` | Agent 模式的执行超时时间（秒）。超时后 Agent 将优雅终止。 |
| `--stdin` | 从标准输入读取 prompt（适用于管道和脚本集成）。 |
| `--list-strategies` | 列出所有可用的执行策略并退出。 |
| `--list-tools` | 列出所有工具类别并退出。 |
| `--dry-run` | 工作流试运行模式。解析并验证工作流定义，但不实际执行步骤。 |

---

## Agent 构建与运行 (AgentCommon.kt)

### `buildAgent`

构建一个 `GraphAIAgent` 实例。

```kotlin
fun buildAgent(
    httpAccess: HttpAccess,
    parameters: List<ConfigurationParameter>,
    systemPrompt: String,
    toolRegistry: ToolRegistry,
    // ...
): GraphAIAgent<Input, Output>
```

### `buildAndRunStringAgent`

构建并运行一个以字符串作为输入输出的 Agent。

```kotlin
suspend fun buildAndRunStringAgent(
    httpAccess: HttpAccess,
    parameters: List<ConfigurationParameter>,
    systemPrompt: String,
    toolRegistry: ToolRegistry? = null,
    // ...
): Pair<AIAgent<String, String>, String?>
```

### `parseToolSet`

解析配置参数并生成工具注册表。

```kotlin
fun parseToolSet(
    parameters: List<ConfigurationParameter>,
    httpAccess: HttpAccess,
    tools: List<String> = emptyList(),
    // ...
): ToolRegistry
```

### `compactEnvSettings`

生成精简的环境设置字符串，仅包含日期、操作系统、工作目录、语言和基于 `tool_set` 的相关解释器信息（约 5-7 行，相比完整环境信息的 25+ 行，Token 消耗减少约 70%）。

```kotlin
fun compactEnvSettings(
    parameters: List<ConfigurationParameter>
): String
```

当配置参数 `compact_env` 为 `true`（默认）时，此函数替代完整的环境信息字符串用于 LLM 提示词构建。

---

## 技能系统 (AgentSkills.kt)

技能系统遵循 [Agent Skills 规范](https://github.com/anthropics/agent-skills)，实现三层渐进式加载（Progressive
Disclosure）策略。

### `ClaudeSkill`

表示一个 Claude 风格的技能包。

- `name`: 技能名称（宽松验证：非标准字符自动清理并警告，不阻止加载）。
- `description`: 技能描述（**必需**，无描述的技能将被跳过）。
- `content`: 技能的核心系统提示词（SKILL.md 中 frontmatter 之后的 Markdown 正文）。
- `attachments`: 关联的附件列表。
- `location`: SKILL.md 文件的绝对路径，用于目录披露和路径解析。
- `scope`: 技能发现来源范围（`"project"`, `"user"`, `"configured"`, `"additional"`, `"cache"`, `"builtin"`）。
- `baseDirectory`: 技能的基础目录（SKILL.md 的父目录），用于解析相对路径。
- `toDiscoverySummary()`: 返回名称和描述摘要，用于渐进式加载的第 1 层（Catalog）。
- `toFullContent()`: 返回用 `<skill_content>` XML 标签包裹的完整内容（第 2 层），包含技能目录路径和捆绑资源列表
  `<skill_resources>`。
- `listBundledResources()`: 列出技能目录中的捆绑资源文件（不读取内容），用于第 3 层按需加载。

### `SkillLoader`

从文件系统和 classpath 加载技能，支持多目录发现和内置静态技能。

- 自动跳过 `.git`、`node_modules` 等非技能目录。
- 扫描深度最大 5 层，目录数量上限 2000，防止大型目录树中的扫描失控。
- **宽松验证**: 名称格式问题仅警告不阻止加载，名称超 64 字符仅警告，名称与目录不匹配仅警告。
- **畸形 YAML 处理**: 支持包含未引用冒号的值（常见跨客户端兼容性问题），通过取第一个冒号后的全部内容作为值来处理。
- **名称冲突处理**: 项目级 > 配置路径 > 附加路径 > 用户级 > 内置。冲突时记录警告，高优先级技能获胜。
- `loadAllSkills()`: 从所有配置路径、标准路径和 classpath 内置资源发现并加载技能。
- `loadSkill(skillDir, scope)`: 从文件系统加载单个技能，包含宽松验证。
- `loadBuiltinSkills()`: 从 classpath 加载打包在 JAR 内的内置技能。通过 `builtin-skills-index.txt` 索引文件发现技能，返回
  scope 为 `"builtin"` 的技能列表。

### `SkillManager`

管理技能生命周期和集成。

- `initialize()`: 初始化管理器，从所有配置目录扫描并加载技能。
- `refresh()`: 重新发现和加载所有技能。
- `getDiscoverySummaries()`: 生成结构化 XML 格式的技能目录（`<available_skills>`），包含名称、描述和路径，附带行为指令。无技能时返回空字符串（不显示空目录块）。
- `createSkillSystemPrompt()`: 根据 `progressiveDisclosure` 配置，返回目录摘要或完整内容。
- `activateSkill(name)`: 激活技能并返回带结构化包裹的完整内容。支持**会话级去重** — 已激活的技能不会重复注入。
- `isSkillActivated(name)`: 检查技能是否已在当前会话中被激活。
- `getActivatedSkills()`: 返回当前会话中已激活的技能名称集合。
- `clearActivationTracking()`: 清除激活跟踪（用于同一会话内的新对话）。
- `detectRelevantSkills(message)`: 根据输入消息检测相关的技能。
- `getSkill(name)`: 按名称获取技能（支持大小写不敏感回退）。

### `SkillsConfiguration`

技能系统的详细配置类。

#### 技能发现配置

- `skillsPath`: 主技能存储路径（默认 `./skills`）。
- `additionalSkillPaths`: 额外的技能扫描目录列表（默认 `[]`）。
- `scanStandardPaths`: 是否自动扫描标准跨客户端目录 `.agents/skills/`、`.claude/skills/`、`.braidrun/skills/`（默认 `true`）。
- `enabled`: 是否启用技能系统（默认 `true`）。
- `skillWhitelistMode`: 显式白名单模式开关。`true` 时只允许白名单；`false` 时默认允许所有；`null` 时兼容旧配置。
- `enabledSkills`: 白名单中的技能名列表。开启白名单模式后仅这些技能可见；内置 skill 会在运行时自动加入白名单。
- `disabledSkills`: 禁止加载的技能黑名单。
- `autoDiscovery`: 是否启用自动发现模式（默认 `true`）。
- `maxSkillsPerRequest`: 每次请求最多加载的技能数（默认 `8`）。
- `progressiveDisclosure`: 是否先加载摘要以节省 Token（默认 `true`）。

#### 信任与安全配置

- `requireProjectTrust`: 是否要求项目标记为受信任才加载项目级技能（默认 `false`）。
- `trustedProjects`: 受信任的项目路径列表（默认 `[]`）。

#### 内置技能配置

- `builtinSkillsEnabled`: 是否加载打包在 JAR 内的内置静态技能（默认 `true`）。
- `builtinSkillsResourcePath`: 内置技能在 classpath 中的前缀路径（默认 `"/builtin-skills"`）。该目录下需包含
  `builtin-skills-index.txt` 索引文件，每行列出一个技能子目录名。

#### 钩子配置

- `hooksEnabled`: 是否启用钩子系统（默认 `true`）。
- `hookScriptExecutionEnabled`: 是否允许执行钩子中的处理器脚本（默认 `true`）。
- `hookTimeoutSeconds`: 处理器脚本执行的超时时间（秒，默认 `30L`）。
- `allowedScriptTypes`: 允许运行的脚本类型（如 `["py", "js", "kts"]`）。
- `userGlobalHooksDir`: 自定义用户全局钩子目录。
- `workspaceDir`: 工作区目录，用于加载工作区级别的钩子。

---

## 策略系统 (AgentStrategies.kt)

策略通过配置参数 `strategy` 的字符串 ID 来选择。

- 对 legacy inline agent 定义（`AgentDefinition.strategy`）来说，默认值是 `just_work_parallel`
- 当运行时收到未知策略名时，`AgentCommon.kt` 的兜底行为等价于 `single_run`

### 对话类策略

- **`chat`** — 基础聊天策略（koog 内置 `chatAgentStrategy()`），无工具调用。
- **`chat_with_summary`** (`chatWithSummaryStrategy`) — 聊天 + 工具调用 + 结束时生成摘要。
- **`continue_chat`** (`continuousChatStrategy`) — 持续对话策略，维护完整历史记录，支持历史压缩。

### 执行类策略

- **`just_work`** (`justDoWorkStrategy`) — 极简执行策略，不进行额外思考，直接执行。工具串行调用。
- **`just_work_parallel`** — 同上，但工具并行调用（`ToolCalls.PARALLEL`）。
- **`just_work_parallel_reasoning`** (`justDoWorkWithReasoningStrategy`) — 并行执行 + 每 N 轮穿插推理步骤。

### 单次运行策略（推荐）

- **`single_run`** (`singleRunWithParallelAbility`) — 单次运行，工具串行调用。支持历史压缩。
- **`single_run_parallel`** — 同上，但工具并行调用。
- **`single_run_reasoning`** (`singleRunWithParallelAbilityWithReasoning`) — 串行 + 推理步骤。
- **`single_run_parallel_reasoning`** — 并行 + 推理步骤。

### 推理类策略

- **`react`** (`reactStrategy`) — ReAct (Reasoning and Acting) 模式，适合需要复杂推理的任务。
- **`react_original`** — koog 内置原版 ReAct 策略。

### 计划-求解策略

- **`plan_solve`** (`planSolveStrategy`) — 先列出步骤，然后逐一执行，适合长链条任务。
- **`plan_solve_reasoning`** (`planSolveStrategyWithReasoning`) — 计划-求解 + 推理步骤。

### 语气策略

- **`tone`** (`toneStrategy`) — 带语气控制的对话策略，支持历史压缩。
- **`tone_reasoning`** (`toneReasoningStrategy`) — 语气策略 + 推理步骤。

### 通用配置参数

所有策略均支持以下可选参数：

- `history_compression`: 历史压缩配置（`HistoryCompressionConfig`），用于长会话的上下文管理。
- `strategy_name`: 自定义策略名称。
- 推理类策略额外支持：`reasoning_interval`（推理间隔，默认 1）、`show_reasoning`（是否显示推理过程，默认 `true`）。
- `compact_env`（Boolean，默认 `true`）: 启用时使用精简环境信息字符串构建 LLM 提示词（约 5-7 行，仅包含日期、OS、工作目录、语言及相关解释器），相比完整环境信息（25+ 行）Token 消耗减少约 70%。设为 `false` 可恢复完整环境信息以保持向后兼容。参见 `compactEnvSettings()` 函数。

---

## 钩子系统 (AgentHooks.kt)

### `BraidrunAgentHook`

表示一个生命周期钩子，定义在 `hooks/braidrun-agent/HOOK.md`。

- `name`: 钩子唯一标识名。
- `description`: 钩子功能描述。
- `events`: 触发该钩子的事件列表（如 `agent:bootstrap`, `message` 等）。
- `content`: 触发时注入的静态系统提示词内容。
- `emoji`: (可选) 在控制台日志中显示的图标。
- `homepage`: (可选) 钩子的文档主页。
- `requires`: (可选) 资格检查要求（见 `BraidrunHookRequires`）。
- `virtualFilePath`: (可选) 将内容作为虚拟文件注入（例如 `"RULES.md"`）。
- `handlerScript`: (可选) 动态处理脚本的绝对路径。
- `always`: (可选) 如果为 `true`，则忽略 `requires` 强制运行。
- `isWorkspaceHook`: 是否为工作区级别的钩子。

### `BraidrunHookRequires`

钩子的前置资格检查要求。

- `bins`: 必须在 PATH 中的二进制文件列表。
- `anyBins`: 列表中至少有一个二进制文件在 PATH 中。
- `env`: 必须设置的环境变量列表。
- `os`: 允许运行的操作系统列表（如 `["darwin", "linux"]`）。
- `config`: 必须存在的文件或目录路径列表。

### `BraidrunHookEvent`

钩子触发事件枚举（共 40 种，及其对应的 YAML 配置字符串）：

**原生生命周期事件（19 种）：**

- `AGENT_BOOTSTRAP` (`agent:bootstrap`): Agent 初始化，加载工作区文件前。
- `MESSAGE` (`message`): 任何消息事件。
- `MESSAGE_RECEIVED` (`message:received`): 接收到输入消息。
- `MESSAGE_TRANSCRIBED` (`message:transcribed`): 消息转录完成（如语音转文字）。
- `MESSAGE_PREPROCESSED` (`message:preprocessed`): 媒体/链接解析完成。
- `MESSAGE_SENT` (`message:sent`): 消息发送成功。
- `SESSION_START` (`session:start`): 会话开始。
- `SESSION_END` (`session:end`): 会话结束。
- `SESSION_COMPACT_BEFORE` (`session:compact:before`): 会话压缩（总结历史）前。
- `SESSION_COMPACT_AFTER` (`session:compact:after`): 会话压缩完成后。
- `COMMAND` (`command`): 执行任何 Agent 指令。
- `COMMAND_NEW` (`command:new`): 执行 `/new` 指令。
- `COMMAND_RESET` (`command:reset`): 执行 `/reset` 指令。
- `COMMAND_STOP` (`command:stop`): 执行 `/stop` 指令。
- `GATEWAY_STARTUP` (`gateway:startup`): 网关启动。
- `AGENT_ERROR` (`agent:error`): 遇到不可恢复错误。
- `SKILL_ACTIVATED` (`skill:activated`): 技能被激活（加载到会话上下文中），`config` 中包含 `skillName` 键。
- `SKILL_DEACTIVATED` (`skill:deactivated`): 技能激活追踪被清除（如新对话），`config` 中包含 `skills` 键（逗号分隔的技能名列表）。
- `AGENT_SHUTDOWN` (`agent:shutdown`): Agent/SkillManager 正在关闭。

**Koog 框架事件（21 种）：**

以下事件由 Koog 框架 `handleEvents` DSL 自动触发，通过 `createHookAwareInstallFeatures` 桥接到钩子系统：

- `AGENT_STARTING` (`agent:starting`): Koog Agent 开始执行。
- `AGENT_COMPLETED` (`agent:completed`): Koog Agent 执行完成，`config` 中包含 `result` 键。
- `AGENT_CLOSING` (`agent:closing`): Koog Agent 资源释放中。
- `STRATEGY_STARTING` (`strategy:starting`): 策略开始执行。
- `STRATEGY_COMPLETED` (`strategy:completed`): 策略执行完成。
- `NODE_EXECUTION_STARTING` (`node:execution:starting`): 图节点开始执行。
- `NODE_EXECUTION_COMPLETED` (`node:execution:completed`): 图节点执行完成。
- `NODE_EXECUTION_FAILED` (`node:execution:failed`): 图节点执行失败。
- `SUBGRAPH_EXECUTION_STARTING` (`subgraph:execution:starting`): 子图开始执行。
- `SUBGRAPH_EXECUTION_COMPLETED` (`subgraph:execution:completed`): 子图执行完成。
- `SUBGRAPH_EXECUTION_FAILED` (`subgraph:execution:failed`): 子图执行失败。
- `LLM_CALL_STARTING` (`llm:call:starting`): LLM API 调用开始。
- `LLM_CALL_COMPLETED` (`llm:call:completed`): LLM API 调用完成。
- `LLM_STREAMING_STARTING` (`llm:streaming:starting`): LLM 流式传输开始。
- `LLM_STREAMING_COMPLETED` (`llm:streaming:completed`): LLM 流式传输完成。
- `LLM_STREAMING_FAILED` (`llm:streaming:failed`): LLM 流式传输失败。
- `LLM_STREAMING_FRAME_RECEIVED` (`llm:streaming:frame:received`): 收到流式帧。
- `TOOL_CALL_STARTING` (`tool:call:starting`): 工具调用开始，`config` 中包含 `toolName` 键。
- `TOOL_CALL_COMPLETED` (`tool:call:completed`): 工具调用完成，`config` 中包含 `toolName` 键。
- `TOOL_CALL_FAILED` (`tool:call:failed`): 工具调用失败，`config` 中包含 `toolName` 和 `error` 键。
- `TOOL_VALIDATION_FAILED` (`tool:validation:failed`): 工具输入验证失败，`config` 中包含 `toolName` 和 `error` 键。

### `createHookAwareInstallFeatures`

创建一个 Koog `GraphAIAgent.FeatureContext` 安装器，将所有 Koog 框架事件桥接到 braidrun-agent 的钩子分发系统。

- `skillManager`: 用于钩子分发的 SkillManager 实例；为 null 时仅执行日志记录。
- `sessionId`: 传递给钩子脚本的当前会话标识符。

### `BraidrunHookContext`

传递给处理脚本 (`handlerScript`) 的 stdin JSON 结构。

- `event`: 触发的事件类型字符串（如 `"message:received"`）。
- `sessionKey`: 当前会话的唯一标识符。
- `workspaceDir`: 当前工作区目录的绝对路径。
- `skillName`: 拥有该钩子的技能名称。
- `config`: 传递给脚本的配置键值对映射（部分事件会附加额外键，如 `skill:activated` 事件的 `skillName`、`skill:deactivated`
  事件的 `skills`）。

### 钩子发现（多目录扫描）

`loadAllHooks()` 的扫描范围与 `loadAllSkills()` 完全一致，覆盖以下目录：

1. **主配置路径** (`skillsPath`)
2. **额外配置路径** (`additionalSkillPaths`)
3. **跨客户端标准路径**（启用 `scanStandardPaths` 时）：
    - 项目级：`<project>/.braidrun/skills/`、`<project>/.agents/skills/`、`<project>/.claude/skills/`
    - 用户级：`~/.braidrun/skills/`、`~/.agents/skills/`、`~/.claude/skills/`
4. **用户全局钩子目录** (`~/.braidrun/hooks/braidrun-agent/`)
5. **工作区钩子目录** (`workspaceDir`)

同名钩子的优先级（从低到高）：用户级 skill hooks → 额外路径 skill hooks → 配置路径 skill hooks → 项目级 skill hooks → 用户全局
hooks → 工作区 hooks。

### `BraidrunHookScriptResult`

处理脚本通过 stdout 返回的 JSON 结构。

- `injectContent`: (可选) 动态生成并注入到系统提示词的文本内容。
- `virtualFiles`: (可选) 动态生成的虚拟文件列表（见 `BraidrunVirtualFile`）。
- `messages`: (可选) 在操作控制台显示的提示信息。

### `BraidrunVirtualFile`

- `path`: 虚拟文件路径（如 `"context/memory.md"`）。
- `content`: 文件内容。

---

## 状态与持久化 (AgentState.kt)

### `loadHistoryMessages`

加载会话的历史消息。支持从本地文件或 MongoDB 加载（基于 `ConfigurationParameter` 的设置）。

### `saveHistoryMessage`

保存新的消息到持久化存储中。

### `PersistenceStorageProvider`

用于 Agent 检查点（Checkpoint）保存的接口。

- `MongoDbCustomStorageProvider`: 基于 MongoDB 的持久化实现，支持 `saveCheckpoint` 和 `getLatestCheckpoint`。

---

## 工具类 (tools 目录)

模块提供了丰富的内置工具，共 25+ 个工具集：

### 核心工具

- `ShellTools`: 跨平台 Shell 命令执行（自动检测 Windows/macOS/Linux），支持超时控制、工作目录、环境变量、输出截断和多行脚本执行。通过 `SubprocessExecutor` 抽象层支持本地（`NativeSubprocessExecutor`）和 Docker 隔离（`DockerSubprocessExecutor`）两种执行模式。
- `WebTools`: 网页抓取、HTTP 请求（GET/POST/通用）、文件下载、链接提取、网页搜索（网页/新闻/图片搜索）。
- `SkillTools`: 运行时搜索、安装、卸载和刷新技能。
- `SubAgentTools`: 管理和分发任务给子智能体，支持并行执行。
- `BrowserTools`: 35+ 个 Playwright 浏览器自动化操作。`browser_navigate` 仅接受 `http(s)://` URL（`file://` / `javascript:` / `data:` / `blob:` 在工具层拒绝）；`browser_set_cookies` 单次最多 256 KiB / 200 cookie。
- `TerminalInteractiveTools`: 终端交互（与用户对话、请求输入）。
- `ExitTool`: 优雅终止 Agent 运行。

### 文件与数据工具

- `ReadFileTool / WriteFileTool / EditFileTool / ListDirectoryTool`: koog 内置文件系统操作（包含在 `file_system` 工具集中）。
- `FileManagementTools`: 文件管理（复制、移动、重命名、删除、创建目录、获取文件信息、搜索文件、读取指定行范围、压缩/解压、计算哈希、文件比较），共
  12 个工具。包含在 `file_system` 工具集中，也可通过 `file_management` 单独启用。
- `DatabaseTools`: 数据库查询工具（SQLite + 通用 JDBC），支持 `queryDatabase`、`listTables`、`describeTable`。
- `DataTransformTools`: 数据格式转换（JSON↔YAML、Base64 编解码、URL 编解码、Markdown↔HTML、XML 解析/查询、JSONPath 查询）。
- `OfficeCsvTools`: CSV 文件读取、筛选、合并和转换。

### 文档处理工具

> **Phase 6 合并 (2026-04)**：下面列的都是单一工具组名，直接在 `tool_set` 里写组名即可；
> Kotlin 代码层的 `*AdvancedTools` / `*EnhancedTools` 分层类已经被 `@Deprecated`
> 或物理合并，不要再直接实例化它们。

- **`pdf`** → `PDFTools`：PDF 读取、抽取文本、渲染、拆分、合并、页面范围抽取/旋转/删除、
  插图、AcroForm 表单填写、加密/解密、元数据、页眉页脚、文本框标注。（Phase 6 已吸收
  原 `PDFAdvancedTools` 的全部 @Tool。）
- **`iwork`** → `IWorkTools`：Apple Pages (.pages) / Keynote (.key) / Numbers (.numbers)
  读取与 QuickLook 预览 PDF/图片抽取。（Phase 6 已吸收 `ApplePagesTools` /
  `AppleKeynoteTools` / `AppleNumbersTools`；`pagesInfo` / `keynoteInfo` /
  `numbersInfo` 等格式专用 @Tool 方法名保留。）
- **`word`** → `WordTools` (+ 旧的 `WordAdvancedTools` / `WordEnhancedTools` 当前仍内部
  注册在此组下，`@Deprecated`)：基础 docx 读写、图片插入、查找替换、CSV→表格、
  Markdown→docx、字体/颜色/样式、页眉页脚、目录、超链接、分页。
- **`excel`** → `ExcelTools` (+ 旧的 `ExcelAdvancedTools` / `ExcelEnhancedTools` 当前仍内部
  注册在此组下，`@Deprecated`)：基础 xlsx 读写、区域 CSV 导出、追加/自动列宽/加工作表、
  富格式（字体/边框/填充/合并单元格/冻结窗格/图片）。
- **`powerpoint`** → `PowerPointTools` (+ 旧的 `PowerPointAdvancedTools` /
  `PowerPointEnhancedTools` 当前仍内部注册在此组下，`@Deprecated`)：基础 pptx 读写、
  渲染幻灯片为图片、加文字页、插图表项目符号列表、复制幻灯片、合并演示、导出全图。
- **`csv`** → `CSVTools`：CSV 读取/预览/列选取/行过滤/合并/与 XLSX 互转（Apache Commons CSV）。
- **`office`** → 便捷包：勾选 office 等同 `word + excel + powerpoint + csv`，不要再重复勾个别项。
- **`ocr`** → `OCRTools`：基于 Tesseract 的图片文字识别。

### 开发与执行工具

- `CodeExecutionTools`: 代码片段执行（Python、JavaScript、通用解释器），支持 pip/npm 包安装（sandbox 模式下禁用包安装）。通过 `SubprocessExecutor` 抽象层支持本地和 Docker 隔离执行。
- `GitTools`: 结构化 Git 操作（status、diff、log、branch、add、commit、checkout、pull、push、show、stash、summary），共 12 个工具。

### 多媒体与图像工具

- `MultimediaGenerationTools`: AI 图片生成（多模型）和 AI 音频生成。
- `ImageProcessingTools`: 图片处理（缩放、裁剪、旋转、格式转换、压缩、合成、水印、翻转、信息获取），共 9 个工具。

### 通信工具

- `EmailTools`: 邮件工具（SMTP 发送 + IMAP 读取/搜索/文件夹列表）。
- `InstantMessagingTools`: 即时通讯集成（Telegram、钉钉、企微、飞书、Slack、Discord、WhatsApp）。

### 效率与记忆工具

- `CalendarTools`: 日期时间工具（获取当前时间、日期加减计算、日期差值、时间戳转换、时区转换、日历显示）。
- `KnowledgeMemoryTools`: 持久化知识记忆（键值存储 + 笔记管理），基于 JSON 文件的跨会话记忆。

### RAG 语义检索工具

- `RAGTools`（工具集名称：`rag_tools`）: 基于向量嵌入的文档索引与语义检索工具集，提供完整的 RAG（Retrieval-Augmented Generation）能力。
    - `indexDocument(documentId, content, tags)`: 将文本内容索引到知识库，自动分块和向量嵌入。
    - `indexFile(filePath, tags)`: 从文件系统读取文件并索引到知识库，支持常见文本格式。
    - `indexDirectory(directoryPath, extensions, tags)`: 递归扫描目录并批量索引所有匹配的文本文件。
    - `searchKnowledge(query, topK)`: 基于自然语言查询进行语义检索，返回最相关的文档片段及相似度分数。
    - `listDocuments()`: 列出知识库中所有已索引的文档及其元数据。
    - `deleteDocument(documentId)`: 删除指定文档及其所有分块和向量。
    - `getDocumentInfo(documentId)`: 获取指定文档的详细信息，包括分块预览。
    - `clearKnowledgeBase()`: 清空整个知识库。
    - **配置参数**:
        - `rag_storage_dir`: 索引存储目录（默认 `.rag-index`）
        - `rag_embedding_model`: 嵌入模型名称（默认 `text-embedding-3-small`）
        - `rag_embedding_base_url`: 嵌入 API 地址（默认 `https://api.openai.com/v1`）
        - `rag_embedding_api_key`: 嵌入 API 密钥（可选，默认使用 OPENAI_API_KEY）
        - `rag_chunk_size`: 分块大小（默认 1000 字符）
        - `rag_chunk_overlap`: 分块重叠（默认 200 字符）
        - `rag_default_top_k`: 默认搜索返回数量（默认 5）

### 工作流与业务工具

- `WorkflowTools`: 工作流编排、执行、监控和版本控制。
- `AppleAppInfoTools`: Apple App Store 应用信息查询。

---

## 工作流编排 API

工作流系统提供声明式 YAML 工作流定义、DAG 执行引擎、实时监控、版本控制、迭代精炼（`repeat_until`）、Agent-based 动态编排（`agent_based`）和工作流级共享知识库（`knowledge_base`）功能。

### 工作流 YAML 完整 Schema

```yaml
# ─── 顶层字段 ───
name: string                          # 必需。工作流名称
version: string                       # 可选，默认 "1.0.0"
description: string                   # 可选。工作流描述

variables:                            # 可选。全局变量（支持 {{var:key}} 或 {{key}}）
  key: "value"

agents:                               # 通常必需。若所有步骤都是 code 步骤可为空
  agent_name:
    # ── 预设模式（推荐） ──
    preset: string                    # 预设 ID（引用 AgentPresetRegistry 中的预设）
    overrides:                        # 可选。覆盖预设中的参数
      system_prompt: string
      tool_set: [string]
      strategy: string
      # ... 任何 ConfigurationParameter 兼容的 key-value

    # ── 旧模式（向后兼容） ──
    type: string                      # 兼容旧 YAML，例如 "universal_agent"
    strategy: string                  # 默认 "just_work_parallel"
    name: string                      # Agent 显示名称
    system_prompt: string             # 系统提示词
    tools: [string]                   # 工具集列表
    llm:                              # LLM 配置
      model: string                   # 模型 ID，如 "anthropic/claude-3-5-sonnet"
      provider: string                # 提供商（见 src/main/resources/models/ 下的 YAML 文件）
      # 支持: openrouter | openai | anthropic | deepseek | google | ollama
      #        xai | qwen | qwen_direct | kimi | minimax | mistral
      #        perplexity | meta | lmstudio
      temperature: number             # 默认 1.0
      max_tokens: integer             # 可选
    llm_provider_keys:                # API 密钥映射
      openrouter: string
      openai: string
      anthropic: string
      deepseek: string
      google: string
      dashscope: string               # Qwen 直连 (DashScope API)
      kimi: string                    # Kimi / Moonshot AI
      minimax: string                 # MiniMax
      lmstudio: string               # LM Studio（通常不需要 key）
      mistral: string                 # Mistral AI
      perplexity: string             # Perplexity
    # 执行参数
    retry_max_attempts: integer       # 重试次数
    retry_initial_delay: integer      # 初始延迟（毫秒）
    retry_max_delay: integer          # 最大延迟（毫秒）

    sub_agent_strategy: string        # 子 Agent 策略
    strategy_name: string             # 策略名称
    max_iterations: integer           # 最大迭代次数
    reasoning_interval: integer       # 推理间隔（秒）
    show_reasoning: boolean           # 是否显示推理过程
    num_choices: integer              # LLM 完成数量
    # 缓存参数
    cache_policy: string              # memory | redis | file
    memory_cache_max_entries: integer
    file_cache_storage: string
    max_files: integer
    redis_client_url: string
    redis_cache_prefix: string
    redis_duration: string
    # 持久化参数
    persistence_storage_type: string  # memory | mongodb | file
    enable_persistence: boolean
    persistence_storage_root: string
    # 历史参数
    history_enabled: boolean
    history_storage_root: string
    restore_session_id: string
    history_max_messages: integer            # 注入侧上限（读取多少条进 prompt），默认 50
    history_max_persisted_messages: integer  # 持久化侧滑动窗口（file 后端，默认 1000；设 0 关闭）
    session_id: string                # 会话 ID
    session_id_strategy: string       # auto | per_execution | per_agent | fixed; generated workflows should set this explicitly instead of relying on the default
    # 技能配置
    skills_config:
      skillsPath: string
      enabled: boolean
      skillWhitelistMode: boolean
      hooksEnabled: boolean
    # Langfuse 追踪
    enable_langfuse_tracing: boolean
    langfuse_url: string
    langfuse_public_key: string
    langfuse_secret_key: string
    # Playwright 浏览器
    PLAYWRIGHT_HEADLESS: boolean
    PLAYWRIGHT_USER_AGENT: string
    PLAYWRIGHT_ARGS: [string]
    # Koog 0.8.0 Tier 1 (opt-in)
    cascade_fallback_enabled: boolean       # CascadingFallbackPromptExecutor 多 tier 容错
    tokenizer_enabled: boolean              # MessageTokenizer feature
    tokenizer_enable_caching: boolean
    tracing_enabled: boolean                # Tracing feature（NDJSON / log / 远程 SSE）
    tracing_file_path: string
    tracing_to_log: boolean
    # Koog 0.8.0 Tier 2 (opt-in)
    weak_model_tool_fix_enabled: boolean    # 弱模型 tool-call 修复
    weak_model_tool_fix_max_retries: integer
    weak_model_tool_fix_manual_only: boolean
    tool_choice: string                     # auto | required | none | <tool_name>
    long_term_memory_enabled: boolean       # LongTermMemory（多轮 RAG）
    long_term_memory_namespace: string      # 默认 "ltm:<user_id>:<session_id>"
    long_term_memory_top_k: integer         # 默认 5
    long_term_memory_ingest_roles: [string] # ["user", "assistant"] / "system" / "tool"
    long_term_memory_timing: string         # on_agent_completion (默认) | on_llm_call
    long_term_memory_augmenter: string      # system (默认) | user
    agent_memory_enabled: boolean           # AgentMemory（结构化 facts）
    agent_memory_storage_dir: string        # 默认 .workflow-web-storage/memory/agent-memory
    agent_memory_encryption_key: string     # AES-256-GCM；解码后必须 ≥32 字节，否则 fail-closed 到 NoMemory
    agent_memory_agent_name: string         # 默认 session_id
    agent_memory_feature_name: string       # 默认 "workflow-agent"
    agent_memory_product_name: string       # 默认 "braidrun-workflow"
    rollback_tool_pairs:                    # WorkflowRollbackRegistry 正/反工具对
      - forward: string
        rollback: string

global_agent:                         # 可选。供编辑器/UI 保存的工作流级默认 agent 配置
  preset: string
  overrides:
    tool_set: [string]

workflow:                             # 必需。步骤列表（按 DAG 拓扑排序执行）
  - step: string                      # 必需。步骤唯一标识
    # 每个步骤必须六选一：
    # 1) agent + input
    # 2) group_chat
    # 3) agent_based
    # 4) code
    # 5) classifier
    # 6) state_machine
    agent: string                     # 单 Agent 模式时必需
    input: string                     # 单 Agent 模式时必需
    group_chat:                       # Group Chat 模式
      participants: [string]
      moderator: string
      max_rounds: integer
      speaker_selection: string       # round_robin | random
      termination_keyword: string
      initial_message: string
      summary_agent: string
    agent_based:                      # Agent-based 动态编排模式
      orchestrator:
        preset: string
        overrides:
          system_prompt: string
          strategy: string
      participants: [string]
      goal: string
      max_steps: integer
      budget_tokens: integer
      timeout_seconds: integer
    code:                             # 确定性代码步骤模式
      language: string                # python | javascript | typescript | bash
      script: string                  # 与 script_file 二选一
      script_file: string
      timeout: integer
      working_directory: string
    classifier:                       # Classifier 路由模式
      agent: string
      input: string
      categories:
        - name: string
          description: string
      output_variable: string
      default_category: string
    state_machine:                    # 复合状态机节点模式
      initial_state: string
      final_states: [string]
      max_transitions: integer
      states:
        state_name:
          name: string
          step:                       # 可选。执行状态；控制状态可不写
            # 内部状态必须五选一：
            # 1) agent + input
            # 2) group_chat
            # 3) agent_based
            # 4) code
            # 5) classifier
            agent: string
            input: string
            group_chat: {}
            agent_based: {}
            code: {}
            classifier: {}
            extract:
              - pattern: string
                json_path: string
                variable: string
          on_enter: []                # 可选。进入状态时执行的动作
          on_exit: []                 # 可选。离开状态时执行的动作
          auto_event: string          # 默认 "enter"
          success_event: string       # 默认 "complete"
          failure_event: string       # 默认 "error"
          transitions:
            - event: string
              target: string
              condition: string
              actions: []

    depends_on: [string]              # 可选。依赖的步骤名称列表
    condition: string                 # 可选。条件表达式（不满足则跳过）
    priority: integer                 # 可选，默认 0。同层级步骤优先级（高值优先）
    timeout_seconds: integer          # 可选。步骤超时（秒，推荐）
    timeout: string                   # 可选。兼容旧格式，如 "300s"
    state: string                     # 兼容保留字段；不要在新的 YAML 中使用
    retry:                            # 可选。步骤级重试配置
      max_attempts: integer           # 默认 3
      backoff: string                 # LINEAR | EXPONENTIAL | CONSTANT，默认 EXPONENTIAL
      initial_delay: integer          # 毫秒，默认 1000
      max_delay: integer              # 毫秒，默认 60000
    parallel:                         # 可选。仅支持单 Agent 步骤的并行子任务
      tasks: [string]                 # 子任务名称列表
      aggregate_results: boolean      # 默认 true
      max_parallel: integer           # 最大并发数
    manual_approval:                  # 可选。手动审批
      enabled: boolean                # 是否启用
      approvers: [string]             # 审批者列表
      timeout: integer                # 审批超时（秒），默认 3600
      approval_message: string        # 审批提示消息
    on_success:                       # 可选。成功后的动作列表
      - next: string                  # 跳转到指定步骤
        stop: boolean                 # 是否停止工作流
        notify: string                # 通知目标
        message: string               # 消息内容
        rollback: string              # 回滚到指定步骤
        parallel: [string]            # 并行执行步骤列表
        action: string                # 自定义动作
        key: string                   # 设置变量键
        value: string                 # 设置变量值
    on_failure:                       # 可选。失败后的动作列表（同 on_success 结构）
    repeat_until:                     # 可选。主要用于单 Agent / group_chat，不能与 code、agent_based 组合
      condition: string               # 必需。终止条件表达式（语法同步骤 condition）
      max_iterations: integer         # 可选，默认 5。最大迭代次数
      evaluate_agent: string          # 可选。评估 Agent 名称（引用 agents 中的定义）
      evaluate_prompt: string         # 可选。评估 prompt 模板（支持 {{steps.xxx.output}}）
      extract_pattern: string         # 可选。从评估结果提取变量的正则表达式
      extract_variable: string        # 可选。提取值存入的变量名（与 extract_pattern 配对）
    extract:                          # 可选。通用结构化输出提取
      - pattern: string               # 正则表达式（与 json_path 二选一）
        json_path: string             # JSON path 表达式（与 pattern 二选一）
        variable: string              # 必需。提取值存入的变量名
    iterate_over:                     # 可选。仅支持单 Agent 步骤或 code 步骤
      source: string                  # 必需。列表数据源（支持模板变量）
      delimiter: string               # 可选，默认 "\n"。分隔符，也支持 "json_array"
      item_variable: string           # 可选，默认 "current_item"。当前项变量名
      index_variable: string          # 可选，默认 "current_index"。当前索引变量名
      max_items: integer              # 可选，默认 0（不限制）。最大遍历项数
      parallel: boolean               # 可选，默认 false。是否并行遍历
      max_parallel: integer           # 可选。并行时最大并发数
      results_variable: string        # 可选。聚合结果存入的变量名
      # 不能与步骤级 parallel、retry、repeat_until、manual_approval、timeout_seconds 组合
    aggregate:                        # 可选。步骤执行前先聚合数据源
      sources: [string]               # 必需。聚合数据源列表（至少 2 个，支持模板变量）
      strategy: string                # 可选，默认 "concat"。聚合策略：concat|json_array|numbered_list|pick_longest|pick_shortest
      separator: string               # 可选，默认 "\n\n---\n\n"。拼接分隔符（仅 concat 策略）
      output_variable: string         # 必需。聚合结果存入的变量名

directory_isolation:                  # 可选。目录隔离配置
  enabled: boolean                    # 默认 true。是否启用目录隔离
  base_dir: string                    # 默认 ".workflow-runs"。隔离目录根路径
  working_dir_pattern: string         # 默认 "{base_dir}/{execution_id}/{step_name}/workspace"
  output_dir_pattern: string          # 默认 "{base_dir}/{execution_id}/output"
  persistence_dir_pattern: string     # 默认 "{base_dir}/{execution_id}/.snapshots"
  shared_cache_dir: string            # 默认 ".prompt_cache"。全局共享 prompt 缓存
  shared_history_dir: string          # 默认 ".agent_history"。全局共享历史消息
  shared_skills_dir: string           # 默认 "./skills"。全局共享技能目录
  cleanup_on_completion: boolean      # 默认 false。执行完成后是否自动清理
  cleanup_after_hours: integer        # 默认 72。自动清理超过指定小时的旧执行目录（0 = 不清理）

concurrency:                          # 可选。并发执行配置
  enabled: boolean                    # 默认 false。是否启用 DAG 自动并发执行
  max_concurrency: integer            # 默认 0（不限制）。同一拓扑层内的最大并发步骤数

knowledge_base:                       # 可选。工作流级共享知识库配置（v2.1 新增）
  enabled: boolean                    # 默认 true。是否启用共享知识库
  storage_dir: string                 # 可选。知识库存储目录（默认自动基于 executionId 隔离）
  embedding_model: string             # 默认 "text-embedding-3-small"。嵌入模型名称
  embedding_provider: string          # 默认 "openai"。嵌入提供商：openai | ollama
  auto_index_outputs: boolean         # 默认 true。自动将步骤输出索引到知识库
  auto_inject_rag_tools: boolean      # 默认 true。自动为所有 Agent 注入 rag_tools
  chunk_size: integer                 # 默认 1000。文本分块大小（字符数）
  chunk_overlap: integer              # 默认 200。分块重叠大小（字符数）
  max_indexed_documents: integer      # 默认 0（不限制）。最大索引文档数量
  max_total_chunks: integer           # 默认 0（不限制）。最大总分块数量
  source_files:                       # 可选。预加载的参考文档列表
    - path: string                    # 必需。文件路径（相对或绝对）
      tags: string                    # 可选。标签（逗号分隔）

error_handling:                       # 可选。全局错误处理
  max_retries: integer                # 默认 3
  retry_delay: string                 # 默认 "5s"
  continue_on_error: boolean          # 默认 false
  on_error: []                        # 全局错误转移动作列表

timeout:                              # 可选。全局超时配置
  total: string                       # 总超时，默认 "3600s"
  per_step: string                    # 每步默认超时，默认 "600s"

tags: [string]                        # 可选。工作流标签
```

#### 模板变量语法

| 语法 | 说明 | 示例 |
|------|------|------|
| `{{step_name}}` | 引用指定步骤的输出文本 | `{{write_draft}}` |
| `{{steps.step_name.output}}` | 显式引用步骤输出 | `{{steps.write_draft.output}}` |
| `{{steps.step_name:evaluate.output}}` | 引用 `repeat_until` 的评估输出 | `{{steps.refine:evaluate.output}}` |
| `{{var:name}}` | 引用 `variables` 中的全局变量 | `{{var:topic}}` |
| `{{name}}` | 也可以直接引用变量 | `{{topic}}` |

#### 条件表达式语法

格式：`variable operator value`（空格分隔，三部分）。

重要限制：

- 不支持 `{{var:x}} == y`
- 不支持 `&&`、`||`
- 不支持括号
- 只从工作流上下文变量中取值

| 运算符        | 说明           | 示例                          |
|------------|--------------|-----------------------------|
| `==`       | 字符串相等        | `status == 'approved'`      |
| `!=`       | 字符串不等        | `status != 'rejected'`      |
| `>`        | 数值大于         | `score > 80`                |
| `<`        | 数值小于         | `score < 50`                |
| `>=`       | 数值大于等于       | `score >= 60`               |
| `<=`       | 数值小于等于       | `score <= 100`              |
| `contains` | 包含子串（不区分大小写） | `output contains 'success'` |

值支持 `'单引号'` 和 `"双引号"` 包裹。变量从 `WorkflowExecutionContext.variables` 中读取。

#### Agent 参数解析优先级

`overrides` > 预设默认值 > 旧模式字段（legacy fields）

如果 CLI 在 workflow 模式下同时提供了 `-a/-c`，这些配置会先作为运行时基础参数加载；workflow 内 `agents.<name>` 解析出的同名字段会覆盖这些基础参数。

### WorkflowParser

用于解析和验证工作流 YAML 定义。

**方法:**

```kotlin
object WorkflowParser {
    fun parseYaml(yamlContent: String): WorkflowDefinition
    fun parseFile(filePath: String): WorkflowDefinition
    fun validateWorkflow(workflow: WorkflowDefinition)
    fun validateAgentPresets(workflow: WorkflowDefinition)
    fun validateAgentReferences(workflow: WorkflowDefinition)
    fun validateStepDependencies(workflow: WorkflowDefinition)
    fun detectCircularDependencies(workflow: WorkflowDefinition)
    fun validateTransitionReferences(workflow: WorkflowDefinition)
    fun validateParallelExecution(workflow: WorkflowDefinition)
    fun validateConditions(workflow: WorkflowDefinition)
    fun validateRepeatUntil(workflow: WorkflowDefinition)   // v2.0 新增
    fun validateAgentBased(workflow: WorkflowDefinition)     // v2.0 新增
    fun validateKnowledgeBase(workflow: WorkflowDefinition)  // v2.1 新增
    fun validateCodeSteps(workflow: WorkflowDefinition)      // v3.0 新增
    fun validateClassifier(workflow: WorkflowDefinition)     // v3.0 新增
    fun validateStateMachines(workflow: WorkflowDefinition)  // v3.0 新增
    fun validateExtract(workflow: WorkflowDefinition)        // v3.0 新增
    fun validateIterateOver(workflow: WorkflowDefinition)    // v3.0 新增
    fun validateAggregate(workflow: WorkflowDefinition)      // v3.0 新增
    fun buildDependencyGraph(workflow: WorkflowDefinition): Map<String, List<String>>
    fun getTopologicalOrder(workflow: WorkflowDefinition): List<WorkflowStep>
    fun getWorkflowSummary(workflow: WorkflowDefinition): String
    fun toYaml(workflow: WorkflowDefinition): String
    fun saveToFile(workflow: WorkflowDefinition, filePath: String)
}
```

### WorkflowExecutor

工作流执行引擎，支持 DAG 执行、并行任务、错误处理、`repeat_until`、`agent_based`、`code`、`classifier`、`state_machine`、`extract`、`iterate_over`、`aggregate` 等。

**构造函数:**

```kotlin
class WorkflowExecutor(
    private val httpAccess: HttpAccess,
    private val baseParameters: List<ConfigurationParameter>,
    private val enableMonitoring: Boolean = true,
    private val approvalHandler: ApprovalHandler? = null
)
```

**主要方法:**

```kotlin
suspend fun execute(
    workflow: WorkflowDefinition,
    initialInput: Map<String, Any> = emptyMap()
): WorkflowExecutionResult

fun getPendingApprovals(): List<ApprovalRequest>
fun approveStep(approvalId: String): Boolean
fun rejectStep(approvalId: String): Boolean
fun clearAgentCache()
```

说明：

- 如果传入 `approvalHandler`，执行器会同步调用它获得审批结果。
- 如果未传入 `approvalHandler`，`execute()` 会在审批步骤挂起，直到调用 `approveStep()` / `rejectStep()`，或超时。

### WorkflowMonitor

实时监控工作流执行状态和性能指标。

**主要方法:**

```kotlin
object WorkflowMonitor {
    fun startExecution(executionId: String, workflowName: String, totalSteps: Int): WorkflowMetrics
    fun startStep(executionId: String, stepName: String)
    fun addEvent(executionId: String, stepName: String, event: AgentEvent)
    fun completeStep(executionId: String, stepName: String, success: Boolean, error: String? = null)
    fun completeExecution(executionId: String, success: Boolean)

    fun getMetrics(executionId: String): WorkflowMetrics?
    fun getActiveExecutions(): List<WorkflowMetrics>
    fun getCompletedExecutions(limit: Int = 100): List<WorkflowMetrics>
    fun getWorkflowStats(workflowName: String): WorkflowStats
    fun generateReport(executionId: String): String
}
```

### WorkflowVersionControl

工作流版本管理和回滚支持。

**构造函数:**

```kotlin
class WorkflowVersionControl(
    private val versionsDir: String = "workflows/versions"
)
```

**主要方法:**

```kotlin
fun saveVersion(
    workflow: WorkflowDefinition,
    workflowPath: String,
    description: String? = null,
    createdBy: String? = null
): VersionedWorkflow

fun getVersions(workflowName: String): List<VersionedWorkflow>
fun getVersion(workflowName: String, version: String): VersionedWorkflow?

fun rollback(workflowName: String, targetVersion: String, targetPath: String): Boolean

fun compareVersions(
    workflowName: String,
    version1: String,
    version2: String
): VersionComparison

fun pruneVersions(workflowName: String, keepCount: Int = 10)
```

### OrchestratorTools（v2.0 新增）

`agent_based` 步骤中 Orchestrator Agent 使用的工具集。由 `WorkflowExecutor` 自动创建和注入，无需手动实例化。

**构造函数:**

```kotlin
class OrchestratorTools(
    private val workerAgentFactory: suspend (agentName: String, stepLabel: String) -> AIAgent<String, String>,
    private val participantDescriptions: Map<String, String>,
    private val context: WorkflowExecutionContext,
    private val maxSteps: Int = 20,
    private val onMonitorEvent: MonitoringEventCallback? = null
) : ToolSet
```

**工具方法（通过 @Tool 注解暴露给 Orchestrator Agent）:**

```kotlin
// 委派任务给指定 Worker Agent，等待结果
suspend fun delegateTask(agentName: String, task: String): String

// 并行委派多个任务（agentNames 和 tasks 为逗号分隔字符串）
suspend fun delegateParallel(agentNames: String, tasks: String): String

// 查询可用 Worker Agent 列表、能力描述及剩余步数
fun getParticipantInfo(): String

// 设置/读取工作流级共享变量
fun setVariable(key: String, value: String): String
fun getVariable(key: String): String

// 标记编排完成，summary 作为步骤输出
fun complete(summary: String): String
```

**状态属性:**

```kotlin
val completed: Boolean       // 是否已调用 complete()
val completionSummary: String? // complete() 传入的 summary
fun getDelegationLog(): List<DelegationRecord> // 委派历史记录
```

**DelegationRecord 数据类:**

```kotlin
data class DelegationRecord(
    val step: Int,             // 步数序号
    val agentName: String,     // Worker Agent 名称
    val task: String,          // 任务描述（截断至 200 字符）
    val success: Boolean,      // 是否成功
    val outputPreview: String?, // 输出预览（截断至 200 字符）
    val error: String?         // 错误信息（如有）
)
```

**异常:**

```kotlin
// 步数预算耗尽时抛出
class OrchestratorBudgetExceededException(message: String) : RuntimeException
```

**监控事件类型:**

| 事件 type | 触发时机 |
|-----------|---------|
| `orchestrator_delegate` | 开始委派任务 |
| `orchestrator_delegate_completed` | Worker Agent 完成任务 |
| `orchestrator_delegate_failed` | Worker Agent 执行失败 |
| `orchestrator_parallel_start` | 开始并行委派 |
| `orchestrator_parallel_completed` | 并行委派全部完成 |
| `orchestrator_completed` | 调用 complete() 结束编排 |

### StateMachineEngine

事件驱动的状态机执行内核。当前由 `WorkflowExecutor` 用于 `state_machine` DAG 节点内部的状态迁移、守卫条件和动作执行，也可以单独用于测试或局部状态流。

**构造函数:**

```kotlin
class StateMachineEngine(
    private val config: StateMachineConfig,
    private val context: WorkflowExecutionContext
)
```

**主要方法:**

```kotlin
suspend fun triggerEvent(event: String): StateTransitionResult
fun isFinalState(): Boolean
fun getCurrentState(): String
fun getHistory(): List<StateTransitionRecord>
fun reset()
```

### WorkflowTools

提供给 Agent 使用的工作流工具集。

**工具方法 (通过 @Tool 注解暴露给 Agent):**

```kotlin
@Tool
suspend fun executeWorkflow(
    workflowPath: String,
    inputs: Map<String, String> = emptyMap()
): String

@Tool
suspend fun validateWorkflow(workflowPath: String): String

@Tool
suspend fun describeWorkflow(workflowPath: String): String

@Tool
suspend fun visualizeWorkflow(workflowPath: String): String

@Tool
suspend fun listWorkflowTemplates(): String

@Tool
suspend fun createWorkflowFromTemplate(
    templateName: String,
    outputPath: String,
    variables: Map<String, String> = emptyMap()
): String

// Phase 2 新增工具

@Tool
suspend fun getWorkflowMetrics(executionId: String): String

@Tool
suspend fun generateExecutionReport(executionId: String): String

@Tool
suspend fun getWorkflowStats(workflowName: String): String

@Tool
suspend fun saveWorkflowVersion(
    workflowPath: String,
    description: String? = null,
    createdBy: String? = null
): String

@Tool
suspend fun listWorkflowVersions(workflowName: String): String

@Tool
suspend fun rollbackWorkflow(
    workflowName: String,
    targetVersion: String,
    targetPath: String
): String

@Tool
suspend fun compareWorkflowVersions(
    workflowName: String,
    version1: String,
    version2: String
): String
```

### 数据模型

**WorkflowDefinition:**

```kotlin
@Serializable
data class WorkflowDefinition(
    val name: String,
    val version: String = "1.0.0",
    val description: String? = null,
    val agents: Map<String, AgentDefinition>,
    val workflow: List<WorkflowStep>,
    val errorHandling: ErrorHandlingConfig? = null,
    val timeout: TimeoutConfig? = null,
    val variables: Map<String, String> = emptyMap(),
    val directoryIsolation: DirectoryIsolationConfig = DirectoryIsolationConfig(),
    val concurrency: ConcurrencyConfig = ConcurrencyConfig(),
    val knowledgeBase: KnowledgeBaseConfig? = null,
    val tags: List<String> = emptyList(),
    val category: String? = null,
    val globalAgent: WorkflowGlobalAgentConfig? = null
)
```

**WorkflowStep:**

```kotlin
@Serializable
data class WorkflowStep(
    val step: String,
    val agent: String? = null,
    val input: String? = null,
    val groupChat: GroupChatConfig? = null,
    val dependsOn: List<String> = emptyList(),
    val condition: String? = null,
    val onSuccess: List<TransitionAction> = emptyList(),
    val onFailure: List<TransitionAction> = emptyList(),
    val parallel: ParallelExecution? = null,
    val manualApproval: ManualApprovalConfig? = null,
    val priority: Int = 0,
    val timeoutSeconds: Int? = null,
    val state: String? = null,         // legacy reserved field
    val retry: RetryConfig? = null,
    val timeout: String? = null,
    val repeatUntil: RepeatUntilConfig? = null,
    val agentBased: AgentBasedConfig? = null,
    val code: CodeStepConfig? = null,
    val classifier: ClassifierConfig? = null,
    val stateMachine: StateMachineConfig? = null,
    val extract: List<ExtractConfig>? = null,
    val iterateOver: IterateOverConfig? = null,
    val aggregate: AggregateConfig? = null
)
```

说明：

- 步骤必须且只能指定一种模式：`agent + input`、`group_chat`、`agent_based`、`code`、`classifier`、`state_machine`
- `parallel` 只适用于单 Agent 步骤
- `iterate_over` 只适用于单 Agent 步骤或 `code` 步骤，且不能与步骤级 `parallel`、`retry`、`repeat_until`、`manual_approval`、`timeout_seconds` 组合
- `repeat_until` 主要用于单 Agent / `group_chat`，且不能与 `code`、`agent_based` 组合
- `state_machine` 外层步骤不能与 `parallel` 组合

**StateMachineConfig / StateDefinition / StateStepConfig:**

```kotlin
@Serializable
data class StateMachineConfig(
    val states: Map<String, StateDefinition>,
    val initialState: String,
    val finalStates: List<String> = emptyList(),
    val maxTransitions: Int = 64
)

@Serializable
data class StateDefinition(
    val name: String,
    val stepConfig: StateStepConfig? = null,
    val onEnter: List<TransitionAction> = emptyList(),
    val onExit: List<TransitionAction> = emptyList(),
    val autoEvent: String = "enter",
    val successEvent: String = "complete",
    val failureEvent: String = "error",
    val transitions: List<StateTransition> = emptyList()
)

@Serializable
data class StateStepConfig(
    val agent: String? = null,
    val input: String? = null,
    val groupChat: GroupChatConfig? = null,
    val agentBased: AgentBasedConfig? = null,
    val code: CodeStepConfig? = null,
    val classifier: ClassifierConfig? = null,
    val extract: List<ExtractConfig>? = null
)
```

**WorkflowMetrics:**

```kotlin
data class WorkflowMetrics(
    val executionId: String,
    val workflowName: String,
    val startTime: Long,
    var endTime: Long? = null,
    var status: ExecutionStatus = ExecutionStatus.RUNNING,
    val stepMetrics: MutableMap<String, StepMetrics> = mutableMapOf(),
    var totalSteps: Int = 0,
    var completedSteps: Int = 0,
    var failedSteps: Int = 0,
    var skippedSteps: Int = 0
) {
    fun getDuration(): Long
    fun getSuccessRate(): Double
    fun getInputTokens(): Long
    fun getOutputTokens(): Long
    fun getTotalTokens(): Long
}
```

**AgentEvent:**

```kotlin
data class AgentEvent(
    val timestamp: Long = System.currentTimeMillis(),
    val type: String,
    val category: String,
    val subCategory: String? = null,
    val summary: String,
    val detail: String? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null
)
```

`subCategory` 用于更细粒度的监控展示，当前常见值：

- `reasoning`
- `skill`
- `reply`
- `sub_agent`
- `token`

**StepMetrics:**

```kotlin
data class StepMetrics(
    val stepName: String,
    val startTime: Long,
    var endTime: Long? = null,
    var status: ExecutionStatus = ExecutionStatus.RUNNING,
    var retryCount: Int = 0,
    var error: String? = null,
    var output: String? = null,
    var agentName: String? = null,
    val events: MutableList<AgentEvent> = mutableListOf()
) {
    fun getDuration(): Long
    fun getInputTokens(): Long
    fun getOutputTokens(): Long
    fun getTotalTokens(): Long
}
```

**ExecutionStatus:**

```kotlin
enum class ExecutionStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    TIMEOUT,
    CANCELLED,
    AWAITING_APPROVAL  // Phase 2
}
```

**WorkflowResumeState:**

```kotlin
data class WorkflowResumeState(
    val preservedStepResults: Map<String, StepExecutionResult> = emptyMap(),
    val skippedSteps: Set<String> = emptySet(),
    val activatedTransitionSteps: Set<String> = emptySet(),
    val transitionActivationSources: Map<String, Set<String>> = emptyMap()
)
```

说明：

- `preservedStepResults`：历史执行中已完成/失败的步骤结果，恢复时直接复用
- `skippedSteps`：需要跳过的步骤名集合
- `activatedTransitionSteps`：已激活的转移目标步骤集合
- `transitionActivationSources`：记录哪些步骤激活了哪些转移

**AgentDefinition `session_id_strategy`:**

AgentDefinition 新增 `session_id_strategy` 字段，控制 Agent 会话隔离级别：

| 值 | 生成规则 | 说明 |
|---|---|---|
| `auto`（默认） | `{executionId}:{agentName}:{stepName}` | 每步完全隔离 |
| `per_execution` | `{executionId}:{agentName}` | 同一次执行内共享 |
| `per_agent` | `{workflowName}:{agentName}` | 跨执行共享，支持长期记忆 |
| `fixed` | 使用 `sessionId` 字段的值 | 固定会话标识 |

**AgentDefinition `history_compression`:**

支持可选的 `HistoryCompressionConfig` 用于压缩消息历史以减少 token 消耗。

**CodeStepConfig:**

```kotlin
@Serializable
data class CodeStepConfig(
    val language: String,           // python, javascript, typescript, bash, ruby, lua, cli
    val script: String? = null,     // 内联脚本
    val scriptFile: String? = null, // 外部脚本文件路径（与 script 二选一）
    val timeout: Int = 30,          // 超时秒数
    val workingDirectory: String? = null
)
```

输入通过环境变量注入：`STEP_INPUTS`、`WF_VAR_xxx`、`STEP_OUTPUT_xxx`。输出为脚本的 `stdout`。

**IterateOverConfig:**

```kotlin
@Serializable
data class IterateOverConfig(
    val source: String,
    val delimiter: String = "\n",       // 支持 "json_array"
    val itemVariable: String = "current_item",
    val indexVariable: String = "current_index",
    val maxItems: Int = 0,              // 0 = 不限制
    val parallel: Boolean = false,
    val maxParallel: Int = 0,           // 并行时最大并发数
    val resultsVariable: String = "iteration_results"
)
```

**AggregateConfig:**

```kotlin
@Serializable
data class AggregateConfig(
    val sources: List<String>,          // ≥2 个模板变量来源
    val strategy: String = "concat",    // concat, json_array, numbered_list, pick_longest, pick_shortest
    val separator: String = "\n\n---\n\n",
    val outputVariable: String = "aggregated_result"
)
```

**DirectoryIsolationConfig:**

```kotlin
@Serializable
data class DirectoryIsolationConfig(
    val enabled: Boolean = true,
    val baseDir: String = ".workflow-runs",
    val workingDirPattern: String? = null,
    val outputDirPattern: String? = null,
    val persistenceDirPattern: String? = null,
    val sharedCacheDir: String? = null,
    val sharedHistoryDir: String? = null,
    val sharedSkillsDir: String? = null,
    val cleanupOnCompletion: Boolean = false,
    val cleanupAfterHours: Int = 72
)
```

模式占位符：`{base_dir}`、`{execution_id}`、`{step_name}`、`{agent_name}`、`{workflow_name}`

**MultimediaToolConfig:**

```kotlin
@Serializable
data class MultimediaToolConfig(
    val apiKey: String? = null,
    val baseUrl: String? = null,
    val imageModel: String? = null,
    val audioModel: String? = null
)
```

---

## 批量执行 (WorkflowBatchExecutor)

`WorkflowBatchExecutor` 管理多个 workflow 的并发执行。

```kotlin
class WorkflowBatchExecutor(
    val maxConcurrency: Int = 0  // 0 = 不限制
)
```

批量执行结果包含：

- `totalCount`、`successCount`、`failureCount`
- `allSucceeded`
- 每个 workflow 的 `startedAt`、`completedAt`、`duration`
- 异常捕获与错误上报
- 批量总耗时

---

## 使用示例

### 基础工作流执行

```kotlin
val httpAccess = HttpAccessClient()
val parameters = listOf(/* your parameters */)
val executor = WorkflowExecutor(httpAccess, parameters)

val workflow = WorkflowParser.parseFile("workflows/my-workflow.yaml")
val result = executor.execute(workflow, mapOf("input" to "value"))

println("Success: ${result.success}")
println("Duration: ${result.duration}ms")
```

### 实时监控

```kotlin
val executionId = "abc-123"
val metrics = WorkflowMonitor.getMetrics(executionId)

println("Status: ${metrics?.status}")
println("Progress: ${metrics?.completedSteps}/${metrics?.totalSteps}")
println("Success Rate: ${metrics?.getSuccessRate()}")
println("Input Tokens: ${metrics?.getInputTokens()}")
println("Output Tokens: ${metrics?.getOutputTokens()}")
println("Total Tokens: ${metrics?.getTotalTokens()}")
```

### 版本控制

```kotlin
val versionControl = WorkflowVersionControl()
val workflow = WorkflowParser.parseFile("my-workflow.yaml")

// 保存版本
versionControl.saveVersion(
    workflow,
    "my-workflow.yaml",
    description = "Added error handling",
    createdBy = "john.doe"
)

// 回滚
versionControl.rollback("my-workflow", "1.0.0", "my-workflow.yaml")
```

### 状态机节点工作流

```kotlin
val workflow = WorkflowParser.parseFile("workflows/templates/test-workflow-state-machine.yaml")
val result = executor.execute(workflow, mapOf("request" to "请审核并修订这份草稿"))

println("Success: ${result.success}")
println("Final state: ${result.variables["review_flow_final_state"]}")
println("Step output: ${result.stepOutputs["review_flow"]}")
```

---

## v1.1.0 Bug 修复说明

### `AgentExitException` 替代 `exitProcess()`

v1.1.0 引入 `AgentExitException` 作为 CLI 退出码的内部机制，替代此前在协程作用域内直接调用 `exitProcess()` 的做法。直接调用 `exitProcess()` 会绕过协程的结构化并发清理流程，可能导致资源泄漏和未完成的挂起操作。`AgentExitException` 携带退出码（`exitCode: Int`），由最外层调用者捕获并转换为进程退出码，确保所有协程和资源在退出前正确清理。

---
