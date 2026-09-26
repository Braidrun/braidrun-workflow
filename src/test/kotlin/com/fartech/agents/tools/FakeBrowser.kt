package com.fartech.agents.tools

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.Cookie
import com.microsoft.playwright.options.SameSiteAttribute
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.Collections

/** Fake Playwright objects built with [Proxy]; only the calls BrowserTools makes are implemented. */
internal class FakeBrowserWorld {
    val contexts: MutableList<FakeContext> = Collections.synchronizedList(mutableListOf())

    @Volatile
    var screenshotBytes: (String) -> ByteArray = { url -> url.toByteArray() }

    /** Runs inside `Page.waitForTimeout`, while the tool call holds its session. */
    @Volatile
    var onWait: () -> Unit = {}

    val browser: Browser = fake { name, _ ->
        when (name) {
            "newContext" -> FakeContext(this).also { contexts += it }.proxy
            "close" -> null
            "isConnected" -> true
            else -> unsupported("Browser", name)
        }
    }
}

internal class FakeContext(world: FakeBrowserWorld) {
    @Volatile
    var closed = false
    val cookies: MutableList<Cookie> = Collections.synchronizedList(mutableListOf())
    val proxy: BrowserContext = fake { name, args ->
        when (name) {
            "newPage" -> page.proxy
            "close" -> { closed = true; null }
            "cookies" -> cookies.toList()
            // Playwright always reports sameSite on the cookies it returns.
            "addCookies" -> {
                @Suppress("UNCHECKED_CAST")
                cookies += (args!![0] as List<Cookie>).map { it.setSameSite(it.sameSite ?: SameSiteAttribute.LAX) }
                null
            }
            "clearCookies" -> { cookies.clear(); null }
            else -> unsupported("BrowserContext", name)
        }
    }
    val page = FakePage(this, world)
}

internal class FakePage(context: FakeContext, world: FakeBrowserWorld) {
    @Volatile
    var closed = false
    @Volatile
    var url = "about:blank"
    val proxy: Page = fake { name, args ->
        when (name) {
            "navigate" -> { url = args!![0] as String; null }
            "url" -> url
            "content" -> "<html><body>$url</body></html>"
            "title" -> "title of $url"
            "context" -> context.proxy
            "screenshot" -> world.screenshotBytes(url)
            "waitForTimeout" -> { world.onWait(); null }
            "close" -> { closed = true; null }
            "isClosed" -> closed
            else -> unsupported("Page", name)
        }
    }
}

private inline fun <reified T> fake(crossinline handler: (String, Array<Any?>?) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java), InvocationHandler { proxy, method, args ->
        when (method.name) {
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.get(0)
            "toString" -> "Fake${T::class.java.simpleName}@${System.identityHashCode(proxy)}"
            else -> handler(method.name, args)
        }
    }) as T

private fun unsupported(type: String, name: String): Nothing = throw UnsupportedOperationException("$type.$name")
