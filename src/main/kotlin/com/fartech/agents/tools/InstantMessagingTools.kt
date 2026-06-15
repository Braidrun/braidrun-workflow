package com.fartech.agents.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.HttpAccess
import com.fartech.ftapp2.commonsKt.parameter
import com.fartech.ftapp2.commonsKt.redactSensitiveUrlForLogs
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import mu.KotlinLogging
import java.io.File
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * InstantMessagingTools - Tools for interacting with users via IM services.
 *
 * Supported services (configured via `im_service`):
 *   - "telegram"   : Telegram Bot API (bidirectional: send + poll for reply)
 *   - "dingtalk"   : DingTalk (钉钉) Robot Webhook (send only)
 *   - "wechatwork" : WeChat Work (企业微信) Robot Webhook (send only)
 *   - "feishu"     : Feishu / Lark (飞书) Robot Webhook (send only)
 *   - "slack"      : Slack Incoming Webhook (send only)
 *   - "discord"    : Discord Webhook (send only)
 *   - "whatsapp"   : WhatsApp Business Cloud API (send only)
 *
 * General parameters:
 *   im_service:             Service type (default: "telegram")
 *   im_ask_timeout_seconds: Max wait time for user reply in seconds (default: 300)
 *   im_debug:               Enable sanitized IM transport debug logs (default: false)
 *
 * Telegram parameters:
 *   im_telegram_bot_token:    Bot API token (required)
 *   im_telegram_chat_id:      Target chat ID (optional; if omitted, auto-discovered from the first incoming message)
 *   im_telegram_poll_timeout: Long-poll timeout per request in seconds (default: 30)
 *
 * DingTalk parameters:
 *   im_dingtalk_webhook_url: Robot webhook URL (required)
 *   im_dingtalk_secret:      HMAC-SHA256 signing secret (optional)
 *
 * WeChat Work parameters:
 *   im_wechatwork_webhook_url: Robot webhook URL (required)
 *
 * Feishu / Lark parameters:
 *   im_feishu_webhook_url: Robot webhook URL (required)
 *   im_feishu_secret:      Signing secret (optional)
 *
 * Slack parameters:
 *   im_slack_webhook_url: Incoming webhook URL (required)
 *
 * Discord parameters:
 *   im_discord_webhook_url: Webhook URL (required)
 *
 * WhatsApp parameters:
 *   im_whatsapp_phone_number_id: WhatsApp Business phone number ID (required)
 *   im_whatsapp_access_token:    Permanent access token (required)
 *   im_whatsapp_recipient_phone: Recipient phone number with country code, no '+' (required)
 *   im_whatsapp_api_version:     Graph API version (default: "v19.0")
 *
 * This tool set is NOT loaded by default. To enable, add "im" to `tool_set` in the agent config.
 */
