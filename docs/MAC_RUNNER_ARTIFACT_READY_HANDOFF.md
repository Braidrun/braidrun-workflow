# Agent artifact-ready 与 test-only HTTP 入口交接

正式协议源：`a380c4806ca362f7533d3ac443a4e210d130c969`，独立
`artifact-boundary.schema.json` SHA-256：
`0b92f10b13388180069457ef36e428ab698b25d9d397ca26528674b38919fd34`。
原 81d / 99e55 / cc684 / b2a schema 字节保持不变。生产包同时携带
`protocol/artifact-boundary.schema.json` 和 JAR 内同一 schema；JAR manifest 有独立 pin。

E 在真实 Agent 成功生成并完成持久化后的 completion hook 发出
`event(type=step,payload.kind=artifact_collection_ready)`，绑定完整任务身份及原 producer
start、真实 root/child/state scope、policy hash 和全部适用 declaration IDs。
全部分片持久化后进入原 deadline 下的 collection wait。重复 ready 使用原帧身份；
错误/关闭/动态非静态声明的 producer 不得冒领。Code 沿用原 same-code 收集。

S 必须持续独立泵送并 ACK 原 start，再转发并 ACK 正式 ready，然后才可 snapshot 对应
正式声明文件、完成 chunks ACK、发 artifactCollectRequest，最后核验 P index ACK 与
E artifactCollectComplete。不能先 hold start 再等待文件或 ready；ready 不证明文件
hash、index、source Ready 或人工批准。Agent 收集请求缺少 ready ACK 会被 E 拒绝。

## 测试范围

18 个定向 JUnit 用例通过：HTTP 5、CLI root/child/state 4、schema/分片/重复/关闭 2、
原协议回归 6、test classpath 导出 1。HTTP 用真实解释器 typed JSON 写盘；在实际写前
设置测试暂停，验证文件和 ready 都尚不存在。覆盖写后取消、写前取消以及包含模型耗时
的原 step/global deadline。CLI 用无账户本地 fake CLI 最终写盘。没有重跑已关闭大矩阵。

独立 `http-agent-fixture.jar` 使用正常 WorkflowExecutor、RunnerWorkflowHost、环境与
租约检查和 NDJSON 收集通道；模型 transport 固定为 Ktor MockEngine，凭据固定假值。
生产 RunnerHostMain 和 HTTP SSRF/TLS 校验未修改。测试入口只能在专用测试 runtime
使用，不能替换生产可执行入口或放宽生产包校验。测试类不进入生产 distribution。

## 可执行测试包

以交接 receipt 的固定 sourceCommit 为准，目录包含：

- `http-agent-fixture.jar`：测试入口及 Fixture-Only manifest。
- `engine/`：该提交的完整固定生产 distribution，字节未改。
- `test-lib/`：额外测试依赖；`classpath.txt` 为全部相对依赖路径，不引用 Gradle 缓存。
- `RunnerHttpAgentFixtureMain.kt`、导出脚本、smoke driver、基础协议 fixtures 和 SHA inventory。

Java 21；每次 heavy JVM 运行前先按共享规则获取
`/tmp/braidrun-macrunner-heavy-build.lock`，仅 owner 释放。用以下命令在全新输出目录执行
受控 root+child smoke（先设置 JAVA 为 Java 21 可执行文件的绝对路径）：

```sh
python3 runner_http_agent_smoke.py --fixture-jar "$PWD/http-agent-fixture.jar" \
  --java "$JAVA" --out /absolute/new/evidence-dir --base-fixtures "$PWD/base-fixtures"
```

S 自身测试驱动可在 executionDirectory 作为 cwd 启动：

```sh
"$JAVA" -jar /absolute/harness/http-agent-fixture.jar \
  --binding /absolute/binding.json --pause-dir /absolute/execution/.runner-state/fixture-control
```

省略 `--pause-dir` 即不暂停。启用时，实际 structured_output 写入前生成
`sha256(logicalTarget).waiting`（内容为 logicalTarget）；测试驱动创建同名 `.release`
才释放。此 marker 仅为确定性测试同步点，不能作为收集授权；收集必须等待正式 ready。
stdout 仅 NDJSON，日志在 stderr。恢复 resume 未实现且会拒绝；此入口不是生产 runner。

源码复现：相同已提交源码先运行指定 JUnit（包括 RunnerFixtureClasspathTest），再
installDist，随后用 `src/test/fixtures/export_runner_http_agent_fixture.py`
`--distribution /absolute/fixed/distribution --out /absolute/new/harness` 导出。
Gradle 和 smoke 必须串行占用共享 heavy lock。

独立 smoke 的 E/JVM、解释器、写盘、事件及 ACK/Complete 通道为实际执行；模型服务、
S snapshot/chunk ACK 和 P index/control 服务为受控模拟。真实 S kernel/P HTTP/Mongo
匹配包联验仍归 S/P，不把本证据视为生产验收。未部署、未启用功能、未升级现有 Runner。
