package com.fartech.agents.workflow

import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext

/** Coroutine-propagated trusted transport scope; model construction never reads workflow flags. */
internal object WorkflowHostContext {
    private val local = ThreadLocal<WorkflowHost?>()
    private val scopeLocal = ThreadLocal<WorkflowHostScope?>()
    val current: WorkflowHost? get() = local.get()
    private val jobLocal = ThreadLocal<kotlinx.coroutines.Job?>()
    val job: kotlinx.coroutines.Job? get() = jobLocal.get()
    val scope: WorkflowHostScope? get() = scopeLocal.get()
    suspend fun <T> withScope(scope: WorkflowHostScope, body: suspend () -> T): T =
        withContext(scopeLocal.asContextElement(scope) + jobLocal.asContextElement(kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job])) { body() }
    suspend fun <T> withHost(host: WorkflowHost?, body: suspend () -> T): T =
        withContext(local.asContextElement(host)) { body() }
}
