# Runner multimedia 宿主接线（本地，默认关闭）

本轮把已隔离的 `RunnerMultimediaGenerationTools` 接到真实 `RunnerWorkflowHost.agentToolSets` 工厂。原 library/server `MultimediaGenerationTools` 不变。未提交、推送或部署，未调用外部供应商。Gradle 由根任务统一执行；本说明不代表验收通过。

## 启用与授权链

- `braidrun.runner.multimedia.enabled=true` 是可信启动 JVM 属性，只是必要条件。不会读取环境变量，也不会仅因设置属性就广告能力。
- IPC 必须已经接收绑定匹配的 start/resume，且该帧的 `authorization.requiredCapabilities` 与受信 runtime inventory 均包含 `tool.multimedia.v1`、`tool.file_system.v1`、`model.http.v1`。伪造帧、未接受帧或缺少任一项均拒绝。
- 当前 `RunnerHostSession.CAPABILITIES` **不包含** multimedia，`RunnerAdmission` 默认工具白名单也未放开，正式 Main 入口继续关闭。平台 purpose、Python 可信启动配置、runtime manifest 和冻结模板联验后，才能统一开放；不能用测试的显式 capability 集合作为已发布能力证据。
- 凭据请求使用独立 `purpose=multimedia.generate`，不借用 `llm.request`。`credentialGrant.allowedOperations` 必须允许此 purpose，material 必须提供非空 `apiKey` 和显式 `grantedModelIds` 字符串数组。数组最多 128 项，无重复，且必须覆盖冻结的全部模型。当前平台 purpose/模型授权生成尚缺，需后续接线。

## 冻结配置

仅支持精确静态 Agent 步骤及已验证的静态子流程链。工具必须在冻结 Agent 的 `tools` 或 literal `overrides.tool_set` 中显式声明 `multimedia`。旧别名 `multi_media`、preset 注入、循环/派生步骤和子流程 Agent 继承均拒绝。

Agent overrides 示例（模型 ID 必须来自已实现的枚举集合）：

```yaml
tools: [multimedia]
overrides:
  multimedia_default_image_model: google/gemini-3.1-flash-image
  multimedia_api_key: credential:media@1
```

图片或音频至少显式选择一项；音频参数为 `multimedia_default_audio_model`。解析后的三个字段必须与冻结值完全一致。其他 `multimedia_*` 参数、endpoint/provider 选择、模板变量、裸密钥和通用模型凭据回退均拒绝。

真实宿主在 prepare 阶段捕获准确 `RunnerLiveCredentials.Handle`、完整规范 scope、冻结选择和模型授权集合。Agent 参数仅保留 credential 引用，不接收密钥。每次调用和 HTTP 请求检查该捕获对象的代际、当前租约及 purpose、模型授权、静态谱系、冻结文件完整性、环境与终止围栏。重复 prepare 会更换工厂代际，即便仍使用同一有效 handle；step end 后重获相同密钥也无法复活旧工具。调用时还要求相同宿主与完整 scope 上下文。

## IO、生命周期与边界

固定 OpenRouter POST endpoint；每个工具的专用宿主 HTTP 客户端绑定准确租约校验，保留 single-dispatch/one-shot、SSRF/DNS/代理及环境门控。引用图和输出使用 `ToolFileAccess`/`RunnerFileConfinement`。整个调用注册到 `RunnerBusinessFence.operation`；终止会取消在途调用，宿主关闭全部拥有的客户端。取消或失权后不允许写输出或再外发；已发请求的供应商副作用不能撤回，也不会自动重放。

现有适配器的请求、响应、base64、文本、引用图及超时限制继续有效。尚无 `ToolFileAccess` 只验证输出路径而不写入的接口，因此输出围栏会在最终写入时检查；非法输出路径可能在供应商已处理请求后才被拒绝。不能据此重试请求。

## 验证范围

`RunnerMultimediaHostTest` 新增 11 项 mock 宿主边界测试，覆盖 accepted-start/可信开关/能力交集、实际工厂与受限文件写、独立 purpose、过期和缺失模型授权、冻结参数扩权、静态子流程谱系、跨 scope、相同 key/handle 的代际不复活、环境/文件完整性/终止失权、取消后零后续外发及 HTTP 503 不重放。测试全部使用 no-network transport mock；不能替代平台授权联验、正式 runtime 包或真实供应商验收。原 adapter 测试继续覆盖大小、引用图、脱敏与取消边界。
