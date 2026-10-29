# Runner 子 Agent 宿主适配（本地代码验证，能力未启用）

本改动只在独立 worktree 编写，未提交、未推送、未部署，未广告 capability。`RunnerWorkflowHost` 的可信构造参数 `subAgentToolsEnabled` 默认为 false；即使 workflow 声明 `sub_agent`，也无法自行开启。默认公共库 `SubAgentTools` 行为保持原样。

普通 agent 与 agent_based 编排使用两阶段工具注册：宿主先建立子工具实例，待父 registry 组装结束后通过只读闭包取回同一个 registry。子图复用父工具实例、同一个 skillManager 和监控 installer；不重新 parseToolSet、不创建 skill manager、不执行技能 bootstrap、不准备 MCP/历史持久化/环境信息/遥测。含 MCP 的配置明确拒绝，因为父 registry 还会在 Bootstrap 中增补，当前闭包不能代表增补后的全集。

模型仅允许父配置中已明确解析的默认模型。key 必须已经由父作用域租约取得，不能从环境变量解析；模型端点继续经过已有门控 HTTP factory。子 Agent 的 LLM 只能完全匹配该 provider/model，不构造 cascade/fallback/retry executor。每个请求复核父 scope、业务 fence、环境 gate、现有凭据租约；父为 text-only local 模型时不允许子工具。

每个根 attempt 共用如下硬限额：

- 子层深度 1；递归在申请并发槽前拒绝。
- 全局同时运行最多 8 个子任务；单批并发参数也必须是 1–8。
- 子调用总预算 64（失败和取消不退回），模型请求总预算 256。
- 子任务 300 秒上限；显式 timeout 只能缩短，0 表示仍使用 300 秒。
- iterations 不超过父冻结 max_iterations，且最高 128。
- prompt 64 KiB、名字 256 字节、单输出 128 KiB、批量合计 1 MiB；拒绝 attachments 和子 reasoning 配置变更。

并行使用 structured coroutineScope，任一失败取消兄弟任务，取消异常继续向外传播；没有自动重试。所有子任务加入宿主 businessFence，终结取消后在 finally 关闭 graph 与唯一模型 executor/client，关闭幂等。

首版 17 项测试已由主整合实际执行并全部通过，覆盖跨父共享预算/并发、递归、超时、失败取消兄弟、取消与 fence、越界输出、固定模型和请求预算、恰好一次关闭、父工具实例与监控 installer 的真实图执行、未租约 key/MCP/未解析模型构造拒绝。所有协程测试声明 Unit，本 worktree 未运行 Gradle；主整合串行编译和执行。新增的传输、二级委派闭门与 scope 增量待复跑，不计入首版通过结果。

待完成：主整合编译和测试、宿主工厂端到端准入测试、RunnerAdmission allowlist/可信 runtime 入口的评审，最后才评估 capability 声明。此适配不解决本地模型的真实 P0/tokenizer/停止证明，也不接通平台两阶段 local-model assignment。

## 追加边界修复（待主整合测试）

实际 Koog 1.3 Ktor factory 字节码仅安装默认请求、SSE、内容协商及 timeout，没有 HttpRequestRetry；Runner 的 OkHttp 已关闭连接失败重试。然而 OkHttp 的 503/Retry-After:0 仍可能发生 follow-up，因此追加两层保护：每个模型 execute 只能消费一次 Koog POST permit；子 Agent 专用 OkHttp 客户端按同一 Call 身份，在 network interceptor 中拒绝第二次物理 dispatch。失败不退还许可，不允许 GET、SSE 或 line streaming。父云模型默认客户端不改变。

相同的物理 dispatch guard 同时用于 webHttpAccess 与 HttpAccess.singleRequestClient，安装幂等；新增真实 loopback HTTP fixture 验证 503 POST 计数 1、认证 follow-up 改 header 与 redirect 改地址仍无法重发、正常独立 GET 不受影响。未触碰生产 SSRF/DNS/私网策略；测试只在独立客户端注入 fixture DNS。

子 registry 中 executeWorkflow、delegateTask、delegateParallel 会启动当前计量包装器之外的更多模型请求。首期明确拒绝该组合，并拒绝声明 workflow + sub_agent；不以协程 depth 元素推断跨调度总预算已经受控。后续必须为这些执行入口传递可信 transitive budget 才能放开。父工具中的普通文件、网络和技能工具仍使用原实例。

Host 另设独立的 subAgentInvocations 登记，只从已验证 bindArtifactProducer 收录静态 lineage；不依赖 sandbox 是否启用。普通子工具 hook 从实际 WorkflowExecutionContext 传入 parentExecutionId 与 occurrence，Host 对缺省 parent 只从已登记 lineage 补齐，再与运行上下文全量比较。

增量新增 14 项测试；合计 31 项测试源。最终执行结果以主整合日志为准。

父模型 key 现在绑定实际 RunnerLiveCredentials.Handle；每次调用检查该 handle 的 active 状态、scope 和当前租约 material。live.checkAll 只复核仍活跃的租约，不能替代对父步骤已释放 handle 的拒绝。新增真实 Host + typed credential IPC fixture 3 项，验证关闭/动态作用域、父步骤释放后的构造和保留工具使用、独立静态 lineage 与 parent/occurrence 伪造拒绝；没有实际模型或公网请求。

补充 generation 校验：子工具捕获构造时的租约映射和实际 handle，后续要求当前映射/handle 身份仍相同，不能因父步骤重新 prepare 相同密钥而复活旧工具。新增第 4 项 Host 测试覆盖 release → 相同 key 的新 lease → 旧工具拒绝、新工具可用。本次没有扩展 RunnerLiveCredentials 的作用域键；它仍按 executionId + stepName（加 credential ref/purpose）共享 handle，不按 parentExecutionId/occurrence 分配新 lease。子工具另行比对宿主调用上下文的 parent/occurrence，但不能据此宣称底层租约已有独立 occurrence generation。

## 主整合最终验证

2026-10-28：主代码和测试代码分开编译通过；子 Agent/真实 HTTP single-dispatch/multimedia 聚焦38项全部通过、0跳过，其中子 Agent及共享网络31项、multimedia 7项。新增Host 4项真实执行通过，包括相同key新lease不能复活旧工具。第一次Host夹具缺agent声明、第二次将release误认为顶层usage，均按现有解析/嵌套event契约修测试，未改生产协议。

该结果覆盖模拟模型与本地HTTP、typed credential IPC；没有实际外部模型调用，不等于可信runtime准入、发布包或生产可用。当前子Agent能力仍关闭。
