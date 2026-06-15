package com.fartech.agents.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("UserInteractionTools 测试")
class UserInteractionToolsTest {

    @AfterEach
    fun cleanup() {
        UserInteractionRegistry.cleanupExecution("test-exec-1")
        UserInteractionRegistry.cleanupExecution("test-exec-2")
    }

    // ========== UserInteractionTools ==========

    @Nested
    @DisplayName("tellUser 工具")
    inner class TellUserTests {

        @Test
        fun `tellUser should call handler sendMessage and return success`() {
            val messages = mutableListOf<String>()
            val handler = object : UserInteractionHandler {
                override fun sendMessage(message: String) { messages.add(message) }
                override suspend fun askQuestion(question: String, timeoutMs: Long): String = ""
            }
            val tools = UserInteractionTools(handler)
            val result = tools.tellUser("Hello user!")
            assertEquals("Message sent to user successfully.", result)
            assertEquals(listOf("Hello user!"), messages)
        }

        @Test
        fun `tellUser should handle empty message`() {
            val messages = mutableListOf<String>()
            val handler = object : UserInteractionHandler {
                override fun sendMessage(message: String) { messages.add(message) }
                override suspend fun askQuestion(question: String, timeoutMs: Long): String = ""
            }
            val tools = UserInteractionTools(handler)
            val result = tools.tellUser("")
            assertEquals("Message sent to user successfully.", result)
            assertEquals(listOf(""), messages)
        }

        @Test
        fun `tellUser should handle message with special characters`() {
            val messages = mutableListOf<String>()
            val handler = object : UserInteractionHandler {
                override fun sendMessage(message: String) { messages.add(message) }
                override suspend fun askQuestion(question: String, timeoutMs: Long): String = ""
            }
            val tools = UserInteractionTools(handler)
            tools.tellUser("消息包含特殊字符: \"引号\" 和 \n换行")
            assertEquals(1, messages.size)
            assertTrue(messages[0].contains("\"引号\""))
        }
    }

    @Nested
    @DisplayName("askUser 工具")
    inner class AskUserTests {

        @Test
        fun `askUser should call handler askQuestion and return response`() = runBlocking {
            val handler = object : UserInteractionHandler {
                override fun sendMessage(message: String) {}
                override suspend fun askQuestion(question: String, timeoutMs: Long): String {
                    return "用户回答: $question"
                }
            }
            val tools = UserInteractionTools(handler)
            val result = tools.askUser("你叫什么名字？")
            assertEquals("用户回答: 你叫什么名字？", result)
        }
    }

    // ========== UserInteractionRegistry ==========

