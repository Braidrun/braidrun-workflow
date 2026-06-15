# braidrun-agent MCP Server Guide

> **关于命令行示例**：文中 `braidrun-agent-cli` / `:braidrun-agent-cli:run` 形式的
> 命令来自本库早期配套的内置 CLI，当前开源版本只包含库本身、暂不附带 CLI 发行版。
> 这些示例中的参数与行为语义仍然准确，可作为理解功能的参考；以编程方式运行
> agent / workflow 请见 [GETTING_STARTED.md](GETTING_STARTED.md)。


## 概览

`braidrun-agent` 现在既可以作为 MCP client 连接外部 `mcp_servers`，也可以直接作为 **stdio MCP server** 启动，把自身内置工具分组暴露给 Claude Desktop、Codex、Cherry Studio 或任何兼容 MCP 的客户端。

这个能力适合两类场景：

- 想把 `braidrun-agent` 的本地工具按组复用到其他 MCP 客户端
- 想把多个常用工具组组合成一个统一的本地 MCP server

当前实现特性：

- 传输方式：`stdio`
- 启动粒度：支持单个工具分组，也支持一次启动多个分组
- 默认行为：如果不指定任何 `--mcp-tool-group`，会加载全部受支持分组
- 输出安全：server 启动后，普通运行日志会被重定向到 `stderr`，避免污染 MCP 协议使用的 `stdout`

## CLI 用法

### 1. 查看可用工具分组

```bash
braidrun-agent --list-mcp-tool-groups
```

### 2. 启动单个工具分组

```bash
braidrun-agent --mcp-server --mcp-tool-group shell
```

### 3. 启动多个工具分组

可以重复传参：

```bash
braidrun-agent --mcp-server \
  --mcp-tool-group shell \
  --mcp-tool-group web \
  --mcp-tool-group git
```

也可以逗号分隔：

```bash
braidrun-agent --mcp-server --mcp-tool-group shell,web,git
```

### 4. 启动全部受支持分组

```bash
braidrun-agent --mcp-server
```

或者显式写成：

```bash
braidrun-agent --mcp-server --mcp-tool-group all
```

### 5. 带配置文件和环境启动

很多工具依赖配置参数，例如：

- `llm_provider_keys`
- `working_dir`
- `output_dir`
- 邮件、浏览器、RAG、数据库相关配置

因此更推荐带配置文件启动：

```bash
braidrun-agent --mcp-server \
  --mcp-tool-group shell \
  --mcp-tool-group file_system \
  --mcp-tool-group git \
  -c my-agent.yaml \
  --env braidrun
```

`-c/--configuration`、`--env`、`--proxy` 在 MCP server 模式下仍然有效；它们会作为工具运行时的基础配置继续传递。

## 支持的工具分组

下表中的 `名称` 就是 `--mcp-tool-group` 要使用的值。

| 名称 | 说明 |
| --- | --- |
| `sub_agent` | 子 Agent 委派与协作工具 |
| `file_system` | 基础文件读写、目录遍历与编辑工具 |
| `shell` | Shell 命令执行工具 |
| `web` | 网页抓取、搜索与 HTTP 工具 |
| `browser` | 基于 Playwright 的浏览器自动化工具 |
| `pdf` | PDF 读取、编辑、合并与高级处理工具 |
| `iwork` | Apple Pages/Numbers/Keynote 工具 |
| `office` | Word/Excel/PowerPoint 及增强文档工具 |
| `word` | Word 文档创建、编辑与增强工具 |
| `excel` | Excel 工作簿创建、编辑与增强工具 |
| `powerpoint` | PowerPoint 演示文稿创建、编辑与增强工具 |
| `csv` | CSV 读写与转换工具 |
| `multimedia` | 多媒体生成工具 |
| `apple_app_info` | Apple App Store 信息查询工具 |
| `skill_tools` | 技能检索、安装与管理工具 |
| `im` | 即时通讯集成工具 |
| `workflow` | 工作流执行与管理工具 |
| `file_management` | 文件复制、移动、压缩、搜索等管理工具 |
| `code_execution` | 代码执行工具 |
| `database` | SQLite/JDBC 数据库工具 |
| `image_processing` | 图片处理工具 |
| `email` | 邮件收发工具 |
| `knowledge_memory` | 本地知识记忆工具 |
| `rag_tools` | RAG 文档索引与检索工具 |
| `data_transform` | 数据格式转换工具 |
| `calendar` | 日期、时区与日历工具 |
| `git` | Git 版本控制工具 |
| `ocr` | OCR 文字识别工具 |

## 当前不作为 MCP tool group 暴露的能力

以下能力没有放入 `--mcp-tool-group` 列表：

- `interactive`
- `user_interaction`
- `exit`

原因：

- `interactive` 和 `user_interaction` 需要直接占用终端输入，而 stdio MCP server 本身已经占用了标准输入输出通道
- `exit` 更适合作为 agent 内部控制工具，而不是独立对外工具

