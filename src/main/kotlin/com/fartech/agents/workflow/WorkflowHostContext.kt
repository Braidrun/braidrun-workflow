package com.fartech.agents.workflow

import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext

/** Coroutine-propagated trusted transport scope; model construction never reads workflow flags. */
internal object WorkflowHostContext {
    private val local = ThreadLocal<WorkflowHost?>()
    val current: WorkflowHost? get() = local.get()
    suspend fun <T> withHost(host: WorkflowHost?, body: suspend () -> T): T =
        withContext(local.asContextElement(host)) { body() }
}
