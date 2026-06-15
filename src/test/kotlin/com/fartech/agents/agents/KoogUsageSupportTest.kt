package com.fartech.agents.agents

import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock

class KoogUsageSupportTest {

    @Test
    fun `extracts token usage from stream end frame meta info`() {
        val usage = koogTokenUsageFromStreamFrame(
            StreamFrame.End(
                finishReason = "stop",
                metaInfo = ResponseMetaInfo(
                    timestamp = Clock.System.now(),
                    totalTokensCount = 2582,
                    inputTokensCount = 2419,
                    outputTokensCount = 163,
                    // Koog 1.0.0 dropped the `additionalInfo` field; `metadata`
                    // (a JsonObject) replaces it for free-form provider extras.
                    metadata = buildJsonObject { }
                )
            )
        )

        requireNotNull(usage)
        assertEquals(2419, usage.promptTokens)
        assertEquals(163, usage.completionTokens)
        assertEquals(2582, usage.totalTokens)
        assertEquals("koog_stream_end", usage.source)
    }

    @Test
    fun `ignores non end frames and empty token usage`() {
        assertNull(koogTokenUsageFromStreamFrame(StreamFrame.TextDelta("hello")))

        val usage = koogTokenUsageFromStreamFrame(
            StreamFrame.End(
                finishReason = "stop",
                metaInfo = ResponseMetaInfo(
                    timestamp = Clock.System.now(),
                    totalTokensCount = 0,
                    inputTokensCount = 0,
                    outputTokensCount = 0,
                    metadata = buildJsonObject { }
                )
            )
        )

        assertNull(usage)
    }
}
