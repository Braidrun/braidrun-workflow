# Engine 接续：资产、原生文本技能及受限 callback

基线 `545e4fe86926f6266ebfb422b73f8c72796540de`，本聊天独立 managed worktree。
仅修改 engine；平台仍是唯一跨仓集成人。没有部署、启用、升级现有 Runner 或迁移生产。

## 本批接线

- R02：启动及每次宿主业务操作重新验证所有声明资产的大小/哈希，并检查
  `.runtime/project`、`inputs`、`skills` 的封闭清单。拒绝私有命名空间、非规范路径、
  大小写/Unicode 别名、缺失/变更/额外文件及符号链接。`run/` 输出可写，不纳入封闭清单。
  `bundle.portableAssets.v1` 现在代表这条校验路径；实际复制、只读封装、依赖收据仍归 S。
- R03/R04：按当前子执行/步骤保存已规范化技能选择，校验冻结 `skills/<name>/SKILL.md`，
  通过 stdin 给 Codex/Claude 提供有界文本；保留白名单/黑名单和关闭技能语义。
  不创建 CLI 自发现目录，不复制登录 HOME，不启动 hook/registry/MCP。
  普通 CLI/library 默认行为不变。scoped subscription ref 的授权/注入仍归 P。
- R05：声明既有正式 `control.callback.v1` adapter；只有本次任务 authorization 明确要求
  此项才给代码步骤发 token。root 使用官方五项操作，child 仅申请 output.read、
  output.publish、interaction.request，避免申请尚无 child ACL 的 artifact 操作。
  保留真实 child/call_N 路径、全 binding、关联应答、到期检查和内存秘密脱敏。
- R11：JVM HTTP 在每次请求前检查 `jvm-http` 路径；不能由其他客户端的观测授权。
  CN 和 ISO 国家表之外的未知代码（例如 ZZ/XX）阻断。
  engine **不声明**覆盖所有子进程客户端的 `network.http.v1`。

## 定向证据与边界

最终 5 类 / 14 tests 全通过，零失败/错误/跳过：新资产/callback/未知出口 8，
订阅 2，原生技能策略 1，真实 headless JVM 2，模型 host 回归 1。
初轮一个 callback coroutine 因非 void 返回未被 JUnit 收录，已修为 runBlocking<Unit>，
最终 XML 确认该 root/child 测试执行通过；不把未收录的方法计作通过。
实际本地 bash fake CLI 消费冻结技能 stdin、检查隔离 HOME/CODEX_HOME、原子更新假 auth，
经 engine 既有租约/CAS 路径回写并清理。CLI subprocess executor/授权/出口是受控夹具，
不是实际 Python supervisor/HTTP/Mongo/真实账号验收。

正常冻结脚本确实在本机执行；asset 修改、缺失、未声明 import、别名、symlink 被拒。
callback 正常 root/child IPC grant 验证通过；没有调用真实账号或公网模型。
两种真实 headless JVM 路径（未授权不发 token / 授权时在代码启动前发 token）均通过；
新 installDist 成功，协议 schema 哈希保持 09a3c3f6，分发包详细记录见 coordination。
重构建使用共享原子 mkdir 锁，PID 写 owner.json，仅本进程释放。

## P/S 接口需求（仍未完成）

1. 正式 wire 固定 81d611a6 / hash 09a3c3f6；没有新增 schema 字段或私有 HTTP/IPC。
   callback 正式契约 README 和 RunnerWorkflowService 实际只有五项操作，TTL/budget <=3600。
   engine 保留 60 秒交付余量，单次 budget <=3540。7000/7200 秒整步 token 仍不受支持。
   请 P 定义 purpose 到 App Factory identity/file-gate/feedback/source/locale 的窄映射，
   并冻结续期或 supervisor 在实际控制调用时领取短 grant 的正式接口。旧
   `workflow:artifact-callback`、普通 renew、业务 broker 不能当作 runner_control。
   当前 runner_control 没有授权续期、凭据、审批决定或 OAuth 路由；不能擅自添加操作。
