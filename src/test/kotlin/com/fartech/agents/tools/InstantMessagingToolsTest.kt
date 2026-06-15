package com.fartech.agents.tools

import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains

class InstantMessagingToolsTest {

    @Test
    fun `sendFileToUser rejects paths outside tool sandbox before transport`(): Unit = runBlocking {
        val outside = File("/etc/hosts")
        if (!outside.exists()) return@runBlocking
        val tools = InstantMessagingTools(HttpAccess(), emptyList())

        val result = tools.sendFileToUser(outside.absolutePath)

        assertContains(result, "file path rejected")
    }

    @Test
    fun `sendFileToUser rejects oversized files before transport`(@TempDir dir: File): Unit = runBlocking {
        val file = File(dir, "payload.txt").also { it.writeText("too large") }
        val tools = InstantMessagingTools(
            HttpAccess(),
            listOf(ConfigurationParameter("im_max_file_bytes", JsonPrimitive("4")))
        )

        val result = tools.sendFileToUser(file.absolutePath)

        assertContains(result, "exceeds im_max_file_bytes")
    }

    @Test
    fun `sendFileToUser applies strict read guard to sensitive filenames`(@TempDir dir: File): Unit = runBlocking {
        val file = File(dir, ".env").also { it.writeText("TOKEN=secret") }
        val tools = InstantMessagingTools(
            HttpAccess(),
            listOf(ConfigurationParameter("sandbox_strict", JsonPrimitive("true")))
        )

        val result = tools.sendFileToUser(file.absolutePath)

        assertContains(result, "file path rejected")
        assertContains(result, "blacklisted")
    }

    /**
     * Phase 10 (2026-05-08) audit fix: webhook URLs are now validated through
     * [UrlSafety] before any HTTP send, preventing SSRF where a misconfigured or
     * tenant-injected `im_*_webhook_url` could otherwise reach `localhost`,
     * `169.254.169.254`, or any RFC 1918 internal address.
     *
     * The check applies to DingTalk / WeChat Work / Feishu / Slack / Discord —
     * Telegram and WhatsApp use hard-coded API endpoints already.
     */
    @Test
    fun `sendMessageToUser rejects loopback DingTalk webhook before transport`(): Unit = runBlocking {
        val tools = InstantMessagingTools(
            HttpAccess(),
            listOf(
                ConfigurationParameter("im_service", JsonPrimitive("dingtalk")),
                ConfigurationParameter("im_dingtalk_webhook_url", JsonPrimitive("http://127.0.0.1:8080/admin"))
            )
        )

        val result = tools.sendMessageToUser("hello")

        assertContains(result, "DingTalk webhook URL is blocked by SSRF guard")
    }

    @Test
    fun `sendMessageToUser rejects cloud metadata Feishu webhook`(): Unit = runBlocking {
        val tools = InstantMessagingTools(
            HttpAccess(),
            listOf(
                ConfigurationParameter("im_service", JsonPrimitive("feishu")),
                ConfigurationParameter("im_feishu_webhook_url", JsonPrimitive("http://169.254.169.254/latest/meta-data/"))
            )
        )

        val result = tools.sendMessageToUser("hello")

        assertContains(result, "Feishu webhook URL is blocked by SSRF guard")
    }

    @Test
    fun `sendMessageToUser rejects RFC1918 Slack webhook`(): Unit = runBlocking {
        val tools = InstantMessagingTools(
            HttpAccess(),
            listOf(
                ConfigurationParameter("im_service", JsonPrimitive("slack")),
                ConfigurationParameter("im_slack_webhook_url", JsonPrimitive("http://10.0.0.5/services/T000/B000/abc"))
            )
        )

        val result = tools.sendMessageToUser("hello")

        assertContains(result, "Slack webhook URL is blocked by SSRF guard")
    }

    @Test
    fun `sendMessageToUser rejects unsupported scheme webhook`(): Unit = runBlocking {
        val tools = InstantMessagingTools(
            HttpAccess(),
            listOf(
                ConfigurationParameter("im_service", JsonPrimitive("discord")),
                ConfigurationParameter("im_discord_webhook_url", JsonPrimitive("file:///etc/passwd"))
            )
        )

        val result = tools.sendMessageToUser("hello")

        assertContains(result, "Discord webhook URL is invalid")
    }
}
