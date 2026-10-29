package com.fartech.agents.tools

import org.junit.jupiter.api.Test
import kotlin.test.*

class DataTransformXmlSafetyTest {
    @Test fun `XML extraction treats tag names literally and does not match longer names`() {
        val xml = "<items>wrong</item><item kind=\"a\">correct</item><ITEM>upper</ITEM>"
        val extracted = DataTransformTools.xmlExtractByTag(xml, "item")
        assertTrue(extracted.contains("Found 2"), extracted)
        assertTrue(extracted.contains("correct"), extracted)
        assertTrue(extracted.contains("upper"), extracted)
        assertFalse(extracted.contains("wrong"), extracted)
        for (tag in listOf("item.*", ".*", "item|items", "[", "(a+)+")) {
            assertEquals("No <$tag> elements found.", DataTransformTools.xmlExtractByTag(xml, tag))
        }
    }
}