2. P planner 对任意文本包含 `http` 就要求 `network.http.v1`，其含义比 JVM model adapter
   更广，也会匹配文档 URL。需要 P/S 明确按实际客户端的能力/依赖收据（JVM、Python
   urllib/requests、curl、Node/browser、CLI）。不能通过增加字符串把这些全部标为支持。
   现有 subprocess/agent-cli 路径门继续使用，真实客户端公网出口仍未验。
3. P 注入 scoped subscription references 和对应 child ACL，以及已授权 resource/input
   closure。S 负责受监督 launch、封装只读目录和实际 Python/Node/CLI 依赖。
   engine 哈希复核不是 OS 文件系统沙箱，也不能单独保证运行中不受任意同 UID 写者修改。
4. callbackRequired 是本次任务的授权，不能仅由 engine ready 能力自动扩大。原 G/M
   长步/domain 回调迁移、全部能力接线与真实 matching-package 原流程验收仍由 P 集成。

测试、scoped commit、installDist、跨仓包、真实账号/原流程验收是不同事实。
本批不能计作 R02/R03/R04/R05/R11 的完整业务验收，更不能计作完整工作流交付。

## 对齐 S 最新依赖收据

已只读核对 S `a5d8478c8113ea9c95e01849b2f622132db6756a` 实现和 `10336f124` 交接。
其实际 E545 matching package、原 G40/M30 prepare F0、封装 Python/Node/render providers
均有独立收据；F0 身份桥输出仍为 unavailable。S 的 Python urllib / Node https
IPv4 JP 观测只属于这两个客户端，不能移用为 JVM/CLI/MCP/IPv6 证明。
本 engine 变更后需 P/S 重新组装对应新 SHA，既有 E545 包不能被称为包含本批接线。

## 第二批：JVM HTTP 只读探针

`RunnerHttpProbeKt` 是无参数本地诊断入口，固定 GET `https://api.country.is/`，
不接受 URL/头/body/账号参数。与业务 `RunnerHttpClients` 共用 OkHttp direct/TLS/
SSRF DNS/no-redirect/no-retry 配置；拒绝代理配置冲突，5 秒连接/读取、15 秒总时限，
响应最多 4096 bytes。检查 IP literal/public 地址及国家码；CN/未知明确阻断。
输出是本地诊断收据，始终 `accepted:false`，不是正式环境授权或新增 wire DTO。
它不证明特定模型目的地、强制 IPv4/IPv6 双栈、CLI/MCP 或任意子进程网络。

运行方式（从本分发目录）：

```sh
java -cp 'runner-host.jar:lib/*' com.fartech.agents.runner.RunnerHttpProbeKt
```

本批 3 项 probe 单测 + 1 项模型 host 回归通过，新 installDist 通过。
两次实际只读探测均 exit 2 / BLOCK_UNKNOWN；第二次 2026-10-08 23:11:10 上海时间
记录异常类别 IllegalArgumentException，无账户/原始配置异常消息落盘。
同时独立 DNS 只读检查得到 `api.country.is -> 198.18.21.204` 保留地址，代理环境变量
未配置。这是阻断/诊断证据，不能作为非 CN 放行收据，也不以 Python/Node 的 JP 结果
覆盖 JVM 失败。本批未修改网络、代理、DNS、系统语言；初次失败收据保留。
P/S 可使用此受限工具获得自身绑定的 fresh probe，须自行验证确切 runtime/profile，
不能将该诊断 JSON 直接当成环境授权。`network.http.v1` 继续不作笼统广告。

## 固定批次证据与最新只读诊断

证据根目录：`/Users/liguoliang/Documents/Codex/2026-10-08/mac-runner-development/engine-continuation-evidence/batches/`。
每批 `<完整 SHA>/receipt.json` 记录原日志/XML/source.tar 的大小和 SHA-256。

- `c69ff8a4441dea74f255fc329678bf8c21967f3c`：原 runner-host.jar 哈希
  `05cfe4a67437c81338f00d98eeaa019344e550e4fcdcf9de74bfe3ccf262c627`。
  原整套分发目录没有单独保存；`distributionAvailable=false`，使用时须由 P 按该源码
  重建并记录新二进制哈希。现有 213 包绝不代表这份历史包。
