# 显式 Agent 子进程工具接线（2026-10-28）

## 当前状态

已接入专用 typed IPC、冻结步骤授权、宿主进程隔离接口与取消/停止链。`shell`、`code_execution`、`git` 和 `tool.agent-sandbox.offline.v1` **均未宣告能力，Admission 也尚未开放这些工具**。Daemon 没有 opt-in 的可信沙箱 provider；尚未执行真实 Linux 隔离与清理验收，不能作为可用功能发布。macOS、Windows 保持拒绝。

本轮没有修改默认工具集的移除决定、`manual_approval.enabled`、线上配置或数据库，没有安装模型或运行沙箱。未提交、推送、部署。

## 引擎接线与授权

- `RunnerHostMain` 只根据已接受的 supervisor `start/resume.authorization.requiredCapabilities` 协商。`RunnerIpc` 要求同一已接收启动帧与完整 binding，并同时核对已宣告的 aggregate、file confinement 及各工具组能力；模型参数、环境变量和 argv 不能开启协议。
- 补充协议固定 SHA-256：`481dab49db82ab3d9d0a457c1f9f678b2c0fd3586c413cc6aadfe674431f7703`。引擎资源和平台合同副本相同。现有绑定的 runtimeHash 和可信 supervisor 管道仍是安装与会话认证边界。
- `sandboxProcessLaunch` 仅引擎发出，`sandboxProcessResult` 仅宿主返回。回包必须关联 pending 请求，未知回包拒绝；完全相同的已处理回包只作幂等去重。requestId 还由执行桥核对。所有请求受现有 business fence、租约和环境门控约束。
- `RunnerWorkflowHost` 通过 `bindArtifactProducer` 保存经过冻结图验证的 invocation。即使没有 artifact/local-model policy，也保留 sandbox 的 `staticInvocations`、`admittedProducers` 和可信 parent。工具入口省略 parent 时，从这份登记补足；自带 parent 不一致则拒绝。
- `RunnerSandboxPolicy` 按实际调用图逐级解析 sub_workflow，核对 identity、已登记的静态父调用和唯一的步骤名。动态工作流、未知路径、sanitize 后冲突、Agent 继承、循环/派生 Agent 作用域一律拒绝。仅接受冻结 `agents.<name>.tools` 或字面量 `overrides.tool_set`，不从 preset 或运行时参数增加权限。
- `RunnerSandboxProcesses` 使用 bytes-only `ToolFileAccess` 读取输入快照和保存输出。不会调用旧 ShellTools/CodeExecutionTools/GitTools，不在宿主预写脚本或临时文件，也不回落到 native executor。旧 processLaunch/processOpen 对 `AGENT_SANDBOX` 标记直接拒绝。

## childExecutionPath 的精确定义

为兼容已有 supplement，字段继续使用 **string**，值是完整、最长 256 UTF-8 字节的 canonical executionId：

```text
execution-fixture
execution-fixture__callingStep__call_1
execution-fixture__callingStep__call_1__nestedStep__call_2
```

root 必须等于本次 binding.executionId；非根路径是实际 WorkflowExecutor 使用的 `__步骤__call_N` 成对结构，最多 16 层。步骤名按执行器规则 sanitize，序号为无前导零的正整数。`root/step/0` 只是旧示例，现已明确拒绝。步骤名本身可以叫 `call_1`，不能按前缀过滤而丢失真实图节点。

Python `frozen_groups(stepName, executionId)` 仍接收同一完整 string。协议先检查 root 与规范格式；可信 provider 必须再依据冻结定义验证图、实际 invocation 和 exact step，不能仅凭格式或客户端声明授权。当前尚未安装该 provider。

## 宿主隔离与生命周期

Linux 需要固定 bwrap/只读 runtime image、完整 namespace 与 seccomp 策略，以及预先委派的 cgroup v2（memory/cpu/pids controllers）。文件只暴露冻结输入和私有 tmpfs 产出，不挂载宿主 HOME、Runner journal、令牌或 socket。默认禁网、明确拒绝 interactive，仅支持本地 Git 操作。

可信 host factory 必须在租约/环境有效后执行固定 live probe：核验 namespace、NoNewPrivs/seccomp/capabilities、网络拒绝、宿主 sentinel 不可读和只读根，再对 setsid/doublefork 后代执行 cgroup.kill 并读取 empty。证明有效期 60 秒，缺失或过期都拒绝启动；不能以“二进制存在”或单元测试替代证明。

进程先 attach/register，写入 durable cgroup 身份，再释放现有 exec 门。取消、租约/环境失效、正常结束和重启恢复均通过宿主 cgroup.kill + empty readback 判断停止。工具自身返回的 JSON 是不可信数据，不能证明清理；无法证明空组时必须保持 STOP_UNCONFIRMED/隔离，不释放容量。专用 worker 每 attempt 最多一个请求，IPC 读取线程继续响应取消。

预算：源码 64 KiB，参数合计 64 KiB，输入和输出各合计 128 KiB、最多 32 文件，stdout/stderr 各 64 KiB。返回文件必须全部声明、唯一、hash/大小正确且清理确认。写入逐个走共享 confinement 配额；不是多文件事务，后续写入失败时步骤失败，但可能保留此前已合法写入的文件。

## 验证与发布前缺口

root 分别完成 `compileKotlin`、`compileTestKotlin`，均通过。已核对 root 本轮 `build/test-results/test` 的真实 XML：`RunnerSandboxProcessesTest` 9、`RunnerSandboxHostTest` 7、`RunnerSandboxIpcTest` 4，合计 **20 项全部通过，0 跳过**；所有 coroutine 测试显式返回 Unit。覆盖真实链接逃逸/配额、typed transport、主流程/真实 child scope、未协商或未冻结工具拒绝、请求/回包绑定、取消与 retained tool 围栏。同批 focused 104 项仍有另一测试组的 1 项失败，不能把本沙箱结果表述为整体回归通过。

Python 既有联合回归 167 项通过；本轮补充 2 项 canonical executionId/冻结 resolver 用例，并修复全量回归暴露的 4 个旧夹具错误：缺少 claim 的 provider 替身、旧分块查询模拟。分块限流测试改用真实 SQLite journal 验证 ACK 和未确认块排空；不弱化业务校验。

本轮两项相关模块加上述联合范围共 **217 tests，0 failures/errors，1 skipped，134.316 秒**。日志：`/tmp/braidrun-sandbox-joint-regression-20261028.log`。5 个修改的 Python 文件通过 3.10 AST 检查；schema 两份字节一致。本次没有重跑全部 994 项，不能把聚焦修复结果表述为全量再次通过。

发布前仍需：真实 Linux 各解释器/image 打包和验收；namespace/seccomp/文件/进程逃逸探针；网络与资源预算；正常/超时/取消/租约/环境失效、host crash 与恢复；所有后代确实清空及不确定时隔离；可信冻结 resolver 和 provider opt-in；runtime/schema 打包验证。全部通过后才能单独评审开放能力和 Admission。
