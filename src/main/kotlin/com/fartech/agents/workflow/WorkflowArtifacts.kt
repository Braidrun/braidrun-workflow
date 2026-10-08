package com.fartech.agents.workflow

/** Interpreter-owned candidates from typed frozen declarations. They are not upload authority. */
data class WorkflowArtifactCandidate(val path: String, val declaration: Declaration) {
    enum class Declaration { STRUCTURED_OUTPUT, PUBLISHED_FILE }
}

/** Original active-execution budgets inherited by nested tools; never a new wire deadline. */
internal class ArtifactCompletionBudgets(val remaining: List<() -> Long>) :
    kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<ArtifactCompletionBudgets>
}
internal suspend fun <T> withArtifactCompletionBudget(remaining: (() -> Long)?, block: suspend () -> T): T {
    if (remaining == null) return block()
    val inherited = kotlinx.coroutines.currentCoroutineContext()[ArtifactCompletionBudgets]?.remaining.orEmpty()
    return kotlinx.coroutines.withContext(ArtifactCompletionBudgets(inherited + remaining)) { block() }
}