- `21350e3a5864bcda5e629dfd147476d1af8df23b`：`distribution/` 已保存只读快照，
  257 个文件逐项核对复制前、复制后、源目录哈希一致；JAR 哈希
  `8919106a1fa431354cd4d1e4c7b1aebfc97077cc3f9b38b4ec39e920c356d375`。
  快照包含固定源码归档、测试、初次/后续 blocked 观测和 manifest，后续构建不覆盖。

2026-10-08 23:18:04 上海时间，使用固定 213 分发包只读诊断：首个拒绝位置是
`RunnerHttpClients.requireDirect`，`http.proxyHost`、`https.proxyHost`、`socksProxyHost`
三个 JVM 属性已配置；`java.net.useSystemProxies` 为 null，代理环境变量未配置。
仅记录属性名和是否配置，没有输出代理地址或认证值。该次检查尚未发出 HTTP 请求；
`198.18.21.204` DNS 结果属于独立观测，不能当作这次异常的已证实触发点。
保持 BLOCK_UNKNOWN / accepted=false；未修改任何 JVM/网络/代理/trust 设置。
诊断入口源码和输出另行保留，不参与生产 runtime 或正式协议。

最新 S 资源实现已只读对齐 `4115007571edf0128f45d3303c61f8c53bdcadcb`，
证据提交 `d6204ee99125d8ec9360be3a2bcb6d2098f997da`。其 E545/411 F0/资源结果
与本 engine 213 的能力及 JVM 阻断证据继续分开；平台拥有最终 bootstrap 接入与验收。

## 长 code 逐调用晚领取：只读改造方案（待 P 正式契约）

统筹已选择由 S 在每次控制调用前领取短 purpose grant。本节仅列调用点/验证设计，
本批不改 runtime、schema、超时或 operationBudget，不预造 provider URL/IPC 字段。

| Engine 调用点 | 契约冻结后的最小变更 |
| --- | --- |
| `WorkflowExecutor.kt:823` | 将 host 的逐调用控制交付模式与旧 executionApiTokenProvider 分开；模式由 P 冻结的授权/能力决定。 |
| `WorkflowExecutor.kt:2849` | 在环境 spill/脚本生成前清除旧 WF_API_TOKEN；不能依赖“provider 非空”才清除。 |
| `WorkflowExecutor.kt:2905` | 旧 freshCodeStepCallbackEnvironment 留给 legacy；新 Runner 模式不以整步 timeout mint。 |
| `WorkflowExecutor.kt:3041` | 新模式取消静态 token 的 environmentAtStart；ExecRequest.timeoutSeconds 保持 7000/7200 原值。 |
| `WorkflowExecutor.kt:3430` | 防止 execution_api_token 参数及最终 overlay 重新把 token 写入代码环境/临时文件。 |
| `WorkflowHost.kt:36`、`RunnerWorkflowHost.kt:214` | 用正式宿主交付模式组合 adapter，不因为缺失 provider 回退旧 token。 |
| `RunnerProcesses.kt:44` | 保留 preparedForStart、环境门及真实进程登记；S 在 frozen code_step_started/peer 校验后注入其正式局部 provider 环境。 |

正式契约仍需 P 冻结：purpose 与原域操作的映射、step/child ACL、原 deadline、
lease/environment、撤销、每次调用的稳定身份与幂等收据、grant 回复丢失语义。
Engine 不拥有审批、业务 HTTP/ACL 或 S 的 kernel peer 注册；旧 Python 控制调用迁移
由 P/S 按正式接口完成。不能将 `control.callback.v1` 的现有五项自动扩大。

待正式接口后的正常验证：root 声明 7000 秒、child 声明 7200 秒但实际脚本快速完成；
启动不发 callbackTokenRequest、不携带 WF_API_TOKEN；首次真实控制调用才取得短 grant。
两个 call_N 的 step/child 授权分离；取消/失效/过 deadline 后不能再取 grant。
丢失回复保留原调用身份，不重放业务；原超时不被截短。旧 SERVER/legacy 保持兼容。
这些是未来验收设计，目前没有执行或宣称新模式可用。

