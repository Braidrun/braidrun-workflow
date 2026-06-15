package com.fartech.agents.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.fartech.ftapp2.commonsKt.AnsiColor
import com.fartech.ftapp2.commonsKt.ConfigurationParameter
import com.fartech.ftapp2.commonsKt.parameter
import com.fartech.ftapp2.commonsKt.printlnColor
import com.microsoft.playwright.*
import com.microsoft.playwright.options.Cookie
import com.microsoft.playwright.options.ScreenshotType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap

@LLMDescription("Toolset for browser automation using Playwright, including navigation, interaction, and screenshots")
class BrowserTools(
    private val parameters: List<ConfigurationParameter> = emptyList()
) : ToolSet {

    companion object {
        // Phase 11 hardening: although `getBrowser` and `closeAll` are both
        // `@Synchronized` on the same class monitor, callers obtain a reference to
        // `browser` outside the lock (via `getBrowser`) and then call `.newContext()`
        // / `.newPage()` on it. Without `@Volatile`, the JIT is free to reorder
        // memory writes; a thread that observed `browser != null` could see a
        // partially-constructed `Browser` instance under aggressive optimisation.
        // The volatile barrier guarantees a happens-before edge between
        // initialisation and the read.
        @Volatile private var playwright: Playwright? = null
        @Volatile private var browser: Browser? = null
        private val contexts = ConcurrentHashMap<String, BrowserContext>()
        private val pages = ConcurrentHashMap<String, Page>()

        /** Cap on the cookiesJson payload accepted by browser_set_cookies. */
        private const val MAX_COOKIES_JSON_BYTES = 256 * 1024
        /** Cap on the number of cookies set in a single call. */
        private const val MAX_COOKIES_PER_CALL = 200

        init {
            Runtime.getRuntime().addShutdownHook(Thread {
                try { closeAll() } catch (_: Exception) {}
            })
        }

        @Synchronized
        private fun getBrowser(parameters: List<ConfigurationParameter>): Browser {
            val pw = playwright ?: Playwright.create().also { playwright = it }
            val existing = browser
            if (existing != null) return existing

            val headlessFromParams = if (parameters.any { it.key == "PLAYWRIGHT_HEADLESS" }) {
                parameters.parameter("PLAYWRIGHT_HEADLESS", true)
            } else null
            val headlessFromEnv = System.getenv("PLAYWRIGHT_HEADLESS")?.toBoolean()

            val headless = headlessFromParams ?: headlessFromEnv ?: true
            val args = parameters.parameter("PLAYWRIGHT_ARGS", listOf("--no-sandbox", "--disable-setuid-sandbox"))
            try {
                val launched = pw.chromium().launch(
                    BrowserType.LaunchOptions()
                        .setHeadless(headless)
                        .setArgs(args)
                )
                browser = launched
                return launched
            } catch (e: Exception) {
                playwright?.close()
                playwright = null
                throw e
            }
        }

        /**
         * Closes all browser instances and contexts.
         *
         * MUST be synchronized on the same monitor as [getBrowser]. Without synchronization,
         * a concurrent `getBrowser` call could observe a half-cleared `playwright`/`browser`
         * pair and hand out a dead reference, or (worse) create a *second* Playwright while
         * the shutdown hook closes the first.
         */
        @Synchronized
        fun closeAll() {
            pages.values.forEach {
                try {
                    it.close()
                } catch (e: Exception) {
                    printlnColor(AnsiColor.RED, "[Browser] Error closing page: ${e.message}")
                }
            }
            pages.clear()
            contexts.values.forEach {
                try {
                    it.close()
                } catch (e: Exception) {
                    printlnColor(AnsiColor.RED, "[Browser] Error closing context: ${e.message}")
                }
            }
            contexts.clear()
            try {
                browser?.close()
            } catch (e: Exception) {
                printlnColor(AnsiColor.RED, "[Browser] Error closing browser: ${e.message}")
            }
            browser = null
            try {
                playwright?.close()
            } catch (e: Exception) {
                printlnColor(AnsiColor.RED, "[Browser] Error closing playwright: ${e.message}")
            }
            playwright = null
        }
    }

    private fun getOrCreatePage(contextId: String): Page {
        val browser = getBrowser(parameters)
        val userAgent = parameters.parameter(
            "PLAYWRIGHT_USER_AGENT",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        )
        // computeIfAbsent (atomic), NOT getOrPut (get-then-putIfAbsent): a race on
        // getOrPut would launch two real BrowserContexts/Pages and the losing one —
        // a live headless tab — would never be stored nor closed.
        val context = contexts.computeIfAbsent(contextId) {
            browser.newContext(
                Browser.NewContextOptions()
                    .setUserAgent(userAgent)
            )
        }
        return pages.computeIfAbsent(contextId) { context.newPage() }
    }

    @Tool
    @LLMDescription("Navigate to a URL in the browser")
    suspend fun browser_navigate(
        @LLMDescription("URL to navigate to (http:// or https:// only)")
        url: String,
        @LLMDescription("Optional context ID to maintain multiple browser sessions (default: 'default')")
        contextId: String = "default"
    ): String = withContext(Dispatchers.IO) {
        try {
            // Reject schemes that read local state (file://) or execute code
            // in an already-loaded page (javascript:, data:, vbscript:, blob:).
            // Without this gate an LLM could stage a `file:///etc/passwd`
            // load and feed the contents back through `browser_get_content`.
            // We deliberately do NOT plug in UrlSafety's SSRF resolver here:
            // browser-driven workflows legitimately drive against localhost
            // (test harnesses, internal admin UIs); preserve that.
            require(isAllowedBrowserScheme(url)) {
                "URL scheme rejected — browser_navigate accepts http(s) only, got: $url"
            }
            val page = getOrCreatePage(contextId)
            val response = page.navigate(url)
            "✅ Navigated to $url (Status: ${response?.status() ?: "unknown"})"
        } catch (e: Exception) {
            "❌ Navigation failed: ${e.message}"
        }
    }

    private fun isAllowedBrowserScheme(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        val lower = trimmed.lowercase()
        // No scheme = relative path; reject — Playwright will silently treat
        // these as "about:blank#path" or refuse to navigate.
        val schemeEnd = lower.indexOf(':')
        if (schemeEnd <= 0) return false
        val scheme = lower.substring(0, schemeEnd)
        return scheme == "http" || scheme == "https"
    }

    @Tool
    @LLMDescription("Take a screenshot of the current page")
    suspend fun browser_screenshot(
        @LLMDescription("Path to save the screenshot image (e.g., 'screenshot.png')")
        path: String,
        @LLMDescription("Optional context ID")
        contextId: String = "default",
        @LLMDescription("Whether to take a full page screenshot")
        fullPage: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        try {
            // Phase 9 (2026-05): validate the destination path against the standard
            // output-path guard. Pre-Phase-9 the LLM-supplied `path` flowed straight
            // into Playwright's `Paths.get(path)` — `path = "/etc/cron.d/payload"` or
            // `path = "../../.ssh/authorized_keys"` would have been honored.
            val safeFile = ToolPathSecurity.validateOutputPath(path)
            val page = getOrCreatePage(contextId)
            page.screenshot(
                Page.ScreenshotOptions()
                    .setPath(safeFile.toPath())
                    .setFullPage(fullPage)
                    .setType(ScreenshotType.PNG)
            )
            "✅ Screenshot saved to ${safeFile.absolutePath}"
        } catch (e: SecurityException) {
            "❌ Screenshot path rejected: ${e.message}"
        } catch (e: Exception) {
            "❌ Screenshot failed: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Click an element on the page using a CSS selector or XPath")
    suspend fun browser_click(
        @LLMDescription("CSS selector or XPath of the element to click")
        selector: String,
        @LLMDescription("Optional context ID")
        contextId: String = "default"
    ): String = withContext(Dispatchers.IO) {
        try {
            val page = getOrCreatePage(contextId)
            page.click(selector)
            "✅ Clicked element: $selector"
        } catch (e: Exception) {
            "❌ Click failed: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Fill an input field with text")
    suspend fun browser_fill(
        @LLMDescription("CSS selector or XPath of the input field")
        selector: String,
        @LLMDescription("Text to enter into the field")
        value: String,
        @LLMDescription("Optional context ID")
        contextId: String = "default"
    ): String = withContext(Dispatchers.IO) {
        try {
            val page = getOrCreatePage(contextId)
            page.fill(selector, value)
            "✅ Filled $selector with value"
        } catch (e: Exception) {
            "❌ Fill failed: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Get the HTML content of the current page")
    suspend fun browser_get_content(
        @LLMDescription("Optional context ID")
        contextId: String = "default"
    ): String = withContext(Dispatchers.IO) {
        try {
            val page = getOrCreatePage(contextId)
            val content = page.content()
            "✅ Page content retrieved (${content.length} characters):\n\n${content.take(5000)}${if (content.length > 5000) "..." else ""}"
        } catch (e: Exception) {
            "❌ Failed to get content: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Execute JavaScript code in the context of the page")
    suspend fun browser_evaluate(
        @LLMDescription("JavaScript code to execute")
        script: String,
        @LLMDescription("Optional context ID")
        contextId: String = "default"
    ): String = withContext(Dispatchers.IO) {
        try {
            val page = getOrCreatePage(contextId)
            val result = page.evaluate(script)
            "✅ Script execution result: ${result?.toString() ?: "null"}"
        } catch (e: Exception) {
            "❌ Script execution failed: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Press a key on the keyboard (e.g., 'Enter', 'Tab', 'ArrowDown')")
    suspend fun browser_press_key(
        @LLMDescription("Key to press")
        key: String,
        @LLMDescription("Optional context ID")
        contextId: String = "default"
    ): String = withContext(Dispatchers.IO) {
        try {
            val page = getOrCreatePage(contextId)
            page.keyboard().press(key)
            "✅ Pressed key: $key"
        } catch (e: Exception) {
            "❌ Key press failed: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Wait for a specific amount of time or for a selector to appear")
    suspend fun browser_wait(
        @LLMDescription("Timeout in milliseconds, or a CSS selector to wait for")
        waitCondition: String,
        @LLMDescription("Optional context ID")
        contextId: String = "default"
    ): String = withContext(Dispatchers.IO) {
        try {
            val page = getOrCreatePage(contextId)
            val timeout = waitCondition.toDoubleOrNull()
            if (timeout != null) {
                page.waitForTimeout(timeout)
                "✅ Waited for ${timeout}ms"
            } else {
                page.waitForSelector(waitCondition)
                "✅ Selector appeared: $waitCondition"
            }
        } catch (e: Exception) {
            "❌ Wait failed: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Get the current URL of the page")
    suspend fun browser_get_url(
        @LLMDescription("Optional context ID")
        contextId: String = "default"
    ): String = withContext(Dispatchers.IO) {
        try {
            val page = getOrCreatePage(contextId)
            "✅ Current URL: ${page.url()}"
        } catch (e: Exception) {
            "❌ Failed to get URL: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Get the title of the current page")
    suspend fun browser_get_title(
        @LLMDescription("Optional context ID")
        contextId: String = "default"
    ): String = withContext(Dispatchers.IO) {
        try {
            val page = getOrCreatePage(contextId)
            "✅ Page title: ${page.title()}"
        } catch (e: Exception) {
            "❌ Failed to get title: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Go back in history")
    suspend fun browser_go_back(
        @LLMDescription("Optional context ID")
        contextId: String = "default"
    ): String = withContext(Dispatchers.IO) {
        try {
            val page = getOrCreatePage(contextId)
            page.goBack()
            "✅ Navigated back"
        } catch (e: Exception) {
            "❌ Go back failed: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Go forward in history")
    suspend fun browser_go_forward(
        @LLMDescription("Optional context ID")
        contextId: String = "default"
    ): String = withContext(Dispatchers.IO) {
        try {
            val page = getOrCreatePage(contextId)
            page.goForward()
            "✅ Navigated forward"
        } catch (e: Exception) {
            "❌ Go forward failed: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Reload the current page")
    suspend fun browser_reload(
        @LLMDescription("Optional context ID")
        contextId: String = "default"
    ): String = withContext(Dispatchers.IO) {
        try {
            val page = getOrCreatePage(contextId)
            page.reload()
            "✅ Page reloaded"
        } catch (e: Exception) {
            "❌ Reload failed: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Hover over an element using a CSS selector or XPath")
    suspend fun browser_hover(
        @LLMDescription("CSS selector or XPath of the element to hover")
        selector: String,
        @LLMDescription("Optional context ID")
        contextId: String = "default"
    ): String = withContext(Dispatchers.IO) {
        try {
            val page = getOrCreatePage(contextId)
            page.hover(selector)
            "✅ Hovered over: $selector"
        } catch (e: Exception) {
            "❌ Hover failed: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Focus an element using a CSS selector or XPath")
    suspend fun browser_focus(
        @LLMDescription("CSS selector or XPath of the element to focus")
        selector: String,
        @LLMDescription("Optional context ID")
        contextId: String = "default"
    ): String = withContext(Dispatchers.IO) {
        try {
            val page = getOrCreatePage(contextId)
            page.focus(selector)
            "✅ Focused element: $selector"
        } catch (e: Exception) {
            "❌ Focus failed: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Select an option in a dropdown menu")
    suspend fun browser_select_option(
        @LLMDescription("CSS selector or XPath of the select element")
        selector: String,
        @LLMDescription("Value or label of the option to select")
        value: String,
        @LLMDescription("Optional context ID")
        contextId: String = "default"
    ): String = withContext(Dispatchers.IO) {
        try {
            val page = getOrCreatePage(contextId)
            page.selectOption(selector, value)
            "✅ Selected option '$value' in $selector"
        } catch (e: Exception) {
            "❌ Select failed: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Check a checkbox or radio button")
    suspend fun browser_check(
        @LLMDescription("CSS selector or XPath of the element to check")
        selector: String,
        @LLMDescription("Optional context ID")
        contextId: String = "default"
    ): String = withContext(Dispatchers.IO) {
        try {
            val page = getOrCreatePage(contextId)
            page.check(selector)
            "✅ Checked element: $selector"
        } catch (e: Exception) {
            "❌ Check failed: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Uncheck a checkbox")
    suspend fun browser_uncheck(
        @LLMDescription("CSS selector or XPath of the element to uncheck")
        selector: String,
        @LLMDescription("Optional context ID")
        contextId: String = "default"
    ): String = withContext(Dispatchers.IO) {
        try {
            val page = getOrCreatePage(contextId)
            page.uncheck(selector)
            "✅ Unchecked element: $selector"
        } catch (e: Exception) {
            "❌ Uncheck failed: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Get the value of an attribute for an element")
    suspend fun browser_get_attribute(
        @LLMDescription("CSS selector or XPath of the element")
        selector: String,
        @LLMDescription("Name of the attribute to retrieve")
        attribute: String,
        @LLMDescription("Optional context ID")
        contextId: String = "default"
    ): String = withContext(Dispatchers.IO) {
        try {
            val page = getOrCreatePage(contextId)
            val value = page.getAttribute(selector, attribute)
            "✅ Attribute '$attribute' for $selector: ${value ?: "null"}"
        } catch (e: Exception) {
            "❌ Get attribute failed: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Get the text content of an element")
    suspend fun browser_get_text(
        @LLMDescription("CSS selector or XPath of the element")
        selector: String,
        @LLMDescription("Optional context ID")
        contextId: String = "default"
    ): String = withContext(Dispatchers.IO) {
        try {
            val page = getOrCreatePage(contextId)
            val text = page.textContent(selector)
            "✅ Text content for $selector: ${text?.trim() ?: ""}"
        } catch (e: Exception) {
            "❌ Get text failed: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Check if an element is visible")
    suspend fun browser_is_visible(
        @LLMDescription("CSS selector or XPath of the element")
        selector: String,
        @LLMDescription("Optional context ID")
        contextId: String = "default"
    ): String = withContext(Dispatchers.IO) {
        try {
            val page = getOrCreatePage(contextId)
            val isVisible = page.isVisible(selector)
            "✅ Element $selector is visible: $isVisible"
        } catch (e: Exception) {
            "❌ Check visibility failed: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Get all cookies for the current browser context")
    suspend fun browser_get_cookies(
        @LLMDescription("Optional context ID")
        contextId: String = "default"
    ): String = withContext(Dispatchers.IO) {
        try {
            val page = getOrCreatePage(contextId)
            val cookies = page.context().cookies()
            val json = Json.encodeToString(cookies.map {
                buildJsonObject {
                    put("name", it.name)
                    put("value", it.value)
                    put("domain", it.domain)
                    put("path", it.path)
                    put("expires", it.expires ?: -1.0)
                    put("httpOnly", it.httpOnly)
                    put("secure", it.secure)
                    put("sameSite", it.sameSite.toString())
                }
            })
            "✅ Cookies retrieved: $json"
        } catch (e: Exception) {
            "❌ Failed to get cookies: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Clear all cookies for the current browser context")
    suspend fun browser_clear_cookies(
        @LLMDescription("Optional context ID")
        contextId: String = "default"
    ): String = withContext(Dispatchers.IO) {
        try {
            val page = getOrCreatePage(contextId)
            page.context().clearCookies()
            "✅ Cookies cleared for context $contextId"
        } catch (e: Exception) {
            "❌ Failed to clear cookies: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Set cookies for the current browser context")
    suspend fun browser_set_cookies(
        @LLMDescription("JSON string representing a list of cookies. Each cookie should have 'name', 'value', and 'url' or 'domain' and 'path'.")
        cookiesJson: String,
        @LLMDescription("Optional context ID")
        contextId: String = "default"
    ): String = withContext(Dispatchers.IO) {
        try {
            // Cap input size before parsing — an LLM passing a multi-MB
            // cookies blob would otherwise force kotlinx.serialization to
            // build the whole tree in heap, then build N individual Cookie
            // objects, then send N cookies through Playwright's IPC bridge.
            // 256 KiB is enough for hundreds of legitimate cookies.
            require(cookiesJson.length <= MAX_COOKIES_JSON_BYTES) {
                "cookiesJson (${cookiesJson.length} chars) exceeds MAX_COOKIES_JSON_BYTES=$MAX_COOKIES_JSON_BYTES"
            }
            val page = getOrCreatePage(contextId)
            val jsonArray = Json.parseToJsonElement(cookiesJson).jsonArray
            require(jsonArray.size <= MAX_COOKIES_PER_CALL) {
                "cookiesJson contains ${jsonArray.size} cookies; max allowed per call is $MAX_COOKIES_PER_CALL"
            }
            val cookies = jsonArray.map { element ->
                val obj = element.jsonObject
                val cookie =
                    Cookie(obj["name"]?.jsonPrimitive?.content ?: "", obj["value"]?.jsonPrimitive?.content ?: "")
                obj["url"]?.jsonPrimitive?.content?.let { cookie.setUrl(it) }
                obj["domain"]?.jsonPrimitive?.content?.let { cookie.setDomain(it) }
                obj["path"]?.jsonPrimitive?.content?.let { cookie.setPath(it) }
                obj["expires"]?.jsonPrimitive?.doubleOrNull?.let { cookie.setExpires(it) }
                obj["httpOnly"]?.jsonPrimitive?.booleanOrNull?.let { cookie.setHttpOnly(it) }
                obj["secure"]?.jsonPrimitive?.booleanOrNull?.let { cookie.setSecure(it) }
                // sameSite skipped for now due to enum resolution issues
                cookie
            }
            page.context().addCookies(cookies)
            "✅ Added ${cookies.size} cookies to context $contextId"
        } catch (e: Exception) {
            "❌ Failed to set cookies: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Scroll the page by a specific amount")
    suspend fun browser_scroll(
        @LLMDescription("Horizontal scroll amount in pixels")
        x: Int = 0,
        @LLMDescription("Vertical scroll amount in pixels")
        y: Int = 0,
        @LLMDescription("Optional context ID")
        contextId: String = "default"
    ): String = withContext(Dispatchers.IO) {
        try {
            val page = getOrCreatePage(contextId)
            page.evaluate("window.scrollBy($x, $y)")
            "✅ Scrolled by ($x, $y)"
        } catch (e: Exception) {
            "❌ Scroll failed: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Scroll an element into view")
    suspend fun browser_scroll_to_element(
        @LLMDescription("CSS selector or XPath of the element to scroll into view")
        selector: String,
        @LLMDescription("Optional context ID")
        contextId: String = "default"
    ): String = withContext(Dispatchers.IO) {
        try {
            val page = getOrCreatePage(contextId)
            page.locator(selector).scrollIntoViewIfNeeded()
            "✅ Scrolled to element: $selector"
        } catch (e: Exception) {
            "❌ Scroll to element failed: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Click an element that contains the specified text")
    suspend fun browser_click_by_text(
        @LLMDescription("Text contained within the element to click")
        text: String,
        @LLMDescription("Optional context ID")
        contextId: String = "default"
    ): String = withContext(Dispatchers.IO) {
        try {
            val page = getOrCreatePage(contextId)
            page.click("text=$text")
            "✅ Clicked element with text: $text"
        } catch (e: Exception) {
            "❌ Click failed: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Double click an element on the page")
    suspend fun browser_double_click(
        @LLMDescription("CSS selector or XPath of the element to double click")
        selector: String,
        @LLMDescription("Optional context ID")
        contextId: String = "default"
    ): String = withContext(Dispatchers.IO) {
        try {
            val page = getOrCreatePage(contextId)
            page.dblclick(selector)
            "✅ Double clicked element: $selector"
        } catch (e: Exception) {
            "❌ Double click failed: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Type text into an input field character by character, simulating real user typing")
    suspend fun browser_type(
        @LLMDescription("CSS selector or XPath of the input field")
        selector: String,
        @LLMDescription("Text to type into the field")
        value: String,
        @LLMDescription("Delay between key presses in milliseconds")
        delay: Int = 100,
        @LLMDescription("Optional context ID")
        contextId: String = "default"
    ): String = withContext(Dispatchers.IO) {
        try {
            val page = getOrCreatePage(contextId)
            page.locator(selector)
                .pressSequentially(value, Locator.PressSequentiallyOptions().setDelay(delay.toDouble()))
            "✅ Typed '$value' into $selector (delay: ${delay}ms)"
        } catch (e: Exception) {
            "❌ Type failed: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Get the number of elements matching a selector")
    suspend fun browser_get_elements_count(
        @LLMDescription("CSS selector or XPath to count elements for")
        selector: String,
        @LLMDescription("Optional context ID")
        contextId: String = "default"
    ): String = withContext(Dispatchers.IO) {
        try {
            val page = getOrCreatePage(contextId)
            val count = page.locator(selector).count()
            "✅ Found $count elements matching $selector"
        } catch (e: Exception) {
            "❌ Failed to count elements: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Get text content of all elements matching a selector")
    suspend fun browser_get_all_text(
        @LLMDescription("CSS selector or XPath")
        selector: String,
        @LLMDescription("Optional context ID")
        contextId: String = "default"
    ): String = withContext(Dispatchers.IO) {
        try {
            val page = getOrCreatePage(contextId)
            val texts = page.locator(selector).allTextContents()
            "✅ Found ${texts.size} elements:\n${texts.joinToString("\n") { "- $it" }}"
        } catch (e: Exception) {
            "❌ Failed to get all text: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Close the browser page and context associated with the ID")
    suspend fun browser_close_context(
        @LLMDescription("Context ID to close")
        contextId: String = "default"
    ): String = withContext(Dispatchers.IO) {
        try {
            pages.remove(contextId)?.close()
            contexts.remove(contextId)?.close()
            "✅ Context $contextId closed"
        } catch (e: Exception) {
            "❌ Failed to close context: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Close all browser instances and contexts to free up resources")
    suspend fun browser_close_all(): String = withContext(Dispatchers.IO) {
        try {
            closeAll()
            "✅ All browser instances and contexts closed"
        } catch (e: Exception) {
            "❌ Failed to close all: ${e.message}"
        }
    }
}