## 构建与可执行文件路径

如果你想把它配置给外部 MCP 客户端，通常先构建安装分发目录：

```bash
./gradlew :braidrun-agent-cli:installDist
```

生成的可执行文件通常位于：

```bash
./braidrun-agent-cli/build/install/braidrun-agent/bin/braidrun-agent
```

## MCP 客户端配置示例

### 示例 1：暴露 `shell + file_system + git`

```json
{
  "mcpServers": {
    "braidrun-agent-devtools": {
      "command": "/absolute/path/to/braidrun-agent-cli/build/install/braidrun-agent/bin/braidrun-agent",
      "args": [
        "--mcp-server",
        "--mcp-tool-group",
        "shell",
        "--mcp-tool-group",
        "file_system",
        "--mcp-tool-group",
        "git",
        "-c",
        "/absolute/path/to/my-agent.yaml",
        "--env",
        "braidrun"
      ]
    }
  }
}
```

### 示例 2：暴露全部工具分组

```json
{
  "mcpServers": {
    "braidrun-agent-all": {
      "command": "/absolute/path/to/braidrun-agent-cli/build/install/braidrun-agent/bin/braidrun-agent",
      "args": [
        "--mcp-server",
        "-c",
        "/absolute/path/to/my-agent.yaml"
      ]
    }
  }
}
```

## 与 agent 模式的区别

### 普通 agent 模式

```bash
braidrun-agent -a coder -c my-agent.yaml
```

- 直接运行一个 AI agent
- 由 LLM 决定何时调用工具
- 最终输出是 agent 的回答

### MCP server 模式

```bash
braidrun-agent --mcp-server --mcp-tool-group shell
```

- 不直接运行 agent 对话
- 只启动一个 MCP server
- 外部 MCP 客户端来发现并调用这些工具

## 参数与行为说明

### `--mcp-server`

开启 MCP stdio server 模式。开启后不会进入普通 agent 模式，也不会执行 workflow。

### `--mcp-tool-group`

指定要暴露的工具分组。支持：

- 重复多次
- 逗号分隔
- 省略时默认全部
- `all` 作为全部分组别名

### `-c/--configuration`

可选。用于给工具提供基础配置。建议在以下场景使用：

- 需要 LLM Key 的工具，如 `sub_agent`、`multimedia`、部分 `workflow`/`rag_tools`
- 需要目录、邮箱、数据库、浏览器或其他运行时参数的工具

### `--env`

会继续写入工具运行参数中，供需要环境上下文的工具使用。

## 注意事项

### 1. stdio 模式下不要向 `stdout` 输出业务日志

MCP 协议本身占用 `stdin/stdout`。`braidrun-agent` 的 MCP server 启动后会把普通输出重定向到 `stderr`，但如果你的外部包装脚本又额外向 `stdout` 写内容，仍然会破坏协议。

> Phase 8（2026-04-29）补丁：`AgentModels.debugLlmConfig`（仅在 `BRAIDRUN_DEBUG_LLM_PARAMS=true` 时启用）现已统一改走 logger 而非 `println`，避免在 MCP stdio 模式下污染 JSON-RPC 通道。新接入的代码也请遵循同样规则——所有诊断输出走 `KotlinLogging.logger { }`，业务返回走工具协议。

### 2. 某些工具需要额外依赖

例如：

- `browser` 需要 Playwright 运行环境
- `ocr` 需要 Tesseract
- `email` 需要 SMTP/IMAP 配置
- `database` 需要对应 JDBC 驱动和连接参数

server 可以启动成功，不代表所有工具在没有配置时都能成功执行。

### 3. `office` 已包含大量文档工具

如果同时启用了 `office` 和 `csv`，不会额外带来问题；但从职责上通常只需要按需求选择即可。

### 4. `skill_tools` 在 MCP server 模式下不会被隐式自动加入

普通 agent 模式里，`skill_tools` 默认会被自动启用；MCP server 模式为了保证“只暴露你显式选择的分组”，不会自动附带它。如果需要，必须显式写：

```bash
braidrun-agent --mcp-server --mcp-tool-group skill_tools
```

## 排错建议

### 启动时报 unknown MCP tool group

先运行：

```bash
braidrun-agent --list-mcp-tool-groups
```

确认分组名是否拼写正确。

### 客户端连接后工具列表为空

优先检查：

- 是否真的传了 `--mcp-server`
- 启动参数里是否传了错误的 `--mcp-tool-group`
- 外层脚本是否向 `stdout` 写了其他内容

### 工具调用时报配置缺失

给 MCP server 增加配置文件：

```bash
braidrun-agent --mcp-server -c my-agent.yaml --mcp-tool-group shell
```

把需要的密钥、目录和业务参数放进 `my-agent.yaml`。
