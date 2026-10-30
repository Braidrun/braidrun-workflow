# Runner Agent 工具第一段实施记录（2026-10-28）

基线：引擎 `ff1b561`，独立工作目录 `/tmp/braidrun-engine-agent-tools-20261028`。本段尚未提交、推送、打包或部署；不改变 `manual_approval.enabled`。主任务另行移除冻结副本中预设默认的 browser/shell/code_execution/git，本段不实现这三种子进程工具的沙箱，也不提前声明它们的能力。

## 实施范围

- **I1**：`WorkflowHost.agentToolSets(scope, toolNames, parameters)` 返回按组命名的宿主替换。普通 agent 与 agent_based orchestrator 同时从参数和工具列表删除被替换组，避免注册库实现。空列表代表明确禁用该组；同样抑制共享 RAG、宿主 workflow 和隐式 skill_tools 的旧注册路径。只允许替换当前请求的组。旧 `fileSystemTools` 在 executor 对实际 host 调用，兼容 Kotlin 委托包装器。
- **I2**：新增 bytes-only `ToolFileAccess`。默认实现保持 `ToolPathSecurity` 路径政策；Runner 的 `RunnerToolFileAccess` 使用同一尝试的 `RunnerFileConfinement`，共享 256 MiB 写配额、原子发布、防软链/硬链、私有目录和只读输入检查。二进制单次读上限 64 MiB，调用方可以收窄；文本仍为 2 MiB。分块读和返回前重查撤销。接口不暴露 File 或 stream；未来格式工具必须显式注入，不能在 Runner 回落到默认实现。
- **I3**：RunnerHostMain 传入 `runtime.toolHttpAccess`。宿主 OkHttp 配置追加在 `SsrfRedirectInterceptor` 之后，初始请求及每个 redirect hop 都重新检查 `jvm-http`、业务关闭状态与租约；保留公网 SSRF/DNS 检查及固定代理策略。跨 origin 重定向移除 Authorization/Cookie/Proxy-Authorization。`HttpAccess` 原构造器及尾 lambda 调用保持兼容。
- **I4**：集中登记需要工作目录的文件/子进程工具组，参数准备强制 working_dir/output_dir/persistence_storage_root；准入拒绝自选目录。未来子进程工具 capability 必须对应 subprocess 路径。当前仅新增 `tool.calendar.v1`、`tool.data_transform.v1`；其余未适配工具仍被准入拒绝。XML tag 输入改为字面值匹配，避免正则注入和前缀误匹配。
- **I5（平台工作目录）**：daemon 启动引擎加 `-Djava.awt.headless=true`；真实包构建检查目标 JRE release 的 `java.desktop`（不执行跨架构 Java）。合成 fixture 不伪称完整 JRE，可跳过此检查。对应改动位于 `runner-readiness-review/braidrun` 的 daemon.py、package.py、test_package.py、test_simulator_cleanup.py。

## W2b 契约对齐

从 `/tmp/braidrun-w2b-fix-20261028/braidrun-web/docs/contracts/runner-workflow-v1/late-control.schema.json` 核验并同步嵌入资源、`RunnerCodeControlNegotiation.SCHEMA_SHA256`、JAR manifest 构建常量：

`80dcf19cd932be50b7e7b89abfc9770f3c691b35227d5e3f72c761efe55638cc`

新增 `connector.google-marketing.operations.v1`，仅当精确的 per-call schema 可用时声明。W2b 的 typed 操作/视频等待由平台适配器执行。本地 schema 补充尚未提交，origin 元数据仍保留既有来源 commit `cc684f0a28b6b3f3106b91e486244843f979aa83`，它不是本地修改的提交证明。最终 RC 必须组合平台 W2b 和本段变更后重打运行时包。

## 验证与后续

已补 Kotlin 行为测试：多组宿主替换及空组禁用、旧 fileSystemTools 委托兼容、二进制读上限与共享写配额、私有/兄弟运行目录和链接拒绝、绑定输入只读及哈希变化、读中撤销、每跳 HTTP 撤销、跨域去凭据、私网 redirect 拒绝、仅两个纯工具组准入和 XML tag 字面匹配。

平台 Python：使用隔离环境 `/tmp/braidrun-readiness-venv-20261028/bin/python -m unittest test_package test_simulator_cleanup`，首轮 39 项，38 通过，1 个真实 JRE/引擎包集成测试按条件跳过（15.618 秒）。随后补充生产包缺模块拒绝测试，与 2 个模块边界测试共 3 项全部通过（0.007 秒），日志 `/tmp/braidrun-agent-tools-jre-20261028.log`。日志 `/tmp/braidrun-agent-tools-python-20261028.log`。系统 Python 首次尝试缺少 jsonschema，未安装系统依赖，改用已有隔离环境。

`git diff --check` 已通过。遵守本机 Mac 作业限制，本 agent 未启动 Gradle，故不声明 Kotlin 编译/测试通过，也不更新冻结模板可用数。主任务需在安全环境分开运行 compileKotlin、compileTestKotlin，然后执行 RunnerToolFileAccessTest、RunnerToolHttpAccessTest、RunnerAdmissionSecretsTest、WorkflowHostTest、RunnerCodeControlNegotiationTest、DataTransformXmlSafetyTest，以及 RunnerTemplateFreezeScanTest。新 JRE 真实包检查、4 平台打包和 capability 验证仍待最终组合。
