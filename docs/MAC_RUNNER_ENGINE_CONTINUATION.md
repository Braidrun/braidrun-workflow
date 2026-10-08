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
