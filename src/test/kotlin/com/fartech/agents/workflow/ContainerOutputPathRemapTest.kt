package com.fartech.agents.workflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContainerOutputPathRemapTest {

    private val host = "/opt/braidrun/repo/braidrun/braidrun-web/.workflow-runs/exec-1/output"

    @Test
    fun `remaps container output root and children to the host output dir`() {
        assertEquals(host, WorkflowExecutor.remapContainerOutputPath("/output", host))
        assertEquals(
            "$host/actions/open_keyword_ai_reviews.json",
            WorkflowExecutor.remapContainerOutputPath("/output/actions/open_keyword_ai_reviews.json", host)
        )
        assertEquals("$host/a.md", WorkflowExecutor.remapContainerOutputPath("  /output/a.md \n", "$host/"))
    }

    @Test
    fun `leaves host paths and look-alikes untouched`() {
        assertNull(WorkflowExecutor.remapContainerOutputPath("$host/actions/x.json", host))
        assertNull(WorkflowExecutor.remapContainerOutputPath("/outputs/x.json", host))
        assertNull(WorkflowExecutor.remapContainerOutputPath("ok", host))
        assertNull(WorkflowExecutor.remapContainerOutputPath("relative/output/x.json", host))
    }
}