    @Nested
    @DisplayName("UserInteractionRegistry 测试")
    inner class RegistryTests {

        @Test
        fun `generateRequestId should create unique IDs`() {
            val id1 = UserInteractionRegistry.generateRequestId("exec1", "step1")
            val id2 = UserInteractionRegistry.generateRequestId("exec1", "step1")
            assertNotEquals(id1, id2)
            assertTrue(id1.startsWith("uir-exec1-step1-"))
            assertTrue(id2.startsWith("uir-exec1-step1-"))
        }

        @Test
        fun `registerRequest and submitReply should work`() = runBlocking {
            val deferred = CompletableDeferred<String>()
            val request = UserInteractionRequest(
                requestId = "test-req-1",
                executionId = "test-exec-1",
                stepName = "step1",
                message = "测试问题",
                requiresResponse = true
            )
            UserInteractionRegistry.registerRequest(request, deferred)

            assertTrue(UserInteractionRegistry.hasPendingRequest("test-req-1"))

            val success = UserInteractionRegistry.submitReply("test-req-1", "测试回答")
            assertTrue(success)
            assertEquals("测试回答", deferred.await())

            assertFalse(UserInteractionRegistry.hasPendingRequest("test-req-1"))
        }

        @Test
        fun `submitReply should return false for non-existent request`() {
            val result = UserInteractionRegistry.submitReply("non-existent", "reply")
            assertFalse(result)
        }

        @Test
        fun `cancelRequest should cancel the deferred`() {
            val deferred = CompletableDeferred<String>()
            val request = UserInteractionRequest(
                requestId = "test-req-cancel",
                executionId = "test-exec-1",
                stepName = "step1",
                message = "取消测试",
                requiresResponse = true
            )
            UserInteractionRegistry.registerRequest(request, deferred)
            assertTrue(UserInteractionRegistry.hasPendingRequest("test-req-cancel"))

            UserInteractionRegistry.cancelRequest("test-req-cancel")
            assertFalse(UserInteractionRegistry.hasPendingRequest("test-req-cancel"))
            assertTrue(deferred.isCancelled)
        }

        @Test
        fun `getPendingRequests should filter by executionId`() {
            val deferred1 = CompletableDeferred<String>()
            val deferred2 = CompletableDeferred<String>()
            val deferred3 = CompletableDeferred<String>()

            UserInteractionRegistry.registerRequest(
                UserInteractionRequest("req-1", "test-exec-1", "step1", "Q1", true),
                deferred1
            )
            UserInteractionRegistry.registerRequest(
                UserInteractionRequest("req-2", "test-exec-1", "step2", "Q2", true),
                deferred2
            )
            UserInteractionRegistry.registerRequest(
                UserInteractionRequest("req-3", "test-exec-2", "step1", "Q3", true),
                deferred3
            )

            val exec1Requests = UserInteractionRegistry.getPendingRequests("test-exec-1")
            assertEquals(2, exec1Requests.size)
            assertTrue(exec1Requests.any { it.requestId == "req-1" })
            assertTrue(exec1Requests.any { it.requestId == "req-2" })

            val exec2Requests = UserInteractionRegistry.getPendingRequests("test-exec-2")
            assertEquals(1, exec2Requests.size)
            assertEquals("req-3", exec2Requests[0].requestId)
        }

        @Test
        fun `cleanupExecution should remove all requests for an execution`() {
            val deferred1 = CompletableDeferred<String>()
            val deferred2 = CompletableDeferred<String>()

            UserInteractionRegistry.registerRequest(
                UserInteractionRequest("req-clean-1", "test-exec-1", "step1", "Q1", true),
                deferred1
            )
            UserInteractionRegistry.registerRequest(
                UserInteractionRequest("req-clean-2", "test-exec-1", "step2", "Q2", true),
                deferred2
            )

            assertTrue(UserInteractionRegistry.hasPendingRequest("req-clean-1"))
            assertTrue(UserInteractionRegistry.hasPendingRequest("req-clean-2"))

            UserInteractionRegistry.cleanupExecution("test-exec-1")

            assertFalse(UserInteractionRegistry.hasPendingRequest("req-clean-1"))
            assertFalse(UserInteractionRegistry.hasPendingRequest("req-clean-2"))
            assertTrue(deferred1.isCancelled)
            assertTrue(deferred2.isCancelled)
        }

        @Test
        fun `cleanupExecution should not affect other executions`() {
            val deferred1 = CompletableDeferred<String>()
            val deferred2 = CompletableDeferred<String>()

            UserInteractionRegistry.registerRequest(
                UserInteractionRequest("req-x1", "test-exec-1", "step1", "Q1", true),
                deferred1
            )
            UserInteractionRegistry.registerRequest(
                UserInteractionRequest("req-x2", "test-exec-2", "step1", "Q2", true),
                deferred2
            )

            UserInteractionRegistry.cleanupExecution("test-exec-1")

            assertFalse(UserInteractionRegistry.hasPendingRequest("req-x1"))
            assertTrue(UserInteractionRegistry.hasPendingRequest("req-x2"))
        }

        @Test
        fun `submitReply after cancel should return false`() {
            val deferred = CompletableDeferred<String>()
            UserInteractionRegistry.registerRequest(
                UserInteractionRequest("req-post-cancel", "test-exec-1", "step1", "Q", true),
                deferred
            )
            UserInteractionRegistry.cancelRequest("req-post-cancel")

            val result = UserInteractionRegistry.submitReply("req-post-cancel", "late reply")
            assertFalse(result)
        }

        @Test
        fun `double submit should return false on second attempt`() = runBlocking {
            val deferred = CompletableDeferred<String>()
            UserInteractionRegistry.registerRequest(
                UserInteractionRequest("req-double", "test-exec-1", "step1", "Q", true),
                deferred
            )

            val first = UserInteractionRegistry.submitReply("req-double", "first reply")
            assertTrue(first)
            assertEquals("first reply", deferred.await())

            val second = UserInteractionRegistry.submitReply("req-double", "second reply")
            assertFalse(second)
        }
    }

    // ========== UserInteractionRequest ==========

    @Nested
    @DisplayName("UserInteractionRequest 数据类")
    inner class RequestTests {

        @Test
        fun `should create request with all fields`() {
            val request = UserInteractionRequest(
                requestId = "req-1",
                executionId = "exec-1",
                stepName = "step1",
                message = "测试问题",
                requiresResponse = true,
                timestamp = 1234567890L
            )
            assertEquals("req-1", request.requestId)
            assertEquals("exec-1", request.executionId)
            assertEquals("step1", request.stepName)
            assertEquals("测试问题", request.message)
            assertTrue(request.requiresResponse)
            assertEquals(1234567890L, request.timestamp)
        }

        @Test
        fun `should have default timestamp`() {
            val before = System.currentTimeMillis()
            val request = UserInteractionRequest(
                requestId = "req-1",
                executionId = "exec-1",
                stepName = "step1",
                message = "Q",
                requiresResponse = false
            )
            val after = System.currentTimeMillis()
            assertTrue(request.timestamp in before..after)
        }
    }

    // ========== 并发安全 ==========

    @Nested
    @DisplayName("并发安全测试")
    inner class ConcurrencyTests {

        @Test
        fun `concurrent register and submit should be thread-safe`() = runBlocking {
            val deferreds = (1..10).map { i ->
                val deferred = CompletableDeferred<String>()
                UserInteractionRegistry.registerRequest(
                    UserInteractionRequest("conc-$i", "test-exec-1", "step$i", "Q$i", true),
                    deferred
                )
                deferred
            }

            // Submit replies
            val submitResults = (1..10).map { i ->
                UserInteractionRegistry.submitReply("conc-$i", "reply-$i")
            }
            assertTrue(submitResults.all { it })

            // Verify all deferreds completed
            deferreds.forEachIndexed { index, deferred ->
                assertEquals("reply-${index + 1}", deferred.await())
            }

            UserInteractionRegistry.cleanupExecution("test-exec-1")
        }
    }
}
