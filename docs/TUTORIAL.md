# Braidrun Agent 详细使用教程 (v1.1.0)

> **关于命令行示例**：文中 `braidrun-agent-cli` / `:braidrun-agent-cli:run` 形式的
> 命令来自本库早期配套的内置 CLI，当前开源版本只包含库本身、暂不附带 CLI 发行版。
> 这些示例中的参数与行为语义仍然准确，可作为理解功能的参考；以编程方式运行
> agent / workflow 请见 [GETTING_STARTED.md](GETTING_STARTED.md)。


本教程将引导你从零开始，逐步掌握如何使用 `braidrun-agent` 框架构建强大的 AI 智能体。

> **v1.1.0 新特性**: 新增 CLI quiet mode、output to file、timeout、stdin piping、dry-run 等命令行功能，以及 `compact_env` token 优化参数。

## 目录

1. [环境准备](#1-环境准备)
2. [Hello World - 第一个 Agent](#2-hello-world---第一个-agent)
3. [配置 LLM 和工具](#3-配置-llm-和-工具)
4. [使用技能系统](#4-使用技能系统)
5. [深入钩子系统](#5-深入钩子系统)
6. [实战案例](#6-实战案例)
7. [工作流编排](#7-工作流编排)
8. [进阶主题](#8-进阶主题)

---

## 1. 环境准备

### 1.1 系统要求

- **Java**: JDK 21 或更高版本
- **Kotlin**: 1.9+ (通过 Gradle 自动管理)
- **操作系统**: macOS, Linux, Windows
- **可选依赖**:
    - Python 3.8+ (用于钩子脚本)
    - Node.js 16+ (用于钩子脚本)
    - Playwright (用于浏览器自动化)

### 1.2 项目配置

在你的 `build.gradle.kts` 中添加依赖：

```kotlin
dependencies {
    // 核心库
    implementation("com.fartech.braidrun:braidrun-workflow:1.0.0-SNAPSHOT")

    // 协程支持
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
```

确保你的项目使用 Java 21：

```kotlin
kotlin {
    jvmToolchain(21)
}
```

### 1.3 获取 API 密钥

你需要至少一个 LLM 提供商的 API 密钥：

- **OpenRouter** (推荐): https://openrouter.ai/keys
- **OpenAI**: https://platform.openai.com/api-keys
- **Anthropic**: https://console.anthropic.com/
- **DeepSeek**: https://platform.deepseek.com/

---

## 2. Hello World - 第一个 Agent

### 2.1 使用命令行 (最简单)

1. 创建配置文件 `hello-agent.yaml`:

```yaml
parameters:
  - language: Chinese
  - prompt: "你好！介绍一下你自己。"
  - llm_config:
      models:
        - model: anthropic/claude-3-5-sonnet
          provider: open_router
      temperature: 1.0
  - llm_provider_keys:
      openrouter: "sk-or-v1-your-key-here"
  - strategy: just_work_parallel
  - tool_set:
      - exit
```

2. 运行 Agent:

```bash
braidrun-agent -a universal -c hello-agent.yaml
```

3. Agent 将自动运行并响应配置中的 `prompt`。

补充说明：

- 只传 `-c hello-agent.yaml` 时，会把文件当作完整 Agent 配置
- 显式传 `-a universal -c hello-agent.yaml` 时，会先加载 `universal` preset，再用文件中的字段覆盖
- 如果你只想使用文件本身，不叠加 preset，省略 `-a`

### 2.2 CLI 进阶用法 (v1.1.0)

v1.1.0 新增了多个命令行选项，让 Agent 在脚本化和自动化场景中更加灵活。

#### 查看版本

```bash
./gradlew :braidrun-agent-cli:run --args='--version'
```

输出当前 braidrun-agent 版本号，用于确认部署环境。

#### Quiet 模式 (`-q`)

静默模式下 Agent 只输出最终结果，不显示中间推理过程和工具调用日志，适合在 CI/CD 流水线或脚本中使用：

```bash
./gradlew :braidrun-agent-cli:run --args='-a universal -p "task" -q'
```

#### 输出到文件 (`-o`)

将 Agent 的最终输出写入指定文件，而不是打印到终端：

```bash
./gradlew :braidrun-agent-cli:run --args='-a coder -p "Write hello world" -o result.txt'
```

可以与 `-q` 组合，实现完全静默地将结果写入文件：

```bash
./gradlew :braidrun-agent-cli:run --args='-a coder -p "Write hello world" -o result.txt -q'
```

#### 超时设置 (`--timeout`)

为 Agent 执行设置超时时间 (单位: 秒)，超时后 Agent 会被优雅终止：

```bash
./gradlew :braidrun-agent-cli:run --args='-a universal -p "complex task" --timeout 120'
```

这在防止 Agent 陷入无限循环或长时间等待的场景中非常有用。

#### 从 stdin 读取输入 (`--stdin`)

通过管道将输入传递给 Agent，适合与其他命令行工具串联使用：

```bash
echo "Analyze this text" | ./gradlew :braidrun-agent-cli:run --args='--stdin -a researcher'
```

实际应用示例 -- 分析文件内容：

```bash
{ echo "分析这些错误日志并给出修复建议"; echo; cat error.log; } | ./gradlew :braidrun-agent-cli:run --args='--stdin -a universal -o analysis.md'
```

注意：`--stdin` 与 `-p/--prompt` 互斥，不能同时传入。

#### 列出可用策略 (`--list-strategies`)

查看当前所有已注册的策略，帮助你选择合适的 `strategy` 参数：

```bash
./gradlew :braidrun-agent-cli:run --args='--list-strategies'
```

#### 列出可用工具 (`--list-tools`)

查看当前所有可用的工具集名称及简要说明：

```bash
./gradlew :braidrun-agent-cli:run --args='--list-tools'
```

#### 工作流 Dry-Run (`--dry-run`)

在不实际执行的情况下验证工作流定义，检查配置是否正确、依赖关系是否合法：

```bash
./gradlew :braidrun-agent-cli:run --args='-w workflow.yaml --dry-run'
```

dry-run 会输出工作流的执行计划（步骤顺序、Agent 分配、依赖关系图），但不会真正调用 LLM 或执行任何工具。

### 2.3 使用 Kotlin 代码

创建 `HelloAgent.kt`:

```kotlin
import com.fartech.agents.agents.universalAgent
import com.fartech.ftapp2.commonsKt.HttpAccess
import com.fartech.ftapp2.commonsKt.parameter
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val httpAccess = HttpAccess()

    val parameters = listOf(
        parameter("name", "HelloAgent"),
        parameter("language", "Chinese"),
        parameter("system_prompt", "你是一个简洁的助手，用一句话回答问题。"),
        parameter("llm_config", mapOf(
            "models" to listOf(mapOf(
                "model" to "anthropic/claude-3-5-sonnet",
                "provider" to "open_router"
            )),
            "temperature" to 1.0
        )),
        parameter("llm_provider_keys", mapOf(
            "openrouter" to "sk-or-v1-your-key-here"
        )),
        parameter("strategy", "just_work_parallel"),
        parameter("tool_set", mutableSetOf("exit"))
    )

    // 运行通用 Agent（会提示用户在终端输入）
    universalAgent(httpAccess, parameters)
}
```

运行：

```bash
./gradlew run
```

**交互示例:**

```
[系统] Agent 已启动，请输入你的问题:
> 什么是 AI?
[Agent] AI (人工智能) 是让计算机系统模拟人类智能的技术。
> exit
[系统] Agent 已退出
```

---

## 3. 配置 LLM 和工具

### 3.1 配置多个模型和回退

```yaml
- llm_config:
    models:
      # 主模型
      - model: anthropic/claude-3-5-sonnet
        provider: open_router
        maxToken: 8192
        isVision: true

      # 备用模型 1
      - model: openai/gpt-4o
        provider: open_router

      # 备用模型 2 (本地)
      - model: llama3
        provider: ollama
        baseUrl: "http://localhost:11434"

    # 最终回退
    fallback:
      model: deepseek/deepseek-chat
      provider: open_router

    temperature: 0.8

- llm_provider_keys:
    openrouter: "sk-or-v1-..."
```

**工作原理**: Agent 会按顺序尝试模型，如果遇到错误（如限流、超时）会自动切换到下一个。

### 3.2 启用工具集

```yaml
- tool_set:
    # --- 核心工具 ---
    - exit              # 优雅退出
    - shell             # 跨平台 Shell 命令执行（Windows/macOS/Linux）
    - file_system       # 文件读写 + 文件管理（复制/移动/删除/搜索/压缩/哈希）
    - web               # 网页抓取、HTTP 请求、搜索引擎（网页/新闻/图片）
    - browser           # Playwright 浏览器自动化 (需要 Playwright)
    - sub_agent         # 子 Agent 委派
    - skill_tools       # 技能搜索和加载
    - interactive       # 交互式用户输入

    # --- 文档与数据 ---
    - office            # Office 文档（Word/Excel/PPT，含增强版支持自定义字体和图片）
    - pdf               # PDF 处理（解析/合并/拆分/加密/水印）
    - csv               # CSV 文件操作
    - iwork             # Apple iWork 文档
    - ocr               # 图片文字识别 (OCR)
    - database          # 数据库查询（SQLite + JDBC）
    - data_transform    # 数据格式转换（JSON/YAML/Base64/XML 等）

    # --- 开发与执行 ---
    - code_execution    # 代码执行（Python/JavaScript）
    - git               # Git 版本控制（status/diff/commit/push 等）

    # --- 多媒体 ---
    - multimedia        # AI 图片/音频生成
    - image_processing  # 图片处理（缩放/裁剪/旋转/格式转换/压缩）

    # --- 通信 ---
    - email             # 邮件收发（SMTP/IMAP）
    - im                # 即时通讯（Telegram/钉钉/企微/飞书/Slack 等）

    # --- 效率与记忆 ---
    - calendar          # 日期时间工具
    - knowledge_memory  # 持久化知识记忆
    - rag_tools         # RAG 文档索引与检索

    # --- 工作流与业务 ---
    - workflow          # 工作流编排
    - apple_app_info    # App Store 应用信息
```

**示例: 文件分析 Agent**

```yaml
parameters:
  - prompt: "分析当前目录下的所有 .kt 文件，统计代码行数"
  - strategy: just_work_parallel
  - tool_set:
      - exit
      - shell
      - file_system
  - llm_config:
      models:
        - model: anthropic/claude-3-5-sonnet
          provider: open_router
  - llm_provider_keys:
      openrouter: "sk-or-v1-..."
```

Agent 会自动：

1. 使用 `file_system` 工具列出 `.kt` 文件
2. 使用 `shell` 工具执行 `wc -l` 统计行数
3. 汇总并输出结果

### 3.3 集成 MCP 服务器

```yaml
- mcp_servers:
    # 文件系统服务器
    filesystem:
      command: "npx"
      args: ["-y", "@modelcontextprotocol/server-filesystem", "/Users/alex/workspace"]

    # 数据库服务器
    postgres:
      command: "npx"
      args: ["-y", "@modelcontextprotocol/server-postgres", "postgresql://localhost/mydb"]

    # GitHub 集成
    github:
      command: "npx"
      args: ["-y", "@modelcontextprotocol/server-github"]
      env:
        GITHUB_TOKEN: "ghp_..."
```

---

## 4. 使用技能系统

### 4.1 技能的基本概念

技能是可复用的功能模块，相比直接调用工具更高效：

| 对比维度     | 工具调用       | 技能         |
|----------|------------|------------|
| Token 消耗 | 每次调用都传完整定义 | 只传摘要，按需加载  |
| 可复用性     | 低          | 高          |
| 版本管理     | 无          | 支持         |
| 附件支持     | 无          | 脚本、模板、数据文件 |

### 4.2 创建本地技能

**步骤 1**: 创建技能目录

```bash
mkdir -p skills/code-analyzer
```

**步骤 2**: 编写 `skills/code-analyzer/SKILL.md`

```markdown
---
name: code-analyzer
description: "分析代码库结构、统计代码行数、识别主要编程语言"
version: "1.0.0"
author: "Your Name"
tags:
  - code-analysis
  - statistics
attachments:
  - analyzer.py
---

# Code Analyzer Skill

这个技能可以分析代码库并生成详细报告。

## 使用方法

1. 确保你在项目根目录
2. 要求 Agent："使用 code-analyzer 技能分析当前项目"
3. Agent 会运行 analyzer.py 脚本并呈现结果

## 输出格式

- 总代码行数
- 按语言分类的统计
- 最大的文件
- 目录结构图
```

**步骤 3**: 创建附件 `skills/code-analyzer/analyzer.py`

```python
#!/usr/bin/env python3
import os
import pathlib
from collections import defaultdict

def analyze_code():
    stats = defaultdict(int)
    total_lines = 0

    for path in pathlib.Path('.').rglob('*'):
        if path.is_file() and not any(x in str(path) for x in ['.git', 'build', 'node_modules']):
            ext = path.suffix
            if ext in ['.py', '.kt', '.java', '.js', '.ts', '.go', '.rs']:
                try:
                    with open(path) as f:
                        lines = len(f.readlines())
                        stats[ext] += lines
                        total_lines += lines
                except:
                    pass

    print(f"总代码行数: {total_lines}")
    for ext, lines in sorted(stats.items(), key=lambda x: -x[1]):
        print(f"{ext}: {lines} 行")

if __name__ == "__main__":
    analyze_code()
```

```bash
chmod +x skills/code-analyzer/analyzer.py
```

**步骤 4**: 配置 Agent 使用技能

```yaml
parameters:
  - skills_config:
      skillsPath: "./skills"
      enabled: true
      autoDiscovery: true
  - tool_set:
      - skill_tools  # 必须启用
      - shell        # analyzer.py 需要
```

如果你想把 agent 限制为“只能发现指定 skill”，显式打开白名单模式：

```yaml
parameters:
  - skills_config:
      skillsPath: "./skills"
      skillWhitelistMode: true
      enabledSkills:
        - code-analyzer
      disabledSkills:
        - braidrun-agent-guide  # 即使是内置 skill，也可以在这里排除
```

**步骤 5**: 运行

```
[User] 使用 code-analyzer 技能分析当前项目
[Agent]
1. (发现技能) 找到 code-analyzer 技能
2. (加载技能) 读取完整内容和附件
3. (执行) 运行 python3 skills/code-analyzer/analyzer.py
4. (结果) 总代码行数: 5234
           .kt: 3456 行
           .py: 1234 行
           ...
```

### 4.3 从 ClawHub 获取技能

```yaml
- tool_set:
    - skill_tools

- clawhub_base_url: "https://clawhub.ai"
- clawhub_cache_dir: "./skills-cache"
```

**使用示例:**

```
[User] 搜索适合生成 PPT 的技能
[Agent] 正在搜索...
        找到 3 个技能:
        1. powerpoint-generator - 生成 PowerPoint 演示文稿
        2. slide-builder - 快速构建幻灯片
        3. presentation-ai - AI 驱动的演示文稿生成

        推荐使用: powerpoint-generator

[User] 安装 powerpoint-generator
[Agent] 正在下载和安装技能...
        ✓ 已安装到 ./skills-cache/powerpoint-generator/
        ✓ 技能已加载

[User] 用它生成一个关于 AI 的 PPT
[Agent] (使用技能) 生成中...
        ✓ 已生成 AI_Presentation.pptx
```

### 4.4 技能的渐进式加载

braidrun-agent 默认启用 Progressive Disclosure (渐进式披露)：

**启动时 (摘要模式)**:

```
可用技能:
- code-analyzer: 分析代码库结构、统计代码行数、识别主要编程语言
- powerpoint-generator: 生成 PowerPoint 演示文稿
- ...
```

**需要时 (完整模式)**:

```
[Agent 内部]: 用户要求使用 code-analyzer
[加载完整内容]: 读取 SKILL.md 的详细内容、附件列表、使用说明
[注入上下文]: 将完整技能内容添加到 prompt
[执行任务]
```

这样可以**节省 80% 以上的 Token 消耗**，同时保持灵活性。

---

## 5. 深入钩子系统

### 5.1 钩子的三个层级

```
优先级: 工作区钩子 > 用户全局钩子 > 技能内置钩子

技能内置:  skills/my-skill/hooks/braidrun-agent/HOOK.md
用户全局:  ~/.braidrun/hooks/braidrun-agent/my-hook/HOOK.md
工作区:    ./hooks/braidrun-agent/my-hook/HOOK.md
```

**实际应用场景:**

- **技能内置钩子**: 技能作者提供的默认行为
- **用户全局钩子**: 你个人的偏好设置（跨所有项目）
- **工作区钩子**: 特定项目的定制（覆盖前两者）

### 5.2 创建静态钩子

**场景**: 在 Agent 启动时自动注入项目编码规范

1. 创建工作区钩子目录:

```bash
mkdir -p hooks/braidrun-agent/coding-standards
```

2. 创建 `hooks/braidrun-agent/coding-standards/HOOK.md`:

```markdown
---
name: coding-standards
description: "注入项目编码规范"
emoji: "📐"
metadata:
  braidrun-agent:
    events:
      - agent:bootstrap
    virtualFilePath: "COBRAIDRUN_STANDARDS.md"
---

# 项目编码规范

## Kotlin 规范

- 使用 4 空格缩进，不使用 Tab
- 函数命名采用 camelCase
- 类命名采用 PascalCase
- 常量使用 UPPER_SNAKE_CASE

## Git 提交规范

- feat: 新功能
- fix: 修复
- docs: 文档
- refactor: 重构
- test: 测试

## 文档要求

- 所有公共函数必须有 KDoc 注释
- 复杂逻辑需要内联注释
```

3. 配置启用:

```yaml
- skills_config:
    hooksEnabled: true
    workspaceDir: "."  # 当前目录
```

4. 运行 Agent 时会自动注入:

```
[Agent 启动]
✓ Loaded workspace hook: coding-standards [workspace]
[Agent 环境中存在虚拟文件]: COBRAIDRUN_STANDARDS.md
[Agent 行为]: 严格遵循 COBRAIDRUN_STANDARDS.md 中的规范
```

### 5.3 创建动态钩子（Python）

**场景**: 根据 Git 分支动态注入不同的提示

1. 创建 `hooks/braidrun-agent/git-context/HOOK.md`:

```markdown
---
name: git-context
description: "根据当前 Git 分支注入上下文"
emoji: "🌿"
metadata:
  braidrun-agent:
    events:
      - agent:bootstrap
    requires:
      bins:
        - git
---

# Git Context Hook

此钩子会被 handler.py 动态处理。
```

2. 创建 `hooks/braidrun-agent/git-context/handler.py`:

```python
#!/usr/bin/env python3
import sys
import json
import subprocess

def get_git_branch():
    try:
        result = subprocess.run(
            ['git', 'rev-parse', '--abbrev-ref', 'HEAD'],
            capture_output=True,
            text=True,
            timeout=5
        )
        return result.stdout.strip()
    except:
        return None

def main():
    # 读取钩子上下文
    context = json.loads(sys.stdin.read())
    workspace_dir = context.get('workspaceDir', '')

    # 获取 Git 分支
    branch = get_git_branch()

    if not branch:
        # 不是 Git 仓库，跳过
        print(json.dumps({
            "injectContent": "",
            "messages": ["不是 Git 仓库，跳过 Git 上下文注入"]
        }))
        return

    # 根据分支生成不同的提示
    if branch == 'main' or branch == 'master':
        content = """
# 当前分支: {} (主分支)

⚠️ **重要提醒**:
- 你正在主分支上工作
- 任何修改都应该经过充分测试
- 考虑使用功能分支进行开发
- 不要直接提交未经审查的代码
""".format(branch)
    elif branch.startswith('feature/'):
        content = """
# 当前分支: {} (功能分支)

✅ 你正在功能分支上工作，可以：
- 自由实验和开发
- 频繁提交进度
- 在完成后创建 Pull Request
""".format(branch)
    elif branch.startswith('hotfix/'):
        content = """
# 当前分支: {} (紧急修复)

🔥 这是一个 hotfix 分支:
- 专注于快速修复关键问题
- 最小化改动范围
- 立即进行测试
- 准备快速部署
""".format(branch)
    else:
        content = f"# 当前分支: {branch}"

    # 返回动态生成的内容
    result = {
        "injectContent": content,
        "virtualFiles": [
            {
                "path": "GIT_CONTEXT.md",
                "content": content
            }
        ],
        "messages": [f"已注入 Git 分支上下文: {branch}"]
    }

    print(json.dumps(result, ensure_ascii=False))

if __name__ == "__main__":
    main()
```

```bash
chmod +x hooks/braidrun-agent/git-context/handler.py
```

3. 启用脚本执行:

```yaml
- skills_config:
    hooksEnabled: true
    hookScriptExecutionEnabled: true
    hookTimeoutSeconds: 30
    allowedScriptTypes: ["py", "js", "kts"]
    workspaceDir: "."
```

4. 运行效果:

```bash
# 在 main 分支
$ git checkout main
$ braidrun-agent -a universal -c config.yaml

[Hooks] ✓ Script virtual file: GIT_CONTEXT.md (from git-context)
[Hooks] 已注入 Git 分支上下文: main

[Agent 收到的上下文]:
```

⚠️ **重要提醒**:

- 你正在主分支上工作
- 任何修改都应该经过充分测试
  ...

# 在 feature 分支

$ git checkout -b feature/new-api
$ braidrun-agent -a universal -c config.yaml

[Hooks] ✓ Script virtual file: GIT_CONTEXT.md (from git-context)
[Hooks] 已注入 Git 分支上下文: feature/new-api

[Agent 收到的上下文]:
✅ 你正在功能分支上工作，可以：

- 自由实验和开发
  ...

```

### 5.4 钩子事件示例

#### 示例 1: 消息接收时注入时间戳

```markdown
---
name: timestamp-injector
metadata:
  braidrun-agent:
    events:
      - message:received
---

当前时间: {{current_time}} (由钩子注入)
```

#### 示例 2: 会话结束时保存日志

```python
# handler.py for session:end event
import sys, json
from datetime import datetime

context = json.loads(sys.stdin.read())
session_key = context.get('sessionKey', 'unknown')

# 保存会话元数据
log_file = f"logs/session_{session_key}_{datetime.now().strftime('%Y%m%d_%H%M%S')}.json"
with open(log_file, 'w') as f:
    json.dump(context, f, indent=2)

print(json.dumps({
    "messages": [f"会话日志已保存到 {log_file}"]
}))
```

#### 示例 3: 错误时发送通知

```python
# handler.py for agent:error event
import sys, json
import requests

context = json.loads(sys.stdin.read())

# 发送 Telegram 通知
TELEGRAM_BOT_TOKEN = "your-bot-token"
TELEGRAM_CHAT_ID = "your-chat-id"

message = f"🚨 Agent 错误\\nSession: {context.get('sessionKey')}\\nWorkspace: {context.get('workspaceDir')}"

requests.post(
    f"https://api.telegram.org/bot{TELEGRAM_BOT_TOKEN}/sendMessage",
    json={"chat_id": TELEGRAM_CHAT_ID, "text": message}
)

print(json.dumps({
    "messages": ["错误通知已发送"]
}))
```

---

## 6. 实战案例

### 案例 1: 自动化代码审查 Agent

**需求**: 审查 Git 仓库中最近的提交，检查代码质量

**配置文件 `code-review-agent.yaml`**:

```yaml
parameters:
  - language: Chinese
  - prompt: |
      请执行以下代码审查任务：
      1. 获取最近 3 次 Git 提交
      2. 分析每次提交的改动
      3. 检查：
         - 代码风格是否一致
         - 是否有潜在的 bug
         - 是否有性能问题
         - 是否有安全漏洞
      4. 生成审查报告到 code_review_report.md

  - strategy: plan_solve_reasoning
  - show_reasoning: true

  - llm_config:
      models:
        - model: anthropic/claude-3-5-sonnet
          provider: open_router
      temperature: 0.3  # 低温度，更准确

  - llm_provider_keys:
      openrouter: "sk-or-v1-..."

  - tool_set:
      - exit
      - shell
      - file_system

  - working_dir: "."
  - output_dir: "./reports"
```

**运行**:

```bash
braidrun-agent -a universal -c code-review-agent.yaml
```

**输出**:

```markdown
# 代码审查报告

## 概览
- 审查时间: 2025-03-15 10:30:00
- 审查提交数: 3
- 发现问题: 5 个

## 提交 1: feat: Add user authentication
- ✅ 代码风格: 符合规范
- ⚠️ 潜在问题:
  - 文件: UserAuth.kt:45
  - 问题: 密码未加盐哈希，存在安全风险
  - 建议: 使用 BCrypt 或 Argon2

## 提交 2: fix: Resolve memory leak
- ✅ 代码风格: 符合规范
- ✅ 修复了内存泄漏

## 提交 3: refactor: Simplify error handling
- ✅ 代码风格: 符合规范
- ⚠️ 潜在问题:
  - 文件: ErrorHandler.kt:23
  - 问题: 捕获了过于宽泛的异常
  - 建议: 使用具体的异常类型

## 总结
总体质量良好，建议优先修复安全问题。
```

### 案例 2: 多 Agent 协同的文档生成系统

**架构**:

```
主 Agent (协调器)
├─> 子 Agent 1 (代码分析)
├─> 子 Agent 2 (API 文档生成)
└─> 子 Agent 3 (README 生成)
```

**主 Agent 配置 `doc-generator-main.yaml`**:

```yaml
parameters:
  - language: Chinese
  - prompt: |
      协调生成完整项目文档：
      1. 委派子 Agent 分析代码结构
      2. 委派子 Agent 生成 API 文档
      3. 委派子 Agent 生成 README
      4. 汇总所有结果

  - strategy: plan_solve
  - sub_agent_strategy: just_work_parallel

  - llm_config:
      models:
        - model: anthropic/claude-3-5-sonnet
          provider: open_router

  - llm_provider_keys:
      openrouter: "sk-or-v1-..."

  - tool_set:
      - exit
      - sub_agent
      - file_system
```

**子 Agent 配置 `doc-generator-sub.yaml`**:

```yaml
parameters:
  - strategy: just_work_parallel

  - llm_config:
      models:
        - model: deepseek/deepseek-chat  # 使用更便宜的模型
          provider: open_router

  - llm_provider_keys:
      openrouter: "sk-or-v1-..."

  - tool_set:
      - exit
      - shell
      - file_system
```

**Kotlin 启动代码**:

```kotlin
import com.fartech.agents.commons.buildAndRunStringAgent
import com.fartech.ftapp2.commonsKt.*

suspend fun main() {
    val httpAccess = HttpAccess()

    // 主 Agent
    val mainParams = loadConfigFromYaml("doc-generator-main.yaml")

    val (mainAgent, result) = buildAndRunStringAgent(
        httpAccess = httpAccess,
        parameters = mainParams,
        systemPrompt = "你是文档生成系统的主协调器"
    )

    println("文档生成完成: $result")
}
```

**执行流程**:

```
[主 Agent] 开始执行计划
  Step 1: 委派子 Agent 分析代码
    [子 Agent 1] 扫描 src/ 目录
    [子 Agent 1] 生成代码结构图
    [子 Agent 1] 返回结果

  Step 2: 委派子 Agent 生成 API 文档
    [子 Agent 2] 解析公共 API
    [子 Agent 2] 生成 Markdown 文档
    [子 Agent 2] 返回结果

  Step 3: 委派子 Agent 生成 README
    [子 Agent 3] 收集项目信息
    [子 Agent 3] 生成 README.md
    [子 Agent 3] 返回结果

  Step 4: 汇总
    [主 Agent] 整合所有文档
    [主 Agent] 生成目录索引
    [主 Agent] 完成 ✓
```

### 案例 3: 带 Telegram 通知的监控 Agent

**需求**: 监控服务器状态，异常时通知

**配置 `monitor-agent.yaml`**:

```yaml
parameters:
  - language: Chinese
  - prompt: |
      每 5 分钟检查一次：
      1. CPU 使用率
      2. 内存使用率
      3. 磁盘空间
      4. 关键进程是否运行

      如果任何指标超过阈值，通过 Telegram 发送警报

  - strategy: just_work_parallel
  - max_iterations: 9999  # 长期运行

  - llm_config:
      models:
        - model: deepseek/deepseek-chat
          provider: open_router

  - llm_provider_keys:
      openrouter: "sk-or-v1-..."

  - tool_set:
      - shell
      - im  # Instant Messaging

  - im_service: "telegram"
  - im_telegram_bot_token: "123456789:AAxxxxx"
  - im_telegram_chat_id: "987654321"
```

**增强: 添加错误通知钩子**

创建 `hooks/braidrun-agent/error-notifier/HOOK.md`:

```markdown
---
name: error-notifier
metadata:
  braidrun-agent:
    events:
      - agent:error
---
```

创建 `hooks/braidrun-agent/error-notifier/handler.py`:

```python
#!/usr/bin/env python3
import sys, json, requests

context = json.loads(sys.stdin.read())

# 发送 Telegram 警报
BOT_TOKEN = "123456789:AAxxxxx"
CHAT_ID = "987654321"

message = f"🚨 监控 Agent 崩溃\\n会话: {context.get('sessionKey')}"

requests.post(
    f"https://api.telegram.org/bot{BOT_TOKEN}/sendMessage",
    json={"chat_id": CHAT_ID, "text": message}
)

print(json.dumps({"messages": ["错误通知已发送"]}))
```

---

## 7. 工作流编排

工作流系统是 braidrun-agent 的核心特性之一，允许通过声明式 YAML 定义多 Agent 协同任务。本节通过循序渐进的示例介绍工作流的使用方法。

### 7.1 第一个工作流

**步骤 1**: 创建工作流目录和 YAML 文件

```bash
mkdir -p workflows/templates
cat > workflows/my-first-workflow.yaml << 'EOF'
name: "hello-workflow"
version: "1.0.0"
description: "简单的两步工作流示例"

agents:
  writer:
    preset: writer
    overrides:
      system_prompt: "你是一个技术作家，请用简洁明了的语言写作。"

  reviewer:
    preset: universal
    overrides:
      system_prompt: "你是一个严格的审稿人，请检查文章质量并给出改进建议。"

workflow:
  - step: write_article
    agent: writer
    input: "请写一篇 300 字的关于 AI Agent 的介绍文章，保存到 article.md"

  - step: review_article
    agent: reviewer
    input: "请审核以下文章并给出评价：\n{{write_article}}"
    depends_on: [write_article]
EOF
```

**步骤 2**: 执行工作流

```bash
# 方式 1: 通过当前 CLI 直接执行 workflow
braidrun-agent -w workflows/my-first-workflow.yaml

# 也可以覆盖 workflow 变量
braidrun-agent -w workflows/my-first-workflow.yaml -v topic="AI Agent"

# 方式 1.5: 先 dry-run 验证工作流，确认无误后再执行 (v1.1.0)
./gradlew :braidrun-agent-cli:run --args='-w workflows/my-first-workflow.yaml --dry-run'

# 方式 2: 让 Agent 帮你执行（需要启用 workflow 工具集）
# 在 Agent 对话中说: "请执行 workflows/my-first-workflow.yaml"
```

**步骤 3**: 编程方式执行

```kotlin
val executor = WorkflowExecutor(httpAccess, parameters)
val workflow = WorkflowParser.parseFile("workflows/my-first-workflow.yaml")
val result = executor.execute(workflow)

println("成功: ${result.success}")
println("耗时: ${result.duration}ms")
result.stepResults.forEach { (step, res) ->
    println("  $step: ${if (res.success) "✅" else "❌"} (${res.duration}ms)")
}
```

### 7.2 使用预设和变量

预设模式让你无需重复配置 LLM、工具集等参数，直接引用已定义的 Agent 模板：

```yaml
name: "research-pipeline"
version: "1.0.0"
description: "研究分析流水线"

variables:
  topic: "大语言模型的发展趋势"
  language: "zh"
  output_dir: "./research-output"

agents:
  researcher:
    preset: researcher                    # 引用内置的 researcher 预设
    overrides:
      system_prompt: "你是一位资深 AI 研究员，擅长深度分析和文献综述。"

  analyst:
    preset: data_analyst
    overrides:
      system_prompt: "你是数据分析师，擅长从研究材料中提取关键数据和趋势。"

  writer:
    preset: writer

workflow:
  - step: research
    agent: researcher
    input: "请研究「{{var:topic}}」，搜索最新资料并整理关键发现，用{{var:language}}输出"

  - step: analyze
    agent: analyst
    input: "基于以下研究材料，提取关键数据点和趋势：\n{{research}}"
    depends_on: [research]

  - step: write_report
    agent: writer
    input: "基于以下研究和分析，撰写完整报告，保存到 {{var:output_dir}}/report.md：\n\n研究：{{research}}\n\n分析：{{analyze}}"
    depends_on: [research, analyze]
```

可用的内置预设 ID（共 19 个）: `universal`, `universal_reasoning`, `coder`, `researcher`, `writer`, `data_analyst`,
`asa`, `pdf_processor`, `lightweight`, `chat`, `office_document`, `word_document`, `excel_workbook`, `powerpoint_presentation`,
`multimedia_creator`, `devops`, `communication`, `web_scraper`, `computer_operator`

### 7.3 条件分支和错误处理

工作流支持条件执行、成功/失败转移和重试策略：

```yaml
name: "quality-pipeline"
version: "1.0.0"
description: "带质量控制的内容流水线"

agents:
  writer:
    preset: writer
  reviewer:
    preset: universal
    overrides:
      system_prompt: |
        你是内容审核员。审核文章后：
        1. 在输出中包含 "APPROVED" 或 "REJECTED"
        2. 使用 set_variable 设置 quality 分数 (0-100)
  editor:
    preset: writer
    overrides:
      system_prompt: "你是专业编辑，负责润色和最终排版。"

workflow:
  - step: write_draft
    agent: writer
    input: "撰写一篇关于量子计算的科普文章"
    timeout: "300s"

  - step: quality_review
    agent: reviewer
    input: "请严格审核以下文章，给出 0-100 的质量分数：\n{{write_draft}}"
    depends_on: [write_draft]
    retry:
      max_attempts: 2
      backoff: exponential
      initial_delay: 2000
      max_delay: 30000
    on_failure:
      - next: write_draft
        message: "审核失败，请重新生成"

  - step: polish
    agent: editor
    input: "请润色以下文章并保存为最终版本：\n{{write_draft}}"
    depends_on: [quality_review]
    condition: "quality >= 70"

  - step: rewrite
    agent: writer
    input: "文章质量不达标，请根据反馈重写：\n{{quality_review}}"
    depends_on: [quality_review]
    condition: "quality < 70"

error_handling:
  max_retries: 3
  retry_delay: "5s"
  continue_on_error: false

timeout:
  total: "1800s"
  per_step: "300s"
```

### 7.4 并行执行

当多个步骤没有依赖关系，并且你显式开启顶层 `concurrency.enabled: true` 时，工作流引擎才会按 DAG 层级并行执行：

```yaml
name: "parallel-research"
version: "1.0.0"
description: "并行研究和汇总"

concurrency:
  enabled: true
  max_concurrency: 3

agents:
  researcher:
    preset: researcher
  summarizer:
    preset: writer

workflow:
  # 这三个步骤没有依赖，会并行执行
  - step: research_ai
    agent: researcher
    input: "研究人工智能领域的最新进展"
    priority: 10

  - step: research_quantum
    agent: researcher
    input: "研究量子计算领域的最新进展"
    priority: 10

  - step: research_biotech
    agent: researcher
    input: "研究生物技术领域的最新进展"
    priority: 10

  # 汇总步骤依赖所有研究步骤
  - step: create_summary
    agent: summarizer
    input: |
      请将以下三个领域的研究汇总为一篇综合报告：
      
      AI: {{research_ai}}
      量子计算: {{research_quantum}}
      生物技术: {{research_biotech}}
    depends_on: [research_ai, research_quantum, research_biotech]
```

步骤也支持内联并行子任务，但 `parallel` 只适用于单 Agent 步骤：

```yaml
  - step: parallel_analysis
    agent: analyst
    input: "分析所有数据"
    parallel:
      tasks: [analyze_sales, analyze_costs, analyze_growth]
      aggregate_results: true
      max_parallel: 3
```

### 7.5 迭代精炼 (`repeat_until`)

当您需要 Agent 反复改进输出直到质量达标时，使用 `repeat_until`：

约束：

- `repeat_until` 当前主要用于单 Agent 或 `group_chat`
- `code` 与 `agent_based` 步骤不能配置 `repeat_until`
- `iterate_over` 当前只支持单 Agent 或 `code`，且不能和步骤级 `parallel`、`retry`、`repeat_until`、`manual_approval`、`timeout_seconds` 组合

```yaml
name: "iterative-writing"
version: "2.0.0"
description: "迭代改进文章直到质量达标"

agents:
  writer:
    preset: writer
    overrides:
      system_prompt: "你是一位技术作家，根据反馈不断改进文章质量。"

  reviewer:
    preset: universal
    overrides:
      system_prompt: |
        你是严格的质量审核员。评估文章质量并给出 1-10 的评分。
        评分标准：完整性、清晰度、专业性、可读性。
        输出格式必须包含：quality_score=N（N 为 1-10 的整数）

variables:
  topic: "Kubernetes 容器编排最佳实践"

workflow:
  - step: write_article
    agent: writer
    input: |
      撰写一篇关于「{{var:topic}}」的技术文章。
      如果有上一轮的评审反馈，请参考改进：
      {{steps.write_article:evaluate.output}}
    repeat_until:
      condition: "quality_score >= 8"
      max_iterations: 3
      evaluate_agent: reviewer
      evaluate_prompt: |
        请评估以下文章的质量（1-10分）：
        {{steps.write_article.output}}
        输出你的评分：quality_score=N
      extract_pattern: "quality_score=(\\d+)"
      extract_variable: quality_score
```

**执行过程说明：**

1. **第 1 次迭代**：`writer` Agent 根据 `input` 撰写文章。由于是第一次执行，`{{steps.write_article:evaluate.output}}` 为空。
2. **评估**：`reviewer` Agent 使用 `evaluate_prompt` 评估文章，输出如 `"quality_score=6"`。
3. **变量提取**：`extract_pattern` 匹配到 `6`，存入变量 `quality_score`。
4. **条件检查**：`quality_score >= 8` → `6 >= 8` = false → 继续迭代。
5. **第 2 次迭代**：`writer` 再次执行，此时 `{{steps.write_article:evaluate.output}}` 包含上一轮评审反馈。
6. 重复直到 `quality_score >= 8` 或达到 `max_iterations: 3`。

### 7.6 Agent-based 动态编排

当执行流程无法预先确定、需要 LLM 根据内容动态决定调用哪些 Agent 时，使用 `agent_based` 步骤：

```yaml
name: "dynamic-code-review"
version: "2.0.0"
description: "LLM 动态决策的多角度代码审查"

agents:
  security_expert:
    preset: universal
    overrides:
      system_prompt: "你是安全专家，专注于发现代码中的安全漏洞、注入风险、权限问题。"

  performance_expert:
    preset: universal
    overrides:
      system_prompt: "你是性能专家，专注于识别性能瓶颈、内存泄漏、算法效率问题。"

  architecture_expert:
    preset: universal
    overrides:
      system_prompt: "你是架构专家，评估代码结构、设计模式、可维护性和扩展性。"

  test_expert:
    preset: universal
    overrides:
      system_prompt: "你是测试专家，评估测试覆盖率、边界情况、测试策略。"

variables:
  code_path: "./src/main/kotlin"

workflow:
  - step: code_review
    agent_based:
      orchestrator:
        preset: universal
        overrides:
          system_prompt: |
            你是代码审查编排者。分析提交的代码后，决定需要哪些专家参与审查。

            决策原则：
            - 如果代码涉及用户输入处理或认证逻辑 → 必须让 security_expert 审查
            - 如果代码涉及数据库查询或大量计算 → 让 performance_expert 审查
            - 如果代码有复杂类结构或设计模式 → 让 architecture_expert 审查
            - 如果代码缺少测试或测试不充分 → 让 test_expert 审查

            你可以使用 delegateParallel 同时让多个专家并行审查以提高效率。
            收集所有反馈后，综合生成最终审查报告。
      participants:
        - security_expert
        - performance_expert
        - architecture_expert
        - test_expert
      goal: |
        审查以下代码路径中的代码：{{var:code_path}}
        根据代码内容，智能选择需要参与的专家进行针对性审查。
        综合所有专家意见生成最终审查报告。
      max_steps: 8
      budget_tokens: 200000
```

**Orchestrator 的决策过程（示例）：**

```
Orchestrator: 让我先了解可用的专家...
→ 调用 getParticipantInfo()
→ 返回: security_expert, performance_expert, architecture_expert, test_expert

Orchestrator: 这段代码涉及 API 端点和数据库操作，我需要安全和性能专家。
→ 调用 delegateParallel("security_expert,performance_expert", 
    "审查 API 端点的安全性,审查数据库查询的性能")
→ 返回: 两位专家的审查结果

Orchestrator: 安全专家发现了 SQL 注入风险，我需要让架构专家评估修复方案。
→ 调用 delegateTask("architecture_expert", "评估以下安全问题的架构级修复方案...")
→ 返回: 架构建议

Orchestrator: 收集完毕，生成最终报告。
→ 调用 complete("综合审查报告：发现 3 个安全问题、2 个性能优化点...")
```

### 7.7 混合工作流

将静态 DAG、`repeat_until`、`agent_based` 和 `group_chat` 组合在一起，构建复杂的端到端工作流：

```yaml
name: "product-launch"
version: "2.0.0"
description: "产品发布全流程——混合工作流示例"

agents:
  pm:
    preset: universal
    overrides:
      system_prompt: "你是产品经理，负责需求分析和产品规划。"
  designer:
    preset: universal
    overrides:
      system_prompt: "你是 UI/UX 设计师。"
  engineer:
    preset: coder
  qa:
    preset: universal
    overrides:
      system_prompt: "你是 QA 工程师。"
  reviewer:
    preset: universal
    overrides:
      system_prompt: "你是质量审核员，评分格式：quality_score=N"

workflow:
  # 步骤 1: 普通 DAG 步骤 — PM 收集需求
  - step: requirements
    agent: pm
    input: "收集并整理产品需求..."

  # 步骤 2: agent_based — Orchestrator 动态分配设计和技术评审
  - step: expert_review
    agent_based:
      orchestrator:
        preset: universal
        overrides:
          system_prompt: "根据需求内容，选择合适的专家进行评审..."
      participants: [designer, engineer, qa]
      goal: "对 {{steps.requirements.output}} 进行多角度评审"
      max_steps: 10
    depends_on: [requirements]

  # 步骤 3: group_chat — 团队讨论达成共识
  - step: team_discussion
    group_chat:
      participants: [pm, designer, engineer]
      initial_message: "讨论评审结果：{{steps.expert_review.output}}"
      max_rounds: 5               # 每个 participant 发言一次算一轮
      speaker_selection: round_robin
      termination_keyword: "CONSENSUS_REACHED"
    depends_on: [expert_review]

  # 步骤 4: repeat_until — 迭代改进直到质量达标
  - step: refine_plan
    agent: pm
    input: |
      根据团队讨论 {{steps.team_discussion.output}} 改进产品规划。
      上次评审反馈：{{steps.refine_plan:evaluate.output}}
    depends_on: [team_discussion]
    repeat_until:
      condition: "quality_score >= 9"
      max_iterations: 3
      evaluate_agent: reviewer

  # 步骤 5: 普通步骤 — 生成最终发布文档
  - step: launch_doc
    agent: pm
    input: "基于 {{steps.refine_plan.output}} 生成产品发布文档"
    depends_on: [refine_plan]
```

> 💡 **提示**: 完整的混合工作流生产示例可参考本仓库 `workflows/templates/prd-review-refinement.yaml`。

### 7.8 列表迭代 (`iterate_over`)

当需要对列表中的每个项目分别处理时，使用 `iterate_over`：

```yaml
name: "batch-translate"
version: "1.0.0"
description: "逐条翻译内容"

agents:
  collector:
    preset: universal
  translator:
    preset: writer
    overrides:
      system_prompt: "你是专业翻译。"

workflow:
  - step: collect
    agent: collector
    input: "列出 3 个待翻译的句子，每行一个"

  - step: translate
    agent: translator
    input: "将以下内容翻译为英文：{{current_item}}"
    depends_on: [collect]
    iterate_over:
      source: "{{steps.collect.output}}"
      delimiter: "\n"
      item_variable: current_item
      index_variable: current_index
      parallel: true
      max_parallel: 3
      results_variable: translations
```

约束：`iterate_over` 只支持单 Agent 或 `code` 步骤，不能与 `parallel`、`retry`、`repeat_until`、`manual_approval`、`timeout_seconds` 组合。

### 7.9 确定性代码步骤 (`code`)

当步骤不需要 LLM 推理，而是确定性逻辑时，使用 `code` 步骤：

```yaml
name: "data-pipeline"
version: "1.0.0"
description: "含确定性代码步骤的数据流水线"

agents:
  analyst:
    preset: data_analyst

workflow:
  - step: prepare_data
    code:
      language: python
      script: |
        import os, json
        topic = os.environ.get("WF_VAR_TOPIC", "default")
        result = {"topic": topic, "items": ["item1", "item2", "item3"]}
        print(json.dumps(result))
      timeout: 30

  - step: analyze
    agent: analyst
    input: "分析以下数据：{{steps.prepare_data.output}}"
    depends_on: [prepare_data]

  - step: format_output
    code:
      language: bash
      script: |
        echo "Analysis completed at $(date)"
        echo "Input was: $STEP_INPUTS"
    depends_on: [analyze]

variables:
  topic: "AI trends"
```

支持的语言：`python`、`javascript`、`typescript`、`bash`、`ruby`、`lua`、`cli`

输入通过环境变量注入：`STEP_INPUTS`（上游输出）、`WF_VAR_xxx`（工作流变量，键名大写）、`STEP_OUTPUT_xxx`（指定步骤输出）。输出为 `stdout`。

### 7.10 手动审批

对于需要人工确认的关键步骤，可以配置手动审批：

```yaml
  - step: deploy_production
    agent: devops_agent
    input: "部署应用到生产环境"
    depends_on: [run_tests]
    manual_approval:
      enabled: true
      approvers: [admin, team_lead]
      timeout: 3600              # 审批超时 1 小时
      approval_message: "⚠️ 即将部署到生产环境，请确认是否继续"
```

编程方式处理审批：

```kotlin
val executor = WorkflowExecutor(httpAccess, parameters, approvalHandler = object : ApprovalHandler {
    override suspend fun requestApproval(request: ApprovalRequest): Boolean {
        println("审批请求: ${request.message}")
        println("输入 'yes' 继续，'no' 拒绝:")
        return readLine()?.trim()?.lowercase() == "yes"
    }
})
```

如果不传 `approvalHandler`，`execute()` 会在该步骤挂起，直到外部调用 `approveStep(approvalId)` / `rejectStep(approvalId)`，或超时结束。

### 7.11 监控和版本控制

**实时监控**：

```kotlin
// 查询执行指标
val metrics = WorkflowMonitor.getMetrics(executionId)
println("状态: ${metrics?.status}")
println("进度: ${metrics?.completedSteps}/${metrics?.totalSteps}")
println("成功率: ${metrics?.getSuccessRate()}")

// 生成执行报告
val report = WorkflowMonitor.generateReport(executionId)
println(report)

// 查看统计数据
val stats = WorkflowMonitor.getWorkflowStats("my-workflow")
println("总执行次数: ${stats.totalExecutions}")
println("平均耗时: ${stats.averageDuration}ms")
```

**版本控制**：

```kotlin
val versionControl = WorkflowVersionControl()

// 保存版本
versionControl.saveVersion(
    workflow = WorkflowParser.parseFile("workflow.yaml"),
    workflowPath = "workflow.yaml",
    description = "增加了质量审核步骤",
    createdBy = "alex"
)

// 查看版本历史
val versions = versionControl.getVersions("my-workflow")
versions.forEach { println("${it.version}: ${it.description} (${it.createdAt})") }

// 对比版本
val comparison = versionControl.compareVersions("my-workflow", "1.0.0", "2.0.0")
comparison.changes.forEach { println(it) }

// 回滚
versionControl.rollback("my-workflow", "1.0.0", "workflow.yaml")

// 清理旧版本（保留最近 5 个）
versionControl.pruneVersions("my-workflow", keepCount = 5)
```

**通过 MCP 工具使用**（让 Agent 管理工作流）：

```
用户: "保存当前工作流的版本，描述为'增加并行步骤'"
Agent 调用: saveWorkflowVersion(workflowPath="workflow.yaml", description="增加并行步骤")

用户: "对比 1.0.0 和 2.0.0 版本的差异"
Agent 调用: compareWorkflowVersions(workflowName="my-workflow", version1="1.0.0", version2="2.0.0")

用户: "查看上次执行的指标报告"
Agent 调用: generateExecutionReport(executionId="abc-123")
```

### 7.12 工作流验证

在执行前验证工作流定义，检测常见错误：

```kotlin
val workflow = WorkflowParser.parseFile("workflow.yaml")

// 完整验证（自动执行所有 16 项检查）
WorkflowParser.validateWorkflow(workflow)
// 检查项包括：
// - Agent 引用有效性（步骤引用的 agent 是否已定义）
// - 预设 ID 有效性（preset 是否存在于 AgentPresetRegistry）
// - 步骤依赖有效性（depends_on 引用的步骤是否存在）
// - 循环依赖检测（DAG 中是否存在环）
// - 转移引用有效性（on_success/on_failure 中引用的步骤是否存在）
// - 并行任务有效性（parallel.tasks 引用是否合法）
// - 条件表达式格式（condition 语法是否正确）
// - repeat_until 验证（evaluate_agent 存在、extract_pattern 正则合法、不与 agent_based/code 组合）
// - agent_based 验证（participants 已定义、goal 非空、orchestrator 引用合法）
// - knowledge_base 验证（source 路径无重复、embedding_provider 合法）
// - code 步骤验证（language 合法、script/script_file 二选一、不与 repeat_until 组合）
// - classifier 步骤验证（agent 存在、≥2 个 category、category name 唯一）
// - state_machine 验证（状态定义合法、transitions 目标存在、event 非空）
// - extract 验证（pattern 或 json_path 二选一、variable 不重复、正则合法）
// - iterate_over 验证（source 非空、仅支持单 agent/code、不与 parallel/retry/repeat_until/manual_approval/timeout_seconds 组合）
// - aggregate 验证（≥2 个 source、source 非空）

// 获取工作流摘要
val summary = WorkflowParser.getWorkflowSummary(workflow)
println(summary)

// 获取拓扑排序（执行顺序）
val order = WorkflowParser.getTopologicalOrder(workflow)
order.forEach { println("${it.step} -> agent: ${it.agent}") }
```

---

## 8. 进阶主题

### 8.1 优化 Token 消耗

**策略 1: 使用 Prompt Cache**

```yaml
- cache_policy: file
- file_cache_storage: ".prompt_cache"
- max_files: 2048
```

**策略 2: 历史压缩**

```yaml
- history_compression:
    strategy: from_last_n
    n: 10  # 只保留最近 10 条消息
    preserve_memory: true
```

**策略 3: 模型选择**

```yaml
- llm_config:
    models:
      # 简单任务用便宜模型
      - model: deepseek/deepseek-chat
        provider: open_router

      # 复杂任务用强模型
      - model: anthropic/claude-3-5-sonnet
        provider: open_router
```

**策略 4: 技能优先**

```
❌ 直接调用工具: 每次传输完整工具定义 (500+ tokens)
✅ 使用技能: 只传摘要 (50 tokens)，按需加载
```

**策略 5: 使用 compact_env 压缩环境信息 (v1.1.0)**

`compact_env` 参数控制是否压缩传递给 LLM 的环境信息。默认为 `true`，会精简系统环境描述、已安装的解释器列表等上下文，从而显著减少每轮对话的 token 消耗。

```yaml
parameters:
  # 默认已开启，无需额外设置
  - compact_env: true
```

在需要详细环境信息的调试场景中，可以关闭压缩：

```yaml
parameters:
  # 关闭压缩 -- Agent 会收到完整的环境描述（解释器路径、版本等）
  - compact_env: false
```

**对比效果**:

```
compact_env: true  -> 环境信息约 100-200 tokens (推荐)
compact_env: false -> 环境信息约 800-1500 tokens (调试用)
```

对于大多数生产场景，保持默认的 `compact_env: true` 即可。只在需要 Agent 精确感知系统环境（例如多版本 Python 选择、特定路径排查）时才考虑设为 `false`。

### 8.2 调试和追踪

**启用 Langfuse 追踪**:

```yaml
- enable_langfuse_tracing: true
- langfuse_url: "https://cloud.langfuse.com"
- langfuse_public_key: "pk-lf-..."
- langfuse_secret_key: "sk-lf-..."
```

在 Langfuse Dashboard 可以看到：

- 每个 LLM 调用的详细信息
- Token 使用统计
- 延迟分析
- 成本计算

**启用推理显示**:

```yaml
- strategy: just_work_parallel_reasoning
- show_reasoning: true
```

输出：

```
💭 [Reasoning]
我需要先列出当前目录的文件，然后读取 README.md 的内容...

[Action] list_directory(path=".")
[Result] README.md, src/, build.gradle.kts, ...

💭 [Reasoning]
找到了 README.md，现在读取它的内容...

[Action] read_file(path="README.md")
[Result] # My Project\n\nThis is...
```

### 8.3 持久化和恢复

**启用检查点**:

```yaml
- enable_persistence: true
- persistence_storage_type: file
- persistence_storage_root: ".agent_snapshots"
```

**自动恢复**:

```kotlin
val parameters = listOf(
    parameter("enable_persistence", true),
    parameter("persistence_storage_type", "file"),
    parameter("session_id", "my-session-123")  // 使用固定 session ID
)

// Agent 会自动从上次中断的地方继续
universalAgent(httpAccess, parameters)
```

### 8.4 安全最佳实践

**1. 限制钩子脚本**:

```yaml
- skills_config:
    skillWhitelistMode: true       # 只允许白名单中的 skill / hook 生效
    enabledSkills: ["trusted-skill"]
    hookScriptExecutionEnabled: false  # 禁用脚本执行
    # 或限制脚本类型
    allowedScriptTypes: ["py"]  # 只允许 Python
```

**2. 使用环境变量存储密钥**:

```bash
export OPENROUTER_API_KEY="sk-or-v1-..."
export TELEGRAM_BOT_TOKEN="123456789:AAxxxxx"
```

```yaml
- llm_provider_keys:
    openrouter: ${OPENROUTER_API_KEY}  # 从环境变量读取
```

**3. 限制文件系统访问**:

使用 MCP 服务器而非直接 `file_system` 工具：

```yaml
- mcp_servers:
    safe_filesystem:
      command: "npx"
      args: ["-y", "@modelcontextprotocol/server-filesystem", "/safe/directory"]
```

### 8.5 性能优化

**并行工具调用**:

```yaml
- strategy: just_work_parallel  # 支持并行工具调用
```

Agent 可以同时执行多个工具：

```
并行执行:
├─> read_file("README.md")
├─> read_file("CHANGELOG.md")
└─> list_directory("src/")

而非顺序执行:
read_file("README.md") → 等待 → read_file("CHANGELOG.md") → 等待 → list_directory("src/")
```

**减少重试延迟**:

```yaml
- retry_max_attempts: 3
- retry_initial_delay: 500   # 500ms
- retry_max_delay: 5000      # 5s
```

---

## 常见问题

### Q: Agent 不响应或卡住？

**排查步骤**:

1. 检查 API 密钥是否有效
2. 查看是否触发了速率限制
3. 增加 `retry_max_attempts`
4. 检查网络连接
5. 查看日志文件

### Q: `./gradlew` 报 `Invalid or corrupt jarfile gradle/wrapper/gradle-wrapper.jar`？

这个仓库的 wrapper jar 通过 Git LFS 管理；如果 checkout 后看到的是 pointer 文件，`./gradlew` 就无法启动。

```bash
git lfs pull --include=gradle/wrapper/gradle-wrapper.jar
```

如果当前机器没有 Git LFS，也可以暂时改用系统安装的 `gradle` 执行同样的任务。

### Q: Token 消耗过大？

**优化措施**:

1. 启用 `cache_policy: file`
2. 配置 `history_compression`
3. 使用 `just_work_parallel` 策略
4. 限制 `max_iterations`
5. 使用技能而非工具
6. 确认 `compact_env: true` 已启用 (v1.1.0 默认开启，可减少 600+ tokens/轮)

### Q: 钩子脚本不执行？

**检查清单**:

- [ ] `hookScriptExecutionEnabled: true`
- [ ] 脚本有执行权限 (`chmod +x`)
- [ ] `allowedScriptTypes` 包含脚本类型
- [ ] `requires` 条件满足
- [ ] 脚本输出正确的 JSON 格式

### Q: 如何调试 Agent？

```yaml
- show_reasoning: true
- enable_langfuse_tracing: true
- execution_log_storage_type: file
- execution_log_file_name: "debug.md"
```

---

## 下一步

- 阅读 [API_REFERENCE.md](API_REFERENCE.md) 了解完整 API
- 查看 `skills/` 目录下的示例技能
- 加入社区讨论

祝你使用 braidrun-agent 构建强大的 AI 应用！🚀
