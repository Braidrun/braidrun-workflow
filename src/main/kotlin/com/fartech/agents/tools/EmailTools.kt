package com.fartech.agents.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.fartech.ftapp2.commonsKt.AnsiColor
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.parameter
import com.fartech.ftapp2.commonsKt.printlnColor
import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.search.BodyTerm
import jakarta.mail.search.FromStringTerm
import jakarta.mail.search.OrTerm
import jakarta.mail.search.SubjectTerm
import mu.KotlinLogging
import java.util.*

private val logger = KotlinLogging.logger {}

@LLMDescription(
    "Email tools for sending and reading emails via SMTP and IMAP. " +
            "Requires email configuration parameters (smtp_host, smtp_port, imap_host, email_username, email_password) " +
            "to be set in agent configuration. SMTP sending can use IP-authenticated relay mode by setting " +
            "smtp_auth_enabled=false and providing smtp_from_address. Use smtp_security=starttls|ssl|none when needed."
)
class EmailTools(
    private val parameters: List<ConfigurationParameter>
) : ToolSet {

    private fun smtpAuthEnabled(): Boolean {
        val raw = parameters.parameter(
            "smtp_auth_enabled",
            parameters.parameter("smtp_auth", "true")
        ).trim().lowercase()
        return raw !in setOf("false", "0", "no", "off")
    }

    private fun getSmtpSession(): Session {
        val host = parameters.parameter("smtp_host", "smtp.gmail.com")
        val port = parameters.parameter("smtp_port", "587")
        val username = parameters.parameter("email_username", "")
        val password = parameters.parameter("email_password", "")
        val useTls = parameters.parameter("smtp_use_tls", "true").trim().lowercase()
        // Back-compat: legacy configs used smtp_use_tls=false to mean IMPLICIT SSL
        // (port-465 style), not plaintext. Defaulting "false" to "none" silently
        // downgraded those deployments to unencrypted SMTP including AUTH
        // credentials. Plaintext now requires an explicit smtp_security=none.
        val security = parameters.parameter(
            "smtp_security",
            if (useTls == "true") "starttls" else "ssl"
        ).trim().lowercase()
        val authEnabled = smtpAuthEnabled()

        val props = Properties().apply {
            put("mail.smtp.host", host)
            put("mail.smtp.port", port)
            put("mail.smtp.auth", authEnabled.toString())
            when (security) {
                "starttls", "tls" -> {
                    put("mail.smtp.starttls.enable", "true")
                    // STARTTLS must NOT silently fall back to plaintext if the server rejects it.
                    put("mail.smtp.starttls.required", "true")
                }
                "ssl", "smtps" -> {
                    put("mail.smtp.ssl.enable", "true")
                }
                "none", "plain", "plaintext" -> {
                    // Plain SMTP, intended only for trusted internal relays.
                }
                else -> {
                    put("mail.smtp.starttls.enable", "true")
                    put("mail.smtp.starttls.required", "true")
                }
            }
            // Hostname verification against the SMTP server's cert + modern TLS only.
            put("mail.smtp.ssl.checkserveridentity", "true")
            put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3")
            put("mail.smtps.ssl.checkserveridentity", "true")
            put("mail.smtps.ssl.protocols", "TLSv1.2 TLSv1.3")
        }

        return if (authEnabled) {
            Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    return PasswordAuthentication(username, password)
                }
            })
        } else {
            Session.getInstance(props)
        }
    }

    private fun getImapStore(): Store {
        val host = parameters.parameter("imap_host", "imap.gmail.com")
        val port = parameters.parameter("imap_port", "993")
        val username = parameters.parameter("email_username", "")
        val password = parameters.parameter("email_password", "")

        val props = Properties().apply {
            put("mail.imap.host", host)
            put("mail.imap.port", port)
            put("mail.imap.ssl.enable", "true")
            // Enforce strict TLS on IMAP connections:
            //   - `checkserveridentity` turns on hostname verification against the cert SAN/CN.
            //     Jakarta Mail defaults this to `false` which allows MITM via any valid cert.
            //   - `ssl.protocols` restricts the handshake to TLS 1.2/1.3 (no SSLv3/TLS 1.0/1.1).
            //   - `ssl.trust=*` is intentionally NOT set; we use the JRE's default truststore.
            put("mail.imap.ssl.checkserveridentity", "true")
            put("mail.imap.ssl.protocols", "TLSv1.2 TLSv1.3")
            put("mail.imaps.ssl.checkserveridentity", "true")
            put("mail.imaps.ssl.protocols", "TLSv1.2 TLSv1.3")
        }

        val session = Session.getInstance(props)
        val store = session.getStore("imaps")
        store.connect(host, username, password)
        return store
    }

    @Tool
    @LLMDescription(
        "Send an email via SMTP. Supports plain text and HTML content, CC, BCC, and file attachments. " +
                "Requires smtp_host and a sender address. By default it uses SMTP AUTH with email_username/email_password; " +
                "set smtp_auth_enabled=false in agent configuration to use IP-authenticated SMTP relay. " +
                "Set smtp_security=none for trusted internal relays or smtp_security=ssl for port 465."
    )
    fun sendEmail(
        @LLMDescription("Recipient email address(es), comma-separated for multiple recipients")
        to: String,
        @LLMDescription("Email subject")
        subject: String,
        @LLMDescription("Email body content (plain text or HTML)")
        body: String,
        @LLMDescription("Whether the body is HTML (default false, plain text)")
        isHtml: Boolean = false,
        @LLMDescription("CC recipients, comma-separated (optional)")
        cc: String = "",
        @LLMDescription("BCC recipients, comma-separated (optional)")
        bcc: String = "",
        @LLMDescription("Comma-separated file paths to attach (optional)")
        attachments: String = "",
        @LLMDescription("Sender display name (optional)")
        fromName: String = "",
        @LLMDescription("Sender email address. Defaults to smtp_from_address/from_address/email_username from configuration")
        fromAddress: String = ""
    ): String {
        return try {
            val username = parameters.parameter("email_username", "")
            val password = parameters.parameter("email_password", "")
            val authEnabled = smtpAuthEnabled()
            val senderAddress = fromAddress.ifBlank {
                parameters.parameter(
                    "smtp_from_address",
                    parameters.parameter("from_address", username)
                )
            }.trim()
            if (authEnabled && username.isBlank()) return "Error: email_username not configured"
            if (authEnabled && password.isBlank()) return "Error: email_password not configured"
            if (senderAddress.isBlank()) return "Error: sender address not configured (fromAddress or smtp_from_address)"

            // Reject CRLF in any header-bound input to prevent SMTP header injection
            // (an attacker could otherwise smuggle Bcc/Reply-To headers via the body of `to`).
            for ((label, value) in listOf("to" to to, "cc" to cc, "bcc" to bcc, "subject" to subject, "fromName" to fromName, "fromAddress" to senderAddress)) {
                if (value.contains('\r') || value.contains('\n')) {
                    return "Error: invalid characters (CR/LF) in '$label' — header injection rejected"
                }
            }

            val session = getSmtpSession()
            val message = MimeMessage(session)

            val fromInternetAddress = if (fromName.isNotBlank()) {
                InternetAddress(senderAddress, fromName)
            } else {
                InternetAddress(senderAddress)
            }
            message.setFrom(fromInternetAddress)
            // strict=true forces InternetAddress to validate the syntax instead of silently
            // tolerating malformed addresses that could carry header smuggling payloads.
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to, true))

            if (cc.isNotBlank()) {
                message.setRecipients(Message.RecipientType.CC, InternetAddress.parse(cc, true))
            }
            if (bcc.isNotBlank()) {
                message.setRecipients(Message.RecipientType.BCC, InternetAddress.parse(bcc, true))
            }

            // Use setSubject(value, charset) so non-ASCII content is properly encoded as
            // RFC 2047 'encoded-word' instead of being inserted verbatim into the header.
            message.setSubject(subject, "UTF-8")

            if (attachments.isNotBlank()) {
                val multipart = MimeMultipart()

                // Body part
                val bodyPart = MimeBodyPart()
                if (isHtml) {
                    bodyPart.setContent(body, "text/html; charset=UTF-8")
                } else {
                    bodyPart.setText(body, "UTF-8")
                }
                multipart.addBodyPart(bodyPart)

                // Attachment parts — enforce per-file and aggregate size caps so the LLM
                // cannot trigger a 10GB attachment that OOM's the JVM or DoS's the SMTP
                // relay. Defaults (25 MB per file, 25 MB total) match Gmail's published
                // limits; operators can widen via `email_max_attachment_bytes` /
                // `email_max_total_attachment_bytes` parameters.
                val maxPerFile = parameters.parameter("email_max_attachment_bytes", (25L * 1024 * 1024).toString())
                    .toLongOrNull() ?: (25L * 1024 * 1024)
                val maxTotal = parameters.parameter("email_max_total_attachment_bytes", (25L * 1024 * 1024).toString())
                    .toLongOrNull() ?: (25L * 1024 * 1024)
                // Phase 11: explicit count cap on top of byte caps. A million-path
                // attachment string would OOM during `split` itself before any byte
                // counting begins. 100 is generous — Gmail's UI tops out around 25.
                val attachmentPaths = attachments
                    .split(",").map { it.trim() }.filter { it.isNotBlank() }
                if (attachmentPaths.size > EMAIL_MAX_ATTACHMENT_COUNT) {
                    return "Error: too many attachments (${attachmentPaths.size}); max $EMAIL_MAX_ATTACHMENT_COUNT"
                }
                var totalBytes = 0L
                attachmentPaths.forEach { filePath ->
                    val safeFile = try {
                        ToolPathSecurity.validateInputPath(filePath)
                    } catch (e: SecurityException) {
                        return "Error: attachment path rejected — ${e.message}"
                    }
                    if (!safeFile.exists()) return "Error: attachment not found: $filePath"
                    val size = safeFile.length()
                    if (size > maxPerFile) {
                        return "Error: attachment '$filePath' is ${size} bytes, exceeds per-file limit ${maxPerFile}"
                    }
                    totalBytes += size
                    if (totalBytes > maxTotal) {
                        return "Error: total attachment size ${totalBytes} exceeds limit ${maxTotal}"
                    }
                    val attachmentPart = MimeBodyPart()
                    attachmentPart.attachFile(safeFile)
                    multipart.addBodyPart(attachmentPart)
                }

                message.setContent(multipart)
            } else {
                if (isHtml) {
                    message.setContent(body, "text/html; charset=UTF-8")
                } else {
                    message.setText(body, "UTF-8")
                }
            }

            printlnColor(AnsiColor.CYAN, "[Email] Sending to: $to, Subject: $subject")
            Transport.send(message)
            "Email sent successfully to: $to"
        } catch (e: Exception) {
            "Error sending email: ${e.message}"
        }
    }

    @Tool
    @LLMDescription(
        "Read recent emails from the inbox via IMAP. Returns subject, from, date, and a preview of the body. " +
                "Requires imap_host, imap_port, email_username, email_password to be configured."
    )
    fun readEmails(
        @LLMDescription("Folder to read from (default 'INBOX')")
        folder: String = "INBOX",
        @LLMDescription("Maximum number of emails to retrieve (default 10, most recent first)")
        maxCount: Int = 10,
        @LLMDescription("Maximum body preview length per email in characters (default 500)")
        maxBodyLength: Int = 500
    ): String {
        return try {
            val store = getImapStore()
            try {
                val inboxFolder = store.getFolder(folder)
                inboxFolder.open(Folder.READ_ONLY)
                try {
                    val messageCount = inboxFolder.messageCount
                    if (messageCount == 0) {
                        return "No emails found in $folder."
                    }

                    val start = maxOf(1, messageCount - maxCount + 1)
                    val messages = inboxFolder.getMessages(start, messageCount)

                    val sb = StringBuilder()
                    sb.appendLine("Emails in $folder ($messageCount total, showing ${messages.size} most recent):")
                    sb.appendLine()

                    for (i in messages.indices.reversed()) {
                        val msg = messages[i]
                        sb.appendLine("--- Email ${messageCount - (messages.size - 1 - i)} ---")
                        sb.appendLine("From: ${msg.from?.joinToString(", ") ?: "Unknown"}")
                        sb.appendLine("Subject: ${msg.subject ?: "(no subject)"}")
                        sb.appendLine("Date: ${msg.sentDate}")

                        val bodyText = getTextContent(msg)
                        val preview = if (bodyText.length > maxBodyLength) {
                            bodyText.take(maxBodyLength) + "..."
                        } else {
                            bodyText
                        }
                        sb.appendLine("Body preview: $preview")
                        sb.appendLine()
                    }

                    sb.toString()
                } finally {
                    inboxFolder.close(false)
                }
            } finally {
                store.close()
            }
        } catch (e: Exception) {
            "Error reading emails: ${e.message}"
        }
    }

    @Tool
    @LLMDescription(
        "Search emails by subject, sender, or body text. Returns matching email summaries."
    )
    fun searchEmails(
        @LLMDescription("Search query (searches in subject, from, and body)")
        query: String,
        @LLMDescription("Folder to search in (default 'INBOX')")
        folder: String = "INBOX",
        @LLMDescription("Maximum number of results (default 20)")
        maxResults: Int = 20
    ): String {
        return try {
            val store = getImapStore()
            try {
                val inboxFolder = store.getFolder(folder)
                inboxFolder.open(Folder.READ_ONLY)
                try {
                    val searchTerm = OrTerm(
                        arrayOf(
                            SubjectTerm(query),
                            FromStringTerm(query),
                            BodyTerm(query)
                        )
                    )

                    val messages = inboxFolder.search(searchTerm)
                    val sb = StringBuilder()
                    sb.appendLine("Search results for '$query' in $folder: ${messages.size} match(es)")
                    sb.appendLine()

                    val limit = minOf(messages.size, maxResults)
                    for (i in (messages.size - limit) until messages.size) {
                        val msg = messages[i]
                        sb.appendLine("- Subject: ${msg.subject ?: "(no subject)"}")
                        sb.appendLine("  From: ${msg.from?.joinToString(", ") ?: "Unknown"}")
                        sb.appendLine("  Date: ${msg.sentDate}")
                        sb.appendLine()
                    }

                    sb.toString()
                } finally {
                    inboxFolder.close(false)
                }
            } finally {
                store.close()
            }
        } catch (e: Exception) {
            "Error searching emails: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("List available email folders (mailboxes).")
    fun listEmailFolders(): String {
        return try {
            val store = getImapStore()
            try {
                val defaultFolder = store.defaultFolder
                val folders = defaultFolder.list("*")

                val sb = StringBuilder()
                sb.appendLine("Email folders:")
                for (f in folders) {
                    val count = try {
                        f.open(Folder.READ_ONLY)
                        try {
                            " (${f.messageCount} messages)"
                        } finally {
                            f.close(false)
                        }
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to close email folder/store" }
                        ""
                    }
                    sb.appendLine("  - ${f.fullName}$count")
                }

                sb.toString()
            } finally {
                store.close()
            }
        } catch (e: Exception) {
            "Error listing folders: ${e.message}"
        }
    }

    private fun getTextContent(message: Message): String {
        return try {
            when (val content = message.content) {
                is String -> content
                is Multipart -> {
                    val sb = StringBuilder()
                    for (i in 0 until content.count) {
                        val part = content.getBodyPart(i)
                        if (part.isMimeType("text/plain")) {
                            sb.append(part.content as? String ?: "")
                        } else if (part.isMimeType("text/html")) {
                            val html = part.content as? String ?: ""
                            sb.append(html.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim())
                        }
                    }
                    sb.toString().ifBlank { "(no text content)" }
                }

                else -> "(unsupported content type)"
            }
        } catch (e: Exception) {
            "(error reading content: ${e.message})"
        }
    }

    companion object {
        /** Max attachments per `sendEmail` call (Phase 11). */
        internal const val EMAIL_MAX_ATTACHMENT_COUNT = 100
    }
}
