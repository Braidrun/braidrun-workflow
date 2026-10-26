# R18：Engine 业务产物 collector 最小接入建议

只读定位基线：engine `475722bf930f4c07101314c808e7d937ec28405e`。
本文没有实现 collector、发送 artifact payload 或扫描执行目录；正式字段待 P 冻结。

## 已确认的真实边界

| 入口（当前 engine 文件及行号） | 现状与可收集边界 |
| --- | --- |
| `WorkflowModels.kt:2358` / `WorkflowExecutor.kt:9837` | 公共 StepExecutionResult 和内部 StepResult 均没有业务 artifacts 清单；只有输出字符串/变量/状态，不能照搬 Web 同名概念。 |
| `WorkflowExecutor.kt:2443`、`:8213` | structured_output.write_to 有原声明、模板解析后的目标，persistStructuredOutput 确实写 JSON 文件。候选需再与 P 冻结的输出授权/命名空间匹配，写入成功本身不授予上传权。 |
| `WorkflowExecutor.kt:2450`、`:8303`、`:466` | prompt 原模板提取的预期路径、CLI 声称路径、校验/autoPersist 是落盘检查机制。它们不是 artifact ACL；禁止把日志声称路径或 repair_input/候选文件全部收集。 |
| `WorkflowModels.kt:447` / `WorkflowExecutor.kt:4124` | publish_outputs 明确支持 file/markdown_file/json_file/artifact_ref，但当前仅解析 source 并发布逻辑 value。文件类型可作为冻结声明候选，artifact_ref 必须核已存在的授权归属，不能把字符串当已上传文件。 |
| `WorkflowExecutor.kt:2271` | 正常步骤成功后的 extract/publish 与 result/checkpoint 之间，是汇总当前步骤已声明文件的最小主入口，仍保留真实 workflow/context/step。不能只挂根流程结束。 |
| `WorkflowExecutor.kt:2723` | 状态机子状态直接 executeStepByType，绕过普通步骤完成入口；必须单独接同一内部收集接口，保留 outer.state 身份及 occurrence，不能归并成外层 step。 |
| `WorkflowExecutor.kt:2905` | code 结束只有 stdout/stderr 字符串。脚本实际文件需要 P 冻结的逐步骤清单或指定 manifest；提取到一个 manifest 路径不等于授权了其所有路径。 |
| `RunnerWorkflowHost.kt:185` / `RunnerHostMain.kt:144` | emit 已先 fsync 原事件再发 NDJSON；目前没有业务 artifact producer，最终 E artifact-manifest 为 [] 占位。该占位及进程 capture 均不能证明业务产物完整。 |

`WorkflowHostScope` 已带 rootExecutionId、实际 executionId（含 call_N）、parentExecutionId、
stepName。FrozenWorkflowBundle 持有真实 definition identity/hash，但当前 Entry 为私有；
后续内部接口应由已验证 definition 反查 producer，不能用 workflowName 或 code 参数代替。
重复/循环/repair 会重用 stepName，必须绑定原 producer occurrence 的 durable eventId；
只有 code producer 才有 code_start，Agent/状态机的 occurrence 语义需要 P 一起冻结。

S 现有 Artifacts.stage 只接受显式文件、做受限 regular-file snapshot/full SHA-256，
固定 spool 后上传 chunks；现有自动调用限 result/checkpoint/process-output。
RunnerArtifactReader 是已 ACK capture 的读取 adapter，不是业务文件发现器。
`RunnerEvent.type=artifact` 已在 wire81d 枚举中，payload 目前是泛 object；这不授权 E/S
自行定义其业务语义。chunk hash/size 是分块数据，不能替代 full-file hash/producer/source。

## 建议 P 冻结的最小语义（不是已实施 DTO）

1. 可信声明绑定：完整 binding、冻结 definition/逐 step 输出策略、实际 child invocation、
   producer occurrence 原事件身份、声明的逻辑名称/用途/输出 namespace。区分新生成文件
   与授权的历史 source，源 execution/artifact/hash/owner 只能来自 P 已授权 source tuple。
2. 文件与数据关联：确切 relative logical path、全文件大小/hash、稳定 artifact 身份和
   对应 chunk manifest/receipt。是否必须、失败/取消时是否可保留为部分证据也须明确。
   E 验证声明候选、计算有界完整文件信息并发正式事件；S 对同一文件 snapshot 时按预期
   full hash 复核。文件变化拒绝本次关联，不重跑 producer，不用新值覆盖旧 intent。
3. P 接受条件：原 producer 事件/声明授权成立，且 S 完整 snapshot/chunk ACK 与 full hash
   一致后才开放业务读视图/source export。事件先到、chunk 先到、ACK 丢失都保留原身份；
   metadata 不能被日志、根状态或“文件存在”替代。S spool/ACK 是数据持久证据，E 事件是
   生产归属证据，二者共同完成关联。
4. 收集规则：只接受已冻结且当前步骤明确声明的单文件，或一个被授权 manifest 中逐项
   验证的有限文件清单。没有全目录递归、任意 glob、HOME/Keychain/private cache、任意
   stderr/stdout 路径提取；不把 .runner-private、envspill、技能/输入资产当生成物。

G/M 文件类型包含设计文档、截图/录像、native evidence、源码/交付 archive 等，许多
通过脚本、Agent prompt、stdout 提取的路径/manifest 传递，不能仅靠 structured_output
或 publish_outputs 覆盖。P 必须为原冻结 G40/M30 图提供对应声明授权；缺声明的部分
如实待接，不能把捕获日志上传标成全部业务产物完成。

## 正式 payload 冻结后的最小测试

正常 root/child/状态机明确声明文件形成原 producer 事件＋全文件 hash＋完整 chunks；
同名 step 不同 call_N/occurrence/source 不能混淆。目录中额外普通文件不上传；symlink、
越界、私有路径、manifest 中未授权条目、源历史归属错误均拒绝。采集时文件变化或
hash 不一致不得悄悄换内容；重复 ACK/断线补传沿原 intent，不重复业务执行。
失败/取消只按声明的证据策略保留，不伪造完整源码/截图交付。完整 capture 仍单列诊断。

本项 R18 定位仅做源检查，不跑 collector/native/账号测试；另行的 HTTP pin 验证不属于 R18 验收。契约冻结后才实现相应内部 hook 与事件。
