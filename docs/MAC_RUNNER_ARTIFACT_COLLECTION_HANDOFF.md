# b2a 业务产物 collection barrier：Engine 接线

正式补充：P `b2a0e01b301a76f108f06e2b92e4ba8dfa26e7bd`，collection schema SHA
`26eee80c21e982a56d8d96897ca688c68d628e9935ec0839d34de8d89ddb66fa`。
Base81d/09a3、publication99/d3cb、late CC/7951 原字节均不改。

## 协商、策略及可信上下文

- ready 仅在固定 supplement 可用时广告 `artifact.collection.v1`；必须在本任务
  requiredCapabilities 明确选择后才启用扩展。carrier 存在而能力未协商会启动拒绝；
  缺文件/角色错误/hash/schema/root/声明错误也在业务开始前拒绝。
- 新 artifactCollectRequest/Complete 使用独立严格 supplement validator。
  旧81d仍拒绝新帧；旧任务的 IPC 不启用扩展，未知/额外字段及完整 binding 错误拒绝。
- 只消费 manifest 中 role=runtime 的 `runtime/runner-artifact-policy.v1.json` 原字节。
  policy hash/size 受 bundleHash 约束，在后续请求再次校验；不从 inputs/env/stdout 创建
  声明。校验 declarationId/完整键唯一、明确 run/ 文件、大小、隐藏/越界/规范化/大小写
  别名、file/dir 冲突、实际 code/agent producer 以及 sourcePolicy/null/hash 关系。
- actual child path 按冻结 edge+canonical call_N 成对解析，得到原 childSteps 与真实
  workflow。不能随意剥除 call_*；真实名为 call_1 的调用 step 有单独正常回归。
  无静态 edge 的动态工具调用不会被猜测为静态 child 声明。
- 请求 producer 与实际 interpreter invocation、当前 ACTIVE original start eventId
  及已累计 ACK 的 start eventSeq 匹配。code_end、step_end、cancel、新 occurrence 或
  lease/environment 失效会关闭旧授权。caller 的 producer/hash 不直接构成认可。
  SHA/size 是 S 的测量声明；P 仍须对 ACKed 字节独立全文校验后才可 ACK publication。
  VERIFIED_CURRENT 的 source SHA 只能与 P 已冻结声明一致，独立 attestation 仍由 P
  授权/保留；不是仅凭 caller/JSON 状态升级 source Ready，也不赋予人工 ACCEPTANCE。

## 同一 code 内双 ACK

1. S 按其正式职责保留稳定 intent，读取/安全 snapshot 明确文件，完成 chunks ACK。
2. S 发原 collection request；E 在独立 coroutine pump 中验证，而非阻塞 IPC reader
   或等待中的 code coroutine。E 不读取业务文件、不触发扫描或旧 fingerprinter。
3. E 持久化原 request/身份关联；通过共享 `RunnerEventJournal` 唯一序列写入原 artifact
   事件，replyTo 指向原 request。waiter 在入队前登记，等待 index ACK 在锁外。
4. 收到 replyTo 原 event frameId 的普通 ACK，确认覆盖原 eventSeq且未超已发送序号，
   再核当前策略/occurrence/authority，持久化 Complete 后发送。Complete 不含
   acceptedRevision，也不伪造 P 的索引或人工批准。
5. S 必须核自身 P ACK + E Complete 才放行 helper 的原 first gate。
   duplicate request/event/Complete 保留原 frame/event/publication/artifact/bytes。
   改写重复帧会关闭严格 IPC；没有可伪造的最终远端 verdict。未完成 intent 保留待对账。
6. 步骤完成时所有本 scope 已声明材料必须有原 occurrence 的完成记录，否则拒绝推进
   下一步。S 应在发送 producer exit/closure 前完成声明收集；E 不靠 post-step 读文件补救。

保留7000/7200原 code timeout及既有取消。控制 ACK 等待有界；超时/断线保存原 intent，
不延长原 deadline、不以新 ID 旁路或自动重跑 producer。跨 attempt 恢复仍需原对账授权。

## 动态输入与旧行为

静态 inputs/skills/runtime 复核不放松。新增 source staging 位于正式
`.runner-state/dynamic-inputs/<readRefId>/`，其 SEALED/owner/ref/hash/权限由 S/P 负责。
本次 layout fixture 仅证明该目录不破坏静态清单且 inputs 新增文件依旧拒绝；不冒充
真实 source-stream/receipt 验收。后续仍需与 S 实际 helper、P HTTP、下一 E 操作组合。

旧 b96 注入式 hook 保留兼容，但协商 collection 模式不调用 E fingerprinter；S 独占
业务文件读取及 spool。整个生产 Root/child 同-code已可由正式NDJSON接入，不需私有字段。

## 定向证据与限制

37项相关测试通过（协议6、旧headless10、旧artifact ACK3/正常文件1、policy5、
同-code headless9、event-order3）；latch等待断言强化后单独重验event-order3。
真实 JVM/Bash 同-code夹具覆盖 root/child 声明文件、原7000/7200 timeout、双 ACK 后
才释放下一 gate marker、丢 event ACK/Complete 的原字节重试。负例覆盖错误策略、
未 ACK start、P 全文 hash 拒绝、改写重复帧、取消、关闭的 root occurrence、缺能力、
未收集声明就结束步骤。原序列连贯且不新建 publication effect。

S snapshot/chunks/index/gate 在这些 E 夹具中为受控实现，非 S 真实 kernel peer 或
P HTTP/Mongo 联合验收。现有 P2 的统一写入器修复与3项独立证据见
`MAC_RUNNER_EVENT_ORDER_REVIEW_FIX.md`。无部署、启用、现有 Runner 升级或生产迁移。