## 本批定向测试边界

普通 fake Claude 真实本地进程覆盖 root 及静态 child：分别选择冻结文本技能，stdin
包含己方 skill、没有另一方 skill；HOME/CLAUDE_CONFIG_DIR 独立，授权 IPC 保留
`nested/call_1`，假 subscription token 只在正确调用出现。返回值来自实际子进程 stdout。
CLI、授权、网络均为无账号夹具；不代表真实 Claude、Python peer 或平台 HTTP 验收。

callback 回显用真实 headless JVM/注册 Bash，root 和 child 均实际向 stdout/stderr
回显假 token：事件脱敏、持久化文件无 token；含秘密业务状态被 checkpoint 拒绝，
最终为 FAILED，不能声称成功恢复。child 失败可能不把其输出复制到 root result，
因此验证真实脱敏事件及最终文件无秘密，不要求 root 伪造一个 redacted 输出。
正常 Claude 完成用例与秘密回显拒绝用例分开。没有修改 checkpoint 防线来让测试成功。

同时为继承的 subscription coroutine 测试显式指定 `runBlocking<Unit>`，确保其中
短租约到期/迟到续租不能复活的测试被 JUnit 实际收录。原批次通过数量不追溯增加。
最初两轮测试输出保留在 engine-continuation-evidence/child-claude-initial、
child-claude-second；最终测试结果和本批源码 SHA 见独立批次收据及 coordination。

最终定向结果：3 类 / 10 tests，零失败/错误/跳过。
Headless 5（无 callback、正常 root/child callback、root/child 秘密回显拒绝）；
subscription 3（包含实际收录的短租约到期/迟到续租保护）；Claude root/child 与回显 2。
未全面重跑；没有 main/runtime/schema 代码改动，没有新 installDist。
固定 213 分发包继续对应 213 运行时代码，不能重新贴成本次测试/文档提交生成的包。

## 内部逐调用模式接口已实现（b15 正式 HTTP 契约，selector 待提交）

已读取 P 提交 `b15fb2d49a630b888075427b7c4fde78ce492e2a` 的正式 HTTP supplement。
`WorkflowCodeControlMode.SUPERVISOR_PER_CALL` 仅为可信宿主内部枚举，没有序列化成 wire。
`WorkflowHost.prepareCodeStepEnvironment(scope, timeoutSeconds)` 在具体 executor 的
preparedForStart 时运行；Runner 实现复核原 root/child/step、完整超时、环境和冻结资产，
只返回非秘密元数据（当前为空），由 S 在正式注册/peer 路径注入 provider 环境。

模式启用的内部 host 不提供 callbackToken，也拒绝 callbackTokenFor。解释器忽略旧
executionApiTokenProvider；清除 base/agent 参数、root/input/restore/static child 变量、
最终环境及准备回调中的保留 callback 字段（含 WF_VAR_*、*_FILE 和结构化 JSON）。
清除发生在 env spill/临时脚本生成之前，动态 child 通过同一可信 host 继承该模式。
不修改冻结脚本源码，不声称能够识别任意别名或任意硬编码秘密；源内容仍须由准入/
secret boundary 校验，S 仍负责其进程最终环境与 kernel peer 授权。

本批 4 个定向选择 / 13 tests，零失败/错误/跳过：新模式 4、旧 SERVER callback 3、
既有 headless 5、实际 Runner 内部模式准备门 1。正常 root 7000、静态 child 7200、
动态 child 7200 的代码步骤均实际运行短 Bash 并完成；只是声明超时保持不变，未进行
两小时耐久运行。大环境变量实际触发 spill，临时脚本/文件/环境无测试旧 token。
prepare 在资源等待后调用；资源等待期间取消不 prepare/launch；启动后取消确认实际
本地进程退出。Runner 准备门对跨 root execution 和失效 gate 拒绝。未运行 P HTTP。

