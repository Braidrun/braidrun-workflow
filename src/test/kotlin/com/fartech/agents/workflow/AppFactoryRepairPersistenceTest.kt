package com.fartech.agents.workflow

import kotlin.test.Test
import kotlin.test.assertEquals

class AppFactoryRepairPersistenceTest {
    @Test
    fun `filename must not be truncated by backtracking through optional words`() {
        assertEquals(setOf("/tmp/workspace/DEMO_SCENARIOS.json"),
            extractRequiredPersistedFilePathsFromPrompt("Update DEMO_SCENARIOS.json", "/tmp/workspace"))
        assertEquals(setOf("/tmp/workspace/REPORT.md"),
            extractRequiredPersistedFilePathsFromPrompt("Create REPORT.md", "/tmp/workspace"))
    }

    @Test
    fun `explicit repair target does not create a suffix file requirement`() {
        val prompt = checkNotNull(javaClass.getResource("/app-factory-repair-prompt.txt")).readText()
        val candidates = extractRequiredPersistedFilePathCandidatesFromPrompt(prompt)
        assertEquals(setOf("{{var:app_workspace}}/REPAIR.md"), candidates)
        assertEquals(setOf("/run/app/REPAIR.md"), resolvePersistedFilePathCandidates(candidates, "/step/workspace") {
            it.replace("{{var:app_workspace}}", "/run/app")
        })
    }
}
