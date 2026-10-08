# b96 P2：单一事件写入器及独立回归证据

原审查：`reviews/engine-artifacts-b96c11c0.md`，基线 b96c11c。
旧路径在为 artifact 分配并落盘 n 后释放 host 锁，再写 intent.event，最后才入队；
并行 child 的 monitor 能先发送 n+1。此问题独立于 P/S 接入，不能由下游补序解决。

修复使用 `RunnerEventJournal.append` 统一所有普通/usage/旧 artifact/b2a artifact 的
首次发送：在同一 monitor 内完成 reserve → intent/event fsync → FIFO enqueue。
任何持久化/入队失败锁住后续事件写入，不复用、跳过或补造该序号。原已发送 frame 的
合法重传只复用原内容/ID，不分配新序号。

`RunnerIpc.awaitReply` 先登记原 frameId 的 waiter，再调用写入器。ACK 等待在写入锁外，
因此其他 producer 仍可追加合法后继事件。b2a 重复 request 只会在首次 event 已完成
有序入队后重传；Complete 先持久化再可见/发送。没有禁用原 parallel 或改 eventSeq。

独立测试 `RunnerEventOrderTest`：

1. artifact 在 intent fsync 位置被 latch 固定住；第二 producer 已启动并尝试追加，
   在释放前不能完成/入队。释放后确认为 artifact n、child step n+1，文件序号也一致。
2. waiter 在发送前已登记；第一个 artifact 等 ACK 时第二 producer 可实际入队；之后
   原关联 ACK 完成第一个等待者，证明等待没有占住写入锁。
3. fsync 失败后后继事件不再发送，保留原未完成序号，不能制造一个缺口后继续。

该3项是独立源码/行为回归，随当前 b2a 提交单独归档 XML、日志及源文件哈希。
37项相关协议/头部/collection/旧artifact回归已通过；latch等待断言强化后又单独运行
这3项。均不构成真实 P/S 并行业务或生产验收，独立复核仍由父会话进行。
