# 99e55 业务产物事件：Engine 内部接线交接

正式来源：P `99e55c1815ea332cd2dccd955279b938e51fc495`，artifact-publication schema
SHA-256 `d3cbb764d096ca9a68ba5a74adafe543a668f79e6273a65287acad3aaff9af09`。
资源为精确 Git 字节，独立 origin/JAR manifest 记录；NDJSON81d、late CC/7951 不变。

## 已实现

- `WorkflowHost` 增加内部 producer 绑定及 collectDeclaredArtifacts 完成屏障。
  producer 在实际 root/child invocation 建立后绑定冻结 workflow ID，不能把
  `admit(child, parentScope)` 的预检上下文当作真实子执行。动态入口也经 execute 绑定。
- 解释器在普通成功步骤输出提取后、publish_outputs/完成/checkpoint 前调用屏障；
  状态机内层直接执行路径也调用，保留 `outer.state`。外层不自动继承子步骤产物归属。
- `structured_output.write_to` 携带实际解析后的候选路径；file/markdown_file/json_file
  publish_outputs 携带其声明候选。原始 code stdout、CLI 声称路径、artifact_ref 字符串、
  capture 和整目录不是候选清单。code 文件由可信逐步骤策略明确提供。
- `RunnerArtifactPolicy` 和 `RunnerArtifactFingerprinter` 是内部可信构造器接口。
  策略未组装时不收集；策略存在但 reader 缺失会拒绝。reader 必须是安全、有界、
  descriptor-confined 的实际完整文件测量器，不能由模型 JSON/路径计算伪 hash。
  **生产 policy/reader 尚未组装，也没有私自增添 bundle 字段或能力广告。**
- 原始 monitored step_started（Agent）或 code_step_started（code）提供 durable
  producerStartEventId。事件保留实际 child/call_N、producerWorkflowId/Step、full binding。
  未组装独立 source attestor 时 `codeSourceSha256=null`，从不拿 definitionHash 替代。
- publicationId/artifactId 根据完整绑定、真实 producer occurrence 和声明逻辑路径固定。
  intent 在 fingerprint 前 fsync；完整文件 size/hash 后才分配原 artifact event 的
  eventSeq/eventId/frameId 并 fsync。使用既有 event + `ack(replyTo=原frameId)`；未关联的
  累计 ACK、低于原 eventSeq 的 ACK 均不能放行。对有界完整大小/路径/秘密/归属复核。
- 当前进程已确认的重复声明不重采集/重分配事件；同 intent 改内容/上下文会冲突。
  已保存原事件的重用不能改成 log 或换 producer。没有完整原事件的中断 intent 要求
  对账；超时/错误/取消保持未确认并丢失业务权限，不自动重跑 producer。

## E/S/P 顺序（必须共同实现；本批 S/P 为受控夹具）

1. P 冻结授权声明；E 建立真实 producer occurrence、持久化原 publication intent。
2. 可信 reader 测量明确文件的完整信息；E 持久化并发送原 type=artifact 事件。
3. S **先**按该 artifactId/logicalPath/expected full hash 安全 snapshot，完成全部 chunks
   ACK；再原样转发原 eventSeq/eventId/binding。不能重编号、改 payload 或造一个新 E 事件。
4. P 独立按全文字节、frozen policy/source/producer 校验并完成 publication/index CAS；
   S 仅在该结果成立后发送关联 ACK。E 检查 ACK 与当前 authority 后才继续后续 gate。
5. 断线/ACK 丢失保留原事件与 intent。跨 attempt/恢复/source 关联由 P 对账规则处理，
   本批不提供自动旧 scope 复活或 producer 重放。

## 必须由 P 补齐的最小正式约定

- **声明策略载体**：请冻结 exact workflow/step/文件或明确 manifest 成员、namespace、
  最大大小/必需性、source 要求及其认证绑定。可考虑既有 bundle.files 中规范策略文件
  及其 schema/整体 bundleHash 绑定；文件名/格式由 P 定义，E 不自行选择私有路径。
- **安全测量/快照接入**：E 生成正式事件之前需要真实完整文件 size/hash。当前内部 reader
  仅在 fixture 以可信临时文件提供测量；生产 descriptor-confined reader/适配仍须接入。
  S 必须再次安全 snapshot 并与该 full hash 对齐，任何变动明确拒绝，不能换内容。
- **同一 code 内 first-gate**：当前入站 NDJSON 没有 S→E 的声明收集请求。
  若 code 正在等待本地 peer 的 gate.submit，事后 step-completion hook 永远晚于该 gate。
  需要正式绑定原 producer/child/step/occurrence 与已授权声明身份的 collection command
  及关联/完成语义，或 P 批准的等价通道。E 仍是事件序列唯一写者；S 必须能持续读 ACK，
  不能在 IPC reader 线程同步等待导致死锁。禁止 stdout/env 私有控制或 S 伪造 E seq。
- Agent retry/state occurrence 的原 start 语义及失败/取消的部分证据政策也需冻结；目前只
  在成功边界收集，不将未声明失败文件或旧 source 自动升格为可读材料。

## 定向验证与未验范围

21 tests 全通过（6 类，零失败/错误/跳过），没有全面重跑：root/child 实际 Bash 文件
生成及显式策略；普通累计 ACK 不放行、两次 index ACK 后才到 synthetic approval；
原 durable event 身份、全文 hash 与 child producer 匹配；重复确认不重读/分配，改 intent/
低 ACK/私有路径/超预算/恢复事件改成 log 被拒；file 输出及状态机内层边界；真实本地 fake
Claude 的 root/child structured write_to 文件与原 Agent start 事件；既有 headless/IPC 回归。

实际文件与子进程是真实本地 fixture；声明策略、fingerprinter 和 S chunks/P index ACK
是受控注入，未证明生产 descriptor 安全快照或真实 P HTTP/Mongo/gate/source export。
初轮 producer 预检误绑父 scope 的回归已修为独立实际 invocation 绑定，最终回归通过。
尚未启用生产 collector、没有自动发现业务目录、没有发布新运行时或部署。

## 与 R19 动态 source 输入分开

S404 的 source 流落在 inputs/.control-streams 会违反 E 静态 inputs 封闭清单；P/S 已在
定义独立动态 staging/受信 readRef receipt 边界。此项未在本批修复，也没有忽略目录、
放松 verifyPortableAssets 或增加动态白名单。后续必须做 sealed bundle + 正常 source
流 + 下一次 E 业务操作的组合验收，不能以独立下载成功或 artifact ACK 代替。

当前内部 publisher 每个成功边界的同 producer occurrence/逻辑路径只创建一次原 intent。
重复调用取回该原声明结果，不把已确认文件的后来变动偷偷当作新内容。今后同一 code
内重复修改/发布同一路径，须由正式 collection 请求定义新的原 intent 身份；当前
post-step 接口不声称覆盖这类多次发布。ACK 恢复不重读/替换原已确认快照。