@LLMDescription("Tools for interacting with users via instant messaging services such as Telegram, DingTalk, WeChat Work, Feishu, Slack, Discord, WhatsApp, etc. Supports sending messages, sending files/documents, and asking questions while waiting for their replies.")
class InstantMessagingTools(
    private val httpAccess: HttpAccess,
    private val parameters: List<ConfigurationParameter>
) : ToolSet {

    private val logger = KotlinLogging.logger {}
    private val outboundFileReadGuard: FileReadGuard by lazy {
        if (parameters.parameter("sandbox_strict", "false").equals("true", ignoreCase = true)) {
            FileReadGuard(enabled = true)
        } else {
            FileReadGuard.DISABLED
        }
    }

    private val maxOutboundFileBytes: Long by lazy {
        parameters.parameter("im_max_file_bytes", (25L * 1024L * 1024L).toString())
            .toLongOrNull()
            ?.takeIf { it > 0 }
            ?: (25L * 1024L * 1024L)
    }

    companion object {
        internal data class TelegramConversationContext(
            val chatId: String,
            val chatType: String,
            val chatTitle: String? = null,
            val senderId: String? = null,
            val senderName: String? = null,
            val senderUsername: String? = null
        )

        internal fun telegramConversationKey(botToken: String, sessionId: String): String {
            val safeSessionId = sessionId.ifBlank { "default" }
            return "$botToken::$safeSessionId"
        }

        /**
         * Hard cap on entries held by each process-wide Telegram discovery
         * cache. Keys are `"$botToken::$sessionId"` — a long-running workflow-web
         * process with many distinct sessions per bot token would otherwise
         * grow these maps without bound. LRU eviction keeps the most recently
         * used entries and drops the cold tail.
         *
         * 4096 covers typical multi-tenant deployments (≈100 bot tokens ×
         * ≈40 concurrent sessions) while bounding heap to a few MB worst case.
         */
        private const val TELEGRAM_CACHE_MAX_ENTRIES = 4096

        private fun <K, V> boundedLruMap(maxEntries: Int): MutableMap<K, V> =
            java.util.Collections.synchronizedMap(
                object : LinkedHashMap<K, V>(maxEntries / 4, 0.75f, /* accessOrder */ true) {
                    override fun removeEldestEntry(eldest: Map.Entry<K, V>): Boolean =
                        size > maxEntries
                }
            )

        /**
         * Global cache of auto-discovered Telegram chat IDs, keyed by bot token + session_id.
         *
         * Each workflow step creates a new Agent → new InstantMessagingTools → new TelegramBackend.
         * Without this cache, the chat ID discovered via askUserViaIM in step 1 is lost when step 2
         * creates a fresh TelegramBackend. This cache ensures the discovered chat ID persists across
         * steps within the same JVM process.
         *
         * Bounded via [boundedLruMap] — the LRU policy evicts the least
         * recently accessed entry when [TELEGRAM_CACHE_MAX_ENTRIES] is
         * exceeded, which in the worst case forces a chat-id re-discovery
         * on the next poll but never OOMs the JVM.
         */
        internal val discoveredChatIds: MutableMap<String, String> =
            boundedLruMap(TELEGRAM_CACHE_MAX_ENTRIES)
        internal val discoveredTelegramContexts: MutableMap<String, TelegramConversationContext> =
            boundedLruMap(TELEGRAM_CACHE_MAX_ENTRIES)
        internal val discoveredTelegramOffsets: MutableMap<String, Long> =
            boundedLruMap(TELEGRAM_CACHE_MAX_ENTRIES)
        internal val discoveredTelegramBotUsernames: MutableMap<String, String> =
            boundedLruMap(TELEGRAM_CACHE_MAX_ENTRIES)

        /** Standard JSON Content-Type headers required by all IM service POST requests. */
        private val JSON_HEADERS = mapOf("Content-Type" to "application/json")

        /** Clears all cached Telegram discovery state to prevent unbounded memory growth. */
        fun clearAll() {
            discoveredChatIds.clear()
            discoveredTelegramContexts.clear()
            discoveredTelegramOffsets.clear()
            discoveredTelegramBotUsernames.clear()
        }

        init {
            Runtime.getRuntime().addShutdownHook(Thread {
                try { clearAll() } catch (_: Exception) {}
            })
        }
    }

    /**
     * Validates a webhook URL through [UrlSafety] (defense-in-depth SSRF guard).
     *
     * IM webhook URLs come from operator-set parameters (`im_*_webhook_url`), but in
     * a multi-tenant SaaS deployment the workflow YAML can also seed those params,
     * so an LLM-influenced YAML or a misconfigured tenant could otherwise point a
     * webhook at `http://localhost:8080/admin` or `http://169.254.169.254/...`.
     * Operators with legitimate internal-network webhook destinations can opt out
     * via `WEB_TOOLS_ALLOW_PRIVATE_URLS=true` (same env var as WebTools).
     *
     * Returns the original URL if valid; throws [IllegalArgumentException] /
     * [SecurityException] if not. Caller should map exceptions to a user-readable
     * "Error: ..." string consistent with the rest of the IM backend surface.
     */
    private fun assertWebhookUrlPublic(rawUrl: String, label: String): String {
        return try {
            UrlSafety.validateAndNormalizeUrl(rawUrl)
            rawUrl
        } catch (e: SecurityException) {
            throw SecurityException(
                "$label webhook URL is blocked by SSRF guard: ${e.message}",
                e
            )
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(
                "$label webhook URL is invalid: ${e.message}",
                e
            )
        }
    }

    // -----------------------------------------------------------------------
    // Backend interface
    // -----------------------------------------------------------------------

    private interface IMServiceBackend {
        /** Send a message. Returns "Done" on success or an error string. */
        suspend fun sendMessage(text: String): String

        /** Send a prompt and wait for the user's reply. Returns the reply or an error string. */
        suspend fun askUser(prompt: String): String

        /**
         * Wait for a user to proactively send a message to the bot.
         * Unlike [askUser], this does NOT send any prompt first — it purely listens.
         * Used when we don't yet know who the user is (e.g. auto-discovery mode).
         * Returns the user's message text on success, or an error string.
         */
        suspend fun waitForMessage(): String =
            "Error: waitForMessage is not supported for this IM service (${this::class.simpleName}). " +
                "Only Telegram currently supports waiting for unsolicited messages."

        /** Send a file/document to the user. Returns "Done" on success or an error string. */
        suspend fun sendFile(filePath: String, caption: String?): String =
            "Error: File sending is not supported for this IM service (${this::class.simpleName}). " +
                "Only Telegram currently supports file sending."
    }

    // -----------------------------------------------------------------------
    // Backend selection
    // -----------------------------------------------------------------------

    private val service: String get() = parameters.parameter("im_service", "telegram").lowercase()
    private val askTimeoutSeconds: Int get() = parameters.parameter("im_ask_timeout_seconds", 300)
    private val debugEnabled: Boolean
        get() = parameters.parameter("im_debug", false) ||
            (System.getProperty("BRAIDRUN_IM_DEBUG") ?: System.getenv("BRAIDRUN_IM_DEBUG"))
                .equals("true", ignoreCase = true)

    /** Cached backend instance – ensures stateful backends (e.g. Telegram auto-discovered chat ID) survive across calls. */
    private var cachedBackend: IMServiceBackend? = null
    private var cachedBackendService: String? = null

    private fun debugLog(message: () -> String) {
        if (debugEnabled) {
            logger.info { "[IM_DEBUG] ${message()}" }
        }
    }

    private fun resolveBackend(): IMServiceBackend {
        val svc = service
        val cached = cachedBackend
        if (cached != null && cachedBackendService == svc) return cached
        val backend = when (svc) {
            "telegram" -> TelegramBackend()
            "dingtalk" -> DingTalkBackend()
            "wechatwork" -> WeChatWorkBackend()
            "feishu" -> FeishuBackend()
            "slack" -> SlackBackend()
            "discord" -> DiscordBackend()
            "whatsapp" -> WhatsAppBackend()
            else -> object : IMServiceBackend {
                override suspend fun sendMessage(text: String) =
                    "Error: Unsupported IM service '$svc'. Supported: telegram, dingtalk, wechatwork, feishu, slack, discord, whatsapp"

                override suspend fun askUser(prompt: String) = sendMessage(prompt)
            }
        }
        cachedBackend = backend
        cachedBackendService = svc
        return backend
    }

    // -----------------------------------------------------------------------
    // Public Tool Methods
    // -----------------------------------------------------------------------

    @Tool
    @LLMDescription("Send a message to the user via the configured IM service. Does not wait for a response.")
    suspend fun sendMessageToUser(
        @LLMDescription("The message text to send to the user")
        message: String
    ): String = resolveBackend().sendMessage(message)

    @Tool
    @LLMDescription("Ask the user a question via the configured IM service and wait for their reply. The user must already be known (chat ID configured or previously discovered). Returns the user's reply as a string.")
    suspend fun askUserViaIM(
        @LLMDescription("The question or prompt to send to the user")
        prompt: String
    ): String = resolveBackend().askUser(prompt)

    @Tool
    @LLMDescription("Wait for a user to proactively send a message to the bot via the configured IM service. " +
        "Unlike askUserViaIM, this does NOT send any prompt — it purely listens for an incoming message. " +
        "Use this when the bot has just started and no user is known yet (e.g. waiting for the first contact). " +
        "The user's chat ID will be automatically discovered and remembered for subsequent communication. " +
        "Returns the user's message text.")
    suspend fun waitUserMessage(): String = resolveBackend().waitForMessage()

    @Tool
    @LLMDescription("Send a file/document to the user via the configured IM service. The file must exist on the local filesystem. Optionally include a caption/description for the file.")
    suspend fun sendFileToUser(
        @LLMDescription("The absolute path to the file on the local filesystem to send to the user")
        filePath: String,
        @LLMDescription("Optional caption or description to accompany the file. Pass empty string if not needed.")
        caption: String = ""
    ): String {
        val file = try {
            ToolPathSecurity.validateInputPath(filePath).also { outboundFileReadGuard.validateReadFile(it) }
        } catch (e: Exception) {
            return "Error: file path rejected — ${e.message}"
        }
        if (!file.exists()) return "Error: File not found: $filePath"
        if (!file.isFile) return "Error: Path is not a file: $filePath"
        if (file.length() == 0L) return "Error: File is empty: $filePath"
        if (file.length() > maxOutboundFileBytes) {
            return "Error: File is ${file.length()} bytes, exceeds im_max_file_bytes=$maxOutboundFileBytes"
        }
        return resolveBackend().sendFile(file.absolutePath, caption.ifBlank { null })
    }

    // -----------------------------------------------------------------------
    // Telegram backend (bidirectional)
    // -----------------------------------------------------------------------

    private inner class TelegramBackend : IMServiceBackend {
        private val botToken: String get() = parameters.parameter("im_telegram_bot_token", "")
        private val configuredChatId: String get() = parameters.parameter("im_telegram_chat_id", "")
        private val configuredBotUsername: String get() = parameters.parameter("im_telegram_bot_username", "")
        private val pollTimeout: Int get() = parameters.parameter("im_telegram_poll_timeout", 30)
        private val includeSenderMetadata: Boolean get() =
            parameters.parameter("im_telegram_include_sender_metadata", false)
        private val groupTriggerMode: String get() =
            parameters.parameter("im_telegram_group_trigger_mode", "all").lowercase()
        private val sessionKey: String
            get() = telegramConversationKey(botToken, parameters.parameter("session_id", ""))

        /**
         * Mutable chat ID: starts with the configured value, but if blank,
         * will be auto-discovered from the first incoming message.
         * Also checks the global [discoveredChatIds] cache so that a chat ID
         * discovered in a previous step survives across agent/tool re-creation.
         */
        private var resolvedChatId: String? = null

        private fun rememberedOffset(): Long? {
            if (botToken.isBlank()) return null
            return discoveredTelegramOffsets[sessionKey] ?: discoveredTelegramOffsets[botToken]
        }

        private fun rememberOffset(offset: Long) {
            if (botToken.isBlank()) return
            discoveredTelegramOffsets[sessionKey] = offset
            val previous = discoveredTelegramOffsets[botToken]
            if (previous == null || offset > previous) {
                discoveredTelegramOffsets[botToken] = offset
            }
        }

        private fun rememberBotUsername(username: String) {
            if (botToken.isBlank() || username.isBlank()) return
            discoveredTelegramBotUsernames[botToken] = username
        }

        private suspend fun botUsername(): String? {
            configuredBotUsername.takeIf { it.isNotBlank() }?.let {
                rememberBotUsername(it)
                return it
            }
            discoveredTelegramBotUsernames[botToken]?.let { return it }
            if (botToken.isBlank()) return null

            return try {
                val response = httpAccess.get<String>("https://api.telegram.org/bot$botToken/getMe")
                val json = Json.parseToJsonElement(response).jsonObject
                val username = json["result"]
                    ?.jsonObject
                    ?.get("username")
                    ?.jsonPrimitive
                    ?.contentOrNull
                username?.takeIf { it.isNotBlank() }?.also { rememberBotUsername(it) }
            } catch (e: Exception) {
                logger.debug(e) { "Failed to discover Telegram bot username" }
                null
            }
        }

        private fun chatId(): String {
            resolvedChatId?.let { return it }
            configuredChatId.ifBlank { null }?.let { return it }
            // Fall back to the session-scoped global cache (populated by a previous step's auto-discovery)
            if (botToken.isNotBlank()) {
                discoveredChatIds[sessionKey]?.let {
                    resolvedChatId = it
                    return it
                }
                // Legacy fallback for pre-session-scoped executions
                discoveredChatIds[botToken]?.let {
                    resolvedChatId = it
                    return it
                }
            }
            return ""
        }

        private fun rememberConversationContext(
            chatId: String,
            chatType: String,
            chatTitle: String?,
            senderId: String?,
            senderName: String?,
            senderUsername: String?
        ) {
            if (botToken.isBlank()) return
            val context = TelegramConversationContext(
                chatId = chatId,
                chatType = chatType,
                chatTitle = chatTitle,
                senderId = senderId,
                senderName = senderName,
                senderUsername = senderUsername
            )
            discoveredChatIds[sessionKey] = chatId
            discoveredTelegramContexts[sessionKey] = context

            // Backward-compatible fallback for callers that still only know the token.
            discoveredChatIds.putIfAbsent(botToken, chatId)
            discoveredTelegramContexts.putIfAbsent(botToken, context)
        }

        private fun extractSenderName(from: JsonObject?): String? {
            if (from == null) return null
            val first = from["first_name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val last = from["last_name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            return listOf(first, last).filter { it.isNotBlank() }.joinToString(" ").ifBlank { null }
        }

        private fun formatIncomingMessage(
            text: String,
            chatId: String,
            chatType: String,
            chatTitle: String?,
            senderId: String?,
            senderName: String?,
            senderUsername: String?
        ): String {
            if (!includeSenderMetadata) return text

            return buildString {
                appendLine("[telegram_message]")
                appendLine("chat_id=$chatId")
                appendLine("chat_type=$chatType")
                chatTitle?.takeIf { it.isNotBlank() }?.let { appendLine("chat_title=$it") }
                senderId?.takeIf { it.isNotBlank() }?.let { appendLine("sender_id=$it") }
                senderName?.takeIf { it.isNotBlank() }?.let { appendLine("sender_name=$it") }
                senderUsername?.takeIf { it.isNotBlank() }?.let { appendLine("sender_username=$it") }
                appendLine("---message---")
                append(text)
            }
        }

        private fun messageRepliesToBot(message: JsonObject, botUsername: String?): Boolean {
            val replyToMessage = message["reply_to_message"]?.jsonObject ?: return false
            val from = replyToMessage["from"]?.jsonObject ?: return false
            val replyIsBot = from["is_bot"]?.jsonPrimitive?.booleanOrNull == true
            if (!replyIsBot) return false

            if (botUsername.isNullOrBlank()) return true
            val replyUsername = from["username"]?.jsonPrimitive?.contentOrNull
            return replyUsername.equals(botUsername, ignoreCase = true)
        }

        private suspend fun shouldAcceptMessage(
            message: JsonObject,
            chatType: String,
            text: String
        ): Boolean {
            if (chatType == "private") return true
            if (groupTriggerMode == "all") return true

            val botUsername = botUsername()
            val normalizedText = text.lowercase()
            val mentionToken = botUsername?.lowercase()?.let { "@$it" }
            val mentionsBot = mentionToken != null && normalizedText.contains(mentionToken)
            val repliesToBot = messageRepliesToBot(message, botUsername)

            return when (groupTriggerMode) {
                "mention_only" -> mentionsBot
                "mention_or_reply" -> mentionsBot || repliesToBot
                else -> true
            }
        }

        override suspend fun sendMessage(text: String): String {
            if (botToken.isBlank()) return "Error: im_telegram_bot_token is not configured"
            val cid = chatId()
            if (cid.isBlank()) return "Error: No user is known yet. Use waitUserMessage first to wait for a user " +
                "to contact the bot, then use sendMessageToUser for subsequent messages."

            val url = "https://api.telegram.org/bot$botToken/sendMessage"
            val body = mapOf("chat_id" to cid, "text" to text)
            return try {
                val response = httpAccess.post<Map<String, String>, String>(url, body, JSON_HEADERS)
                val json = Json.parseToJsonElement(response).jsonObject
                if (json["ok"]?.jsonPrimitive?.boolean == true) "Done"
                else "Error: Telegram API returned: $response"
            } catch (e: Exception) {
                "Error sending Telegram message: ${e.message}"
            }
        }

        override suspend fun askUser(prompt: String): String {
            if (botToken.isBlank()) return "Error: im_telegram_bot_token is not configured"

            val cid = chatId()
            if (cid.isBlank()) return "Error: No user is known yet. Use waitUserMessage first to wait for a user " +
                "to contact the bot, then use askUserViaIM for subsequent questions."

            // Send the prompt first
            val sendResult = sendMessage(prompt)
            if (sendResult != "Done") return sendResult

            // Poll for the user's reply
            val deadline = System.currentTimeMillis() + askTimeoutSeconds * 1000L
            var offset: Long? = rememberedOffset()
            if (offset == null) {
                offset = drainUpdates()
                offset?.let { rememberOffset(it) }
            }

            while (System.currentTimeMillis() < deadline) {
                val remaining = ((deadline - System.currentTimeMillis()) / 1000).coerceAtLeast(1)
                val pollSecs = minOf(pollTimeout.toLong(), remaining).toInt()
                try {
                    val urlStr = buildString {
                        append("https://api.telegram.org/bot$botToken/getUpdates?timeout=$pollSecs")
                        if (offset != null) append("&offset=$offset")
                    }
                    val response = httpAccess.get<String>(urlStr)
                    val json = Json.parseToJsonElement(response).jsonObject
                    if (json["ok"]?.jsonPrimitive?.boolean != true) continue

                    val results = json["result"]?.jsonArray ?: continue
                    for (update in results) {
                        val updateObj = update.jsonObject
                        val updateId = updateObj["update_id"]?.jsonPrimitive?.long ?: continue
                        offset = updateId + 1
                        rememberOffset(offset)
                        val message = updateObj["message"]?.jsonObject ?: continue
                        val chatObj = message["chat"]?.jsonObject ?: continue
                        val msgChatId = chatObj["id"]?.jsonPrimitive?.content ?: continue
                        val chatType = chatObj["type"]?.jsonPrimitive?.content ?: "private"
                        val chatTitle = chatObj["title"]?.jsonPrimitive?.contentOrNull
                            ?: chatObj["first_name"]?.jsonPrimitive?.contentOrNull
                        val from = message["from"]?.jsonObject
                        val senderId = from?.get("id")?.jsonPrimitive?.contentOrNull
                        val senderUsername = from?.get("username")?.jsonPrimitive?.contentOrNull
                        val senderName = extractSenderName(from)
                        if (msgChatId != cid) continue
                        val text = message["text"]?.jsonPrimitive?.content ?: continue
                        if (!shouldAcceptMessage(message, chatType, text)) {
                            debugLog { "ignoring group message because it does not address the bot" }
                            continue
                        }
                        rememberConversationContext(
                            chatId = msgChatId,
                            chatType = chatType,
                            chatTitle = chatTitle,
                            senderId = senderId,
                            senderName = senderName,
                            senderUsername = senderUsername
                        )
                        return formatIncomingMessage(
                            text = text,
                            chatId = msgChatId,
                            chatType = chatType,
                            chatTitle = chatTitle,
                            senderId = senderId,
                            senderName = senderName,
                            senderUsername = senderUsername
                        )
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.debug(e) { "Transient polling error, continuing" }
                }
            }
            return "Error: Timed out waiting for user reply after $askTimeoutSeconds seconds"
        }

        override suspend fun waitForMessage(): String {
            if (botToken.isBlank()) {
                return "Error: im_telegram_bot_token is not configured"
            }

            val deadline = System.currentTimeMillis() + askTimeoutSeconds * 1000L
            var offset: Long? = rememberedOffset()
            if (offset == null) {
                offset = drainUpdates()
                offset?.let { rememberOffset(it) }
            }

            var loopCount = 0
            while (System.currentTimeMillis() < deadline) {
                loopCount++
                val remaining = ((deadline - System.currentTimeMillis()) / 1000).coerceAtLeast(1)
                val pollSecs = minOf(pollTimeout.toLong(), remaining).toInt()
                try {
                    val urlStr = buildString {
                        append("https://api.telegram.org/bot$botToken/getUpdates?timeout=$pollSecs")
                        if (offset != null) append("&offset=$offset")
                    }
                    val response = httpAccess.get<String>(urlStr)
                    val json = Json.parseToJsonElement(response).jsonObject
                    if (json["ok"]?.jsonPrimitive?.boolean != true) {
                        continue
                    }

                    val results = json["result"]?.jsonArray ?: continue
                    for (update in results) {
                        val updateObj = update.jsonObject
                        val updateId = updateObj["update_id"]?.jsonPrimitive?.long ?: continue
                        offset = updateId + 1
                        rememberOffset(offset)
                        val message = updateObj["message"]?.jsonObject ?: continue
                        val chatObj = message["chat"]?.jsonObject ?: continue
                        val msgChatId = chatObj["id"]?.jsonPrimitive?.content ?: continue
                        val chatType = chatObj["type"]?.jsonPrimitive?.content ?: "private"
                        val chatTitle = chatObj["title"]?.jsonPrimitive?.contentOrNull
                            ?: chatObj["first_name"]?.jsonPrimitive?.contentOrNull
                        val from = message["from"]?.jsonObject
                        val senderId = from?.get("id")?.jsonPrimitive?.contentOrNull
                        val senderUsername = from?.get("username")?.jsonPrimitive?.contentOrNull
                        val senderName = extractSenderName(from)

                        // If we already know the user, only accept messages from them
                        val knownCid = chatId()
                        if (knownCid.isNotBlank() && msgChatId != knownCid) {
                            debugLog {
                                "waitForMessage ignoring message from another chat: " +
                                    "knownCid=$knownCid actualCid=$msgChatId updateId=$updateId"
                            }
                            continue
                        }

                        // Auto-discover: remember this user for all future communication
                        if (resolvedChatId == null && configuredChatId.isBlank()) {
                            resolvedChatId = msgChatId
                        }

                        val text = message["text"]?.jsonPrimitive?.content ?: run {
                            debugLog {
                                "waitForMessage skipping non-text update: " +
                                    "chatId=$msgChatId updateId=$updateId"
                            }
                            continue
                        }
                        if (!shouldAcceptMessage(message, chatType, text)) {
                            debugLog { "ignoring group message because it does not address the bot" }
                            continue
                        }
                        debugLog {
                            "waitForMessage accepted message: " +
                                "chatId=$msgChatId updateId=$updateId textLength=${text.length}"
                        }
                        rememberConversationContext(
                            chatId = msgChatId,
                            chatType = chatType,
                            chatTitle = chatTitle,
                            senderId = senderId,
                            senderName = senderName,
                            senderUsername = senderUsername
                        )
                        return formatIncomingMessage(
                            text = text,
                            chatId = msgChatId,
                            chatType = chatType,
                            chatTitle = chatTitle,
                            senderId = senderId,
                            senderName = senderName,
                            senderUsername = senderUsername
                        )
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    debugLog { "waitForMessage cancelled" }
                    throw e
                } catch (e: Exception) {
                    debugLog {
                        "waitForMessage exception: ${e::class.simpleName}: ${e.message?.take(200)}"
                    }
                }
            }
            debugLog { "waitForMessage timed out after $loopCount polling cycle(s)" }
            return "Error: Timed out waiting for a user to message the bot after $askTimeoutSeconds seconds. " +
                "Please ask a user to send a message to the bot on Telegram to start the conversation."
        }

        override suspend fun sendFile(filePath: String, caption: String?): String {
            if (botToken.isBlank()) return "Error: im_telegram_bot_token is not configured"
            val cid = chatId()
            if (cid.isBlank()) return "Error: No user is known yet. Use waitUserMessage first to wait for a user " +
                "to contact the bot, then use sendFileToUser for subsequent file sends."

            val file = File(filePath)
            val url = "https://api.telegram.org/bot$botToken/sendDocument"
            return try {
                val response = httpAccess.client.submitFormWithBinaryData(
                    url = url,
                    formData = formData {
                        append("chat_id", cid)
                        append("document", file.readBytes(), Headers.build {
                            append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                            append(HttpHeaders.ContentDisposition, "filename=\"${file.name}\"")
                        })
                        if (!caption.isNullOrBlank()) {
                            append("caption", caption)
                        }
                    }
                )
                val body = response.bodyAsText()
                val json = Json.parseToJsonElement(body).jsonObject
                if (json["ok"]?.jsonPrimitive?.boolean == true) "Done"
                else "Error: Telegram API returned: $body"
            } catch (e: Exception) {
                "Error sending file via Telegram: ${e.message}"
            }
        }

        /** Drain pending updates so [askUser] only sees new replies. */
        private suspend fun drainUpdates(): Long? = try {
            val url = "https://api.telegram.org/bot$botToken/getUpdates?timeout=0"
            debugLog { "drainUpdates() calling: ${redactSensitiveUrlForLogs(url)}" }
            val response = httpAccess.get<String>(url)
            val json = Json.parseToJsonElement(response).jsonObject
            val results = json["result"]?.jsonArray ?: run {
                debugLog { "drainUpdates() no result array, returning null" }
                return null
            }
            if (results.isEmpty()) {
                debugLog { "drainUpdates() empty results, returning null" }
                return null
            }
            val lastOffset = (results.last().jsonObject["update_id"]?.jsonPrimitive?.long ?: run {
                debugLog { "drainUpdates() no update_id in last result, returning null" }
                return null
            }) + 1
            debugLog { "drainUpdates() drained ${results.size} updates, returning offset=$lastOffset" }
            lastOffset
        } catch (e: Exception) {
            debugLog { "drainUpdates() exception: ${e::class.simpleName}: ${e.message?.take(200)}" }
            null
        }
    }

    // -----------------------------------------------------------------------
    // DingTalk (钉钉) backend – Robot webhook, send-only
    // -----------------------------------------------------------------------

    private inner class DingTalkBackend : IMServiceBackend {
        private val webhookUrl: String get() = parameters.parameter("im_dingtalk_webhook_url", "")
        private val secret: String get() = parameters.parameter("im_dingtalk_secret", "")

        override suspend fun sendMessage(text: String): String {
            if (webhookUrl.isBlank()) return "Error: im_dingtalk_webhook_url is not configured"

            val validatedWebhookUrl = try {
                assertWebhookUrlPublic(webhookUrl, "DingTalk")
            } catch (e: Exception) {
                return "Error: ${e.message}"
            }
            val url = if (secret.isNotBlank()) appendDingTalkSign(validatedWebhookUrl, secret) else validatedWebhookUrl
            val body = mapOf(
                "msgtype" to "text",
                "text" to mapOf("content" to text)
            )
            return try {
                val response = httpAccess.post<Map<String, Any>, String>(url, body, JSON_HEADERS)
                val json = Json.parseToJsonElement(response).jsonObject
                val errcode = json["errcode"]?.jsonPrimitive?.intOrNull ?: -1
                if (errcode == 0) "Done" else "Error: DingTalk API returned: $response"
            } catch (e: Exception) {
                "Error sending DingTalk message: ${e.message}"
            }
        }

        override suspend fun askUser(prompt: String): String {
            val sendResult = sendMessage(prompt)
            if (sendResult != "Done") return sendResult
            return "Message sent via DingTalk. Reply collection is not supported for webhook-only services."
        }

        private fun appendDingTalkSign(webhookUrl: String, secret: String): String {
            val timestamp = System.currentTimeMillis()
            val stringToSign = "$timestamp\n$secret"
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val signData = mac.doFinal(stringToSign.toByteArray(Charsets.UTF_8))
            val sign = Base64.getEncoder().encodeToString(signData)
                .let { java.net.URLEncoder.encode(it, "UTF-8") }
            return "$webhookUrl&timestamp=$timestamp&sign=$sign"
        }
    }

    // -----------------------------------------------------------------------
    // WeChat Work (企业微信) backend – Robot webhook, send-only
    // -----------------------------------------------------------------------

    private inner class WeChatWorkBackend : IMServiceBackend {
        private val webhookUrl: String get() = parameters.parameter("im_wechatwork_webhook_url", "")

        override suspend fun sendMessage(text: String): String {
            if (webhookUrl.isBlank()) return "Error: im_wechatwork_webhook_url is not configured"

            val validatedUrl = try {
                assertWebhookUrlPublic(webhookUrl, "WeChat Work")
            } catch (e: Exception) {
                return "Error: ${e.message}"
            }
            val body = mapOf(
                "msgtype" to "text",
                "text" to mapOf("content" to text)
            )
            return try {
                val response = httpAccess.post<Map<String, Any>, String>(validatedUrl, body, JSON_HEADERS)
                val json = Json.parseToJsonElement(response).jsonObject
                val errcode = json["errcode"]?.jsonPrimitive?.intOrNull ?: -1
                if (errcode == 0) "Done" else "Error: WeChat Work API returned: $response"
            } catch (e: Exception) {
                "Error sending WeChat Work message: ${e.message}"
            }
        }

        override suspend fun askUser(prompt: String): String {
            val sendResult = sendMessage(prompt)
            if (sendResult != "Done") return sendResult
            return "Message sent via WeChat Work. Reply collection is not supported for webhook-only services."
        }
    }

    // -----------------------------------------------------------------------
    // Feishu / Lark (飞书) backend – Robot webhook, send-only
    // -----------------------------------------------------------------------

    private inner class FeishuBackend : IMServiceBackend {
        private val webhookUrl: String get() = parameters.parameter("im_feishu_webhook_url", "")
        private val secret: String get() = parameters.parameter("im_feishu_secret", "")

        override suspend fun sendMessage(text: String): String {
            if (webhookUrl.isBlank()) return "Error: im_feishu_webhook_url is not configured"

            val validatedUrl = try {
                assertWebhookUrlPublic(webhookUrl, "Feishu")
            } catch (e: Exception) {
                return "Error: ${e.message}"
            }
            val bodyMap = mutableMapOf<String, Any>(
                "msg_type" to "text",
                "content" to mapOf("text" to text)
            )
            if (secret.isNotBlank()) {
                val timestamp = System.currentTimeMillis() / 1000
                bodyMap["timestamp"] = timestamp.toString()
                bodyMap["sign"] = feishuSign(timestamp, secret)
            }
            return try {
                val response = httpAccess.post<Map<String, Any>, String>(validatedUrl, bodyMap, JSON_HEADERS)
                val json = Json.parseToJsonElement(response).jsonObject
                val statusCode = json["StatusCode"]?.jsonPrimitive?.intOrNull
                    ?: json["code"]?.jsonPrimitive?.intOrNull
                    ?: 0
                if (statusCode == 0) "Done" else "Error: Feishu API returned: $response"
            } catch (e: Exception) {
                "Error sending Feishu message: ${e.message}"
            }
        }

        override suspend fun askUser(prompt: String): String {
            val sendResult = sendMessage(prompt)
            if (sendResult != "Done") return sendResult
            return "Message sent via Feishu. Reply collection is not supported for webhook-only services."
        }

        private fun feishuSign(timestamp: Long, secret: String): String {
            val stringToSign = "$timestamp\n$secret"
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(stringToSign.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            return Base64.getEncoder().encodeToString(mac.doFinal())
        }
    }

    // -----------------------------------------------------------------------
    // Slack backend – Incoming Webhook, send-only
    // -----------------------------------------------------------------------

    private inner class SlackBackend : IMServiceBackend {
        private val webhookUrl: String get() = parameters.parameter("im_slack_webhook_url", "")

        override suspend fun sendMessage(text: String): String {
            if (webhookUrl.isBlank()) return "Error: im_slack_webhook_url is not configured"

            val validatedUrl = try {
                assertWebhookUrlPublic(webhookUrl, "Slack")
            } catch (e: Exception) {
                return "Error: ${e.message}"
            }
            val body = mapOf("text" to text)
            return try {
                val response = httpAccess.post<Map<String, String>, String>(validatedUrl, body, JSON_HEADERS)
                if (response.trim() == "ok") "Done"
                else "Error: Slack API returned: $response"
            } catch (e: Exception) {
                "Error sending Slack message: ${e.message}"
            }
        }

        override suspend fun askUser(prompt: String): String {
            val sendResult = sendMessage(prompt)
            if (sendResult != "Done") return sendResult
            return "Message sent via Slack. Reply collection is not supported for incoming webhook. Use Slack Bot API for bidirectional communication."
        }
    }

    // -----------------------------------------------------------------------
    // Discord backend – Webhook, send-only
    // -----------------------------------------------------------------------

    private inner class DiscordBackend : IMServiceBackend {
        private val webhookUrl: String get() = parameters.parameter("im_discord_webhook_url", "")

        override suspend fun sendMessage(text: String): String {
            if (webhookUrl.isBlank()) return "Error: im_discord_webhook_url is not configured"

            val validatedUrl = try {
                assertWebhookUrlPublic(webhookUrl, "Discord")
            } catch (e: Exception) {
                return "Error: ${e.message}"
            }
            val body = mapOf("content" to text)
            return try {
                // Discord returns 204 No Content on success; httpAccess may return an empty string
                httpAccess.post<Map<String, String>, String>(validatedUrl, body, JSON_HEADERS)
                "Done"
            } catch (e: Exception) {
                val msg = e.message ?: ""
                // 204 No Content can manifest as an exception in some clients; treat as success
                if (msg.contains("204") || msg.contains("No Content")) "Done"
                else "Error sending Discord message: $msg"
            }
        }

        override suspend fun askUser(prompt: String): String {
            val sendResult = sendMessage(prompt)
            if (sendResult != "Done") return sendResult
            return "Message sent via Discord. Reply collection is not supported for webhook-only services."
        }
    }

    // -----------------------------------------------------------------------
    // WhatsApp backend – Business Cloud API, send-only
    // -----------------------------------------------------------------------

    private inner class WhatsAppBackend : IMServiceBackend {
        private val phoneNumberId: String get() = parameters.parameter("im_whatsapp_phone_number_id", "")
        private val accessToken: String get() = parameters.parameter("im_whatsapp_access_token", "")
        private val recipientPhone: String get() = parameters.parameter("im_whatsapp_recipient_phone", "")
        private val apiVersion: String get() = parameters.parameter("im_whatsapp_api_version", "v19.0")

        override suspend fun sendMessage(text: String): String {
            if (phoneNumberId.isBlank()) return "Error: im_whatsapp_phone_number_id is not configured"
            if (accessToken.isBlank()) return "Error: im_whatsapp_access_token is not configured"
            if (recipientPhone.isBlank()) return "Error: im_whatsapp_recipient_phone is not configured"

            val url = "https://graph.facebook.com/$apiVersion/$phoneNumberId/messages"
            val body = mapOf(
                "messaging_product" to "whatsapp",
                "to" to recipientPhone,
                "type" to "text",
                "text" to mapOf("body" to text)
            )
            return try {
                val response = httpAccess.post<Map<String, Any>, String>(
                    url, body,
                    headers = JSON_HEADERS + mapOf("Authorization" to "Bearer $accessToken")
                )
                val json = Json.parseToJsonElement(response).jsonObject
                if (json["messages"] != null) "Done"
                else "Error: WhatsApp API returned: $response"
            } catch (e: Exception) {
                "Error sending WhatsApp message: ${e.message}"
            }
        }

        override suspend fun askUser(prompt: String): String {
            val sendResult = sendMessage(prompt)
            if (sendResult != "Done") return sendResult
            return "Message sent via WhatsApp. Reply collection is not supported for Cloud API send-only mode."
        }
    }
}
