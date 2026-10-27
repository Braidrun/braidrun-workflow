package com.fartech.agents.runner

internal object RunnerBusinessBackendProtocol {
    const val CAPABILITY = "control.businessBackend.v1"
    const val HASH = "d54faad8cad874b1a314c2cdecdb651a55f2d476f0a7b49f1f28ce52dc5bb1d0"
    fun capabilities(base:Set<String>,read:()->ByteArray? = {javaClass.getResourceAsStream("/runner-workflow-v1/business-backend-control.schema.json")?.use{it.readBytes()}}):Set<String> =
        if(RunnerCodeControlNegotiation.PER_CALL in base && read()?.let{sha256(it)==HASH}==true) setOf(CAPABILITY) else emptySet()
}
