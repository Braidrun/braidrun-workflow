# 引擎候选逐类 JUnit 回归结果

状态：79 / 79 个选定测试类、600 项全部通过；failures / errors / skipped 均为 0，最终退出码 0。

本轮从此前 36 类、227 项续跑，新增 43 类、373 项。中途发现 simctl 活动，资源守卫退出 75，保留当时已通过的 44 类、274 项；再次确认空闲后用同一命令续跑成功。未停止共享进程或 Mac 作业。

输入 fingerprint：`bcccd9e2e8bb1edd5fab9f1be0bd1cc2a77c260e0c2293b25f306df284a29d29`，结束复核未改变。本轮复用已有编译类与 launcher，没有运行 Gradle 或重新编译，也未修改源码、提交、推送或生产写入。

对应回执：`direct-junit-runner/summary.json` 与各类 XML；独立汇总 `ENGINE_JUNIT_COMPLETION.json`。验证 JVM 已退出，资源窗口已交回父任务。

本地模型相关选定测试：

| 测试类 | 测试数 | 结果 |
| --- | ---: | --- |
| `RunnerLocalModelHostTest` | 9 | 通过 |
| `RunnerLocalModelIpcTest` | 4 | 通过 |
| `RunnerLocalModelPolicyTest` | 6 | 通过 |
| `RunnerLocalModelPromptExecutorTest` | 15 | 通过 |
| `RunnerLocalModelTest` | 13 | 通过 |

这是选定引擎测试的结果，不等同于平台全量回归、生产 Runner 接入或真实本地模型性能验收。
