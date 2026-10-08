# ea441 collection 三项 P2 修复

复核基线：`ea44188851a3d721e2a9448a0950bc6bbf72b415`。
原报告：coordination 同级 `reviews/engine-collection-ea441888.md`。
原 event 越序 P2 已由父复核关闭，本批不重开、不修改其 journal。

## 1. 无适用声明的动态调用

WorkflowExecutor 在真实 invocation 绑定时传递内部 staticInvocation 标志；动态 tool
入口为 false，静态子调用继承父 lineage。该标志不来自 workflow 参数、env 或 wire，
同 invocation 不能修改分类。成功 completion hook 对已知动态 lineage 不套用静态
artifact policy；静态声明查询先检查 workflow/step 是否有候选，再解析静态路径。

入站 collection 仍走原严格 graph/ordinal/current occurrence 校验，并要求真实静态
lineage，不能把动态 ID 猜成或剥成静态路径。正常无声明动态 code 可结束，不广告其
具有声明产物权限。实际 WorkflowTools.executeWorkflow fixture 覆盖 root-only policy，
以及同一 child 在别的静态 lineage 有声明但当前动态 invocation 不适用两种情况。

## 2. 原 occurrence 唯一 intent

增加 `(完整 binding, declarationId, producerStartEventId)` 到原 request 身份的内存
保留，以及同语义键到原 requestId/requestHash 的原子持久链接。语义链接在 publication/
artifact 链接、请求记录和 event 分配之前检查并 fsync；并发请求共用原锁。
同 declaration/start 换 frame/request/publication/artifact 全部 IDs 仍冲突，不能分配
第二个 event。原 IDs/metadata 的合法重试保留原行为，取消不删除旧 evidence。

真实 headless fixture 先产生一次原事件/模拟索引效果，hold 原 ACK，再发送全新 IDs，
验证仅一条 artifact 事件、一个索引 effect、无 Complete/放行。另在未 ACK 时取消并
重建组件，新的 IDs 被磁盘语义绑定拒绝，原文件哈希不变且没有新事件。后者仅验证
组件 ledger 重建，不授权生产执行以同 binding 重启，也不代表生产恢复已验收。

## 3. HTTP Agent 的 suspend completion

Agent completion hook 等待本 declaration/original occurrence 的 Complete；独立
request/ACK pump 继续推进，不占用事件锁。真正没有材料会在有界等待后拒绝。
code 已关闭却缺材料仍立即拒绝，因为其新 collection 已不合法；S 可以且应在发送
processExited 之前完成 code 收集，不能让此分支延后关闭 scope。

原 step/global active-execution 预算通过内部 coroutine context 传入完成等待，仍扣除
已有 resource-admission/人工审批规则允许的等待。step 计时从原执行开始、包含模型和
write_to 已用时间；不会写完文件后重新给一份 timeout。嵌套调用继承原预算；无配置
预算时 completion 使用本地30秒有界等待。此上下文不是新的协议 deadline。

受控 HTTP model（MockEngine，无真实账号/网络）实际执行 typed structured output，
由 E persistStructuredOutput 真正写出 JSON。S 夹具在观察文件后刻意延迟，E 保持
等待，收到原 artifact/index ACK/Complete 后才完成。另测取消、step/global 原3秒
超时；900ms 模型调用计入该原预算，耗时在约3秒内结束，没有重置到写后3秒。

## 证据与边界

本批5类/25 tests，0失败/错误/跳过：collection headless10、policy5、review3、HTTP
completion4、legacy resource-wait/callback3。未重跑旧已关闭大矩阵。

wire81d、publication99、lateCC、collectionb2 原schema字节/来源不改；不增加私有字段、
不放宽静态 inputs，也不扫描目录。新源码需独立构建/保存包，ea441 固定包原样保留。
实际 S kernel peer、P HTTP/Mongo、原G/M联合验收仍由集成人负责，当前没有部署或启用。
