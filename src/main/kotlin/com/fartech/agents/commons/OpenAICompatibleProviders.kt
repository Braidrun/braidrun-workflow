package com.fartech.agents.commons

import ai.koog.prompt.llm.LLMProvider

internal val QWEN_DIRECT_LLM_PROVIDER = LLMProvider("qwen_direct", "Qwen Direct")
internal val KIMI_LLM_PROVIDER = LLMProvider("kimi", "Kimi")
internal val LMSTUDIO_LLM_PROVIDER = LLMProvider("lmstudio", "LM Studio")
internal val ZAI_LLM_PROVIDER = LLMProvider("zai", "Z.ai")
internal val NVIDIA_LLM_PROVIDER = LLMProvider("nvidia", "NVIDIA NIM")

internal fun openAICompatibleProviderIdentity(providerKey: String): LLMProvider? = when (providerKey.lowercase()) {
    "qwen_direct", "dashscope" -> QWEN_DIRECT_LLM_PROVIDER
    "kimi", "moonshot" -> KIMI_LLM_PROVIDER
    "minimax" -> LLMProvider.MiniMax
    "lmstudio", "lm-studio", "lm_studio" -> LMSTUDIO_LLM_PROVIDER
    "zai", "z.ai", "z_ai", "z-ai", "zhipuai", "zhipu_ai" -> ZAI_LLM_PROVIDER
    "nvidia", "nvidia_nim", "nvidia-nim", "nim", "nvidia_build", "nvidia-build" -> NVIDIA_LLM_PROVIDER
    else -> null
}

internal fun isOpenAICompatibleProviderIdentity(provider: LLMProvider): Boolean =
    provider == LLMProvider.OpenAI ||
        provider == QWEN_DIRECT_LLM_PROVIDER ||
        provider == KIMI_LLM_PROVIDER ||
        provider == LLMProvider.MiniMax ||
        provider == LMSTUDIO_LLM_PROVIDER ||
        provider == ZAI_LLM_PROVIDER ||
        provider == NVIDIA_LLM_PROVIDER