P 工作树新增的 `control.perCall.v1` selector/严格 arguments 表当前尚未在 b15 提交中，
因此本批没有修改 RunnerHostSession 的外部协商或 ready 能力字符串，也没有复制私有
schema。默认仍为原模式；正式 selector 提交后才能接入 mutually-exclusive 校验和
真实 start/root/child 新模式。原 NDJSON81d 与哈希完全不变，不缩短 code timeout/budget。
本批 main 源码已变化，但没有重复 installDist；固定213包仍是旧213实现。集成人应
按本次源码 SHA 构建，不能将旧分发包标为包含内部新模式。

## cdc4 正式 selector / NDJSON 接线与 8fa P1 修复

P 正式提交 `cdc4f7aea47e282336dce46548a34a716b967c88` 已接入。
HTTP supplement 原字节嵌入 engine resource，SHA-256
`13e3d91d1e582253a9f56d5e6de8aae5eac3ee574fd152ea3abec3c336a6b892`；单独 origin 文件
和 Runner-Late-Control-* JAR 头记录该语义版本。原 NDJSON81d/schema09a3c3f6 未改变。

RunnerHostSession 在业务环境事件/资产执行前解析 requiredCapabilities：
`control.perCall.v1` 选择内部逐调用模式，与 `control.callback.v1` 互斥。
ready 只有在本机嵌入补充 schema 哈希精确匹配时才广告 perCall。
固定拒绝原因码区分 REQUIRED_CAPABILITY_UNAVAILABLE、CONTROL_MODE_CONFLICT 和
CONTROL_SUPPLEMENT_UNAVAILABLE（均以 RUNNER_ 为前缀，不含输入/秘密）。
没有 selector 不会隐式启用 perCall；不需要此控制的旧任务保持原行为。
需要 perCall 的任务缺 selector/具体 provider/补充契约，仍由 S 在 JVM 启动前做正式
provider 准入；E 不编造一个未定义的 provider-ready 字段或代替 S kernel peer 证明。
S 当前对应实现仍在其自有分支开发，本批不把只读看到的 WIP 当已完成集成验收。

本机真实 NDJSON/JVM/Bash 夹具验证：perCall root 保留 7000 秒、child 保留 7200 秒，
实际执行短脚本并完成；短 code 同样不 mint/inject 静态 token。互斥配置、隔离测试资源
缺少补充 schema 时均在任何业务事件/进程之前拒绝。负例仅使用独立测试资源副本，
没有修改真实 JAR/资源目录。原始 code_start 所属 RunnerEvent.eventId 在本机事件文件
持久化后发送，值与 IPC frameId 不同，原 child invocation/step 均保留；测试核对逐字节
落盘 frame 和启动顺序。S 必须取得 P 的原事件持久 ACK 才发 grant，本批只模拟 ACK，
没有把本地 durable 文件冒充平台 HTTP/Mongo 持久化。

8fa 限定复核 P1 已修复：reserved() 先剥离真实 `__FILE` 再处理兼容 `_FILE`，覆盖
WF_API_TOKEN__FILE、WF_VAR_WF_API_TOKEN__FILE、WF_VAR_EXECUTION_API_TOKEN__FILE。
测试在参数、root/input/static/dynamic child 变量、final overlay 和 prepare 返回值中
注入指向可读假 token 文件的真实别名。root/static/dynamic 的普通大业务变量都实际
spill，Bash 桥之后无 callback authority；Python/Node 实际桥读取大业务值正常、无法
恢复旧 token。没有关闭普通 spill，没有裁剪 timeout，也未修改 SERVER legacy 路径。
复核原文：coordination 同级 `reviews/engine-percall-8fa0dd9a.md`。

最终 4 类 / 22 tests 全通过，零失败/错误/跳过：headless 10、negotiation 3、
late-code/spill/cancel 6、legacy callback 3。仅定向测试，不全面重跑。
新源码需新 installDist；新包/hash 与固定源码由独立批次 receipt 和 coordination 记录。
原213不含8fa/cdc4接线，不能复用其哈希宣称本批分发完成。无部署/启用/存量升级。
