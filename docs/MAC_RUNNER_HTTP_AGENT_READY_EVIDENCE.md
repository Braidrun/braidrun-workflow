# HTTP Agent collection-ready 缺口（精确3195源证据）

基线 `3195b4b6e978300d638cd745eed5295bec2b358f`。

- WorkflowExecutor.kt:2453-2472：存在 structured_output 时 promptPersistedFiles 为空；
  externalStructuredOutputPersistedFiles（487-493）仅 Claude/Codex external Agent 返回
  write_to。HTTP Agent 的 expectedPersistedFiles 因此为空。
- WorkflowExecutor.kt:8230-8251：persistStructuredOutput 同步 mkdir/writeText JSON，随后
  返回包含 structured_output_file 的字符串，没有发 typed durable ready event。
- WorkflowExecutor.kt:8337-8342：validatePersistedOutputs 的 checkedPaths 为空即返回，
  不会到8406-8415的 step_output_persistence_verified。该通知只覆盖实际进入并通过
  落盘校验的路径；不能推断普通HTTP typed write_to必有通知。
- 旧通知的 AgentEvent 字段是 type/category/subCategory/summary/detail；detail 为路径
  文本。RunnerWorkflowHost.kt:247以后转发为 RunnerEvent(type=step) 的 agent_event，
  包括 executionId、stepName、嵌套type/category/summary/detail等，不含明确declaration IDs
  或producerStartEventId。它没有成为新collection authority的正式定义。
- 新可信位置应是原 completion hook（Host:216-231）：实际typed写入返回、声明/真实
  scope匹配之后、awaitCompleted之前。此处已有P冻结policy hash/声明IDs、实际producer
  workflow/step/child和原durable start身份；不能从模型stdout或文件轮询重建这些。

基线时待唯一P冻结：通知的正式事件type/payload schema、原occurrence关联、仅当前协商任务
可用的边界、重复ready的原ID/ACK规则、S可开始snapshot所需的durable ACK，以及该通知
是“producer成功完成生成、可尝试明确声明收集”，还是更窄的实际持久化义务证明。
E/S不自行发明字段。通知不是文件hash、chunks ACK、P index、人工批准或source Ready。
S仍只能安全snapshot正式声明文件，再遵守原双ACK与原deadline。


## a380 正式补充已落地

上述行号仅指 3195 基线。现按唯一 P 冻结的
`a380c4806ca362f7533d3ac443a4e210d130c969` 实现
`RunnerEvent(type=step).payload.kind=artifact_collection_ready`。
独立 schema SHA-256：`0b92f10b13388180069457ef36e428ab698b25d9d397ca26528674b38919fd34`。

RunnerWorkflowHost 在真实 Agent completion hook 内验证冻结声明、静态作用域和当前
producer start，持久化全部 ready 分片后才进入原 completion wait。每片最多 64 个声明，
完整 frame 不超过 64 KiB；同一边界重复发送保留原 eventSeq/eventId/frameId。
Agent 收集请求必须在原 start 和对应 ready 均已 ACK 后才可接受。
Code 的原 same-code 收集路径保持原语义。

HTTP typed writer 使用真正 WorkflowExecutor 写盘；CLI root/child/state Agent 使用真实
测试 CLI 最终落盘路径。测试覆盖写前无 ready、写后完成、取消、原 step/global deadline、
分片、重复和错误/已关闭 occurrence。可执行 HTTP 测试入口和受控 S/P driver 只位于
`src/test`，不进入生产 distribution。详见 `MAC_RUNNER_ARTIFACT_READY_HANDOFF.md`。
