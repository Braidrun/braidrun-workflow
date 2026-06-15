package com.fartech.agents.agents.app_generators.iOS

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class IOSAppUtilsTest {

    private fun arch(path: String, isCritical: Boolean = false) = FileArchitecture(
        filePath = path,
        isCritical = isCritical
    )

    @Test
    fun `findFileArchitecture matches exact normalized paths`() {
        val entries = listOf(arch("Sources/AppNameApp.swift"), arch("Sources/Views/Home.swift"))
        val found = findFileArchitecture(entries, "sources/appnameapp.swift")
        assertEquals("Sources/AppNameApp.swift", found?.filePath)
    }

    @Test
    fun `findFileArchitecture matches relative architecture path against sanitized absolute file path`() {
        // The architecture carries the LLM's relative form while generated files are
        // absolute under the project root — the original raw == comparison missed,
        // dropping the ARCHITECTURE SPECIFICATION from every generation prompt.
        val entries = listOf(arch("Sources/AppNameApp.swift", isCritical = true))
        val found = findFileArchitecture(entries, "/tmp/proj/Sources/AppNameApp.swift")
        assertEquals(true, found?.isCritical)
    }

    @Test
    fun `findFileArchitecture falls back to unique basename`() {
        val entries = listOf(arch("App/Models/User.swift"), arch("App/Views/Settings.swift"))
        val found = findFileArchitecture(entries, "/abs/elsewhere/User.swift")
        assertEquals("App/Models/User.swift", found?.filePath)
    }

    @Test
    fun `findFileArchitecture returns null on ambiguous basename`() {
        val entries = listOf(arch("A/Helper.swift"), arch("B/Helper.swift"))
        assertNull(findFileArchitecture(entries, "/abs/Helper.swift"))
    }

    @Test
    fun `findFileArchitecture handles null and empty lists`() {
        assertNull(findFileArchitecture(null, "x.swift"))
        assertNull(findFileArchitecture(emptyList(), "x.swift"))
    }
}
