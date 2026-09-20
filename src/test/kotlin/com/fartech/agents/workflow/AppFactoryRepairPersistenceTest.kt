package com.fartech.agents.workflow

import kotlin.test.Test
import kotlin.test.assertEquals

class AppFactoryRepairPersistenceTest {
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
