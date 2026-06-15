package com.fartech.agents.agents

import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame

internal data class KoogTokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val source: String
)

internal fun koogTokenUsageFromMetaInfo(
    metaInfo: ResponseMetaInfo,
    source: String
): KoogTokenUsage? {
    val promptTokens = (metaInfo.inputTokensCount ?: 0).coerceAtLeast(0)
    val completionTokens = (metaInfo.outputTokensCount ?: 0).coerceAtLeast(0)
    val totalTokens = (metaInfo.totalTokensCount ?: (promptTokens + completionTokens)).coerceAtLeast(0)
    if (promptTokens <= 0 && completionTokens <= 0 && totalTokens <= 0) {
        return null
    }

    return KoogTokenUsage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens,
        source = source
    )
}

internal fun koogTokenUsageFromStreamFrame(
    frame: StreamFrame,
    source: String = "koog_stream_end"
): KoogTokenUsage? {
    if (frame !is StreamFrame.End) return null
    return koogTokenUsageFromMetaInfo(frame.metaInfo, source)
}
