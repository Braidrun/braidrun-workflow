package com.fartech.agents.workflow

/** Interpreter-owned candidates from typed frozen declarations. They are not upload authority. */
data class WorkflowArtifactCandidate(val path: String, val declaration: Declaration) {
    enum class Declaration { STRUCTURED_OUTPUT, PUBLISHED_FILE }
}
