package com.fartech.agents.runner

/** P/S own gate HTTP providers and frozen material authority. E only negotiates
 * these independently pinned companions over the existing per-call transport.
 * This inventory never admits a missing supervisor provider or new IPC frame. */
internal object RunnerGateProtocolInventory {
    const val SET_HASH = "330250cb23010e87a59e9f8892049aafd89ea18728c72259ed6b7e6302acad34"
    const val SET_COMMIT = "fcf5b9f4b79a34c283863707c9b34d0f2ffbcd24"
    const val PROJECTION_HASH = "fec8d0e3b9744aa5d370dc82f76b7223b45e3b675c152b2a9e65936bd41836e7"
    const val PROJECTION_COMMIT = "2adcc5778d5e9d50f1ac3f00b4b7e2050da35776"
    const val FEEDBACK_HASH = "cb19be846c714a915e765564ce5fd2e97afa548e34331cf97994cd953351dfdc"
    const val FEEDBACK_COMMIT = "ded74d6482fcf2abe1ecfb4be4c4d56a021a1c2d"
    private fun resource(name: String) = javaClass.getResourceAsStream("/runner-workflow-v1/$name")?.use { it.readBytes() }
    fun capabilities(base: Set<String>, read: (String) -> ByteArray? = ::resource): Set<String> {
        if (!base.containsAll(setOf(RunnerCodeControlNegotiation.PER_CALL, RunnerArtifactSetProtocol.CAPABILITY,
                RunnerCollectionProtocol.CAPABILITY))) return emptySet()
        fun pinned(name: String, hash: String, commit: String) =
            read("$name.schema.json")?.let { sha256(it) == hash } == true &&
                read("$name-origin.txt")?.toString(Charsets.UTF_8)?.lineSequence()?.firstOrNull() == commit
        if (!pinned("gate-set-control", SET_HASH, SET_COMMIT)) return emptySet()
        return buildSet {
            add(RunnerArtifactSetProtocol.GATES)
            if (pinned("gate-projection", PROJECTION_HASH, PROJECTION_COMMIT)) {
                add(RunnerCodeControlNegotiation.GATE_PROJECTION)
                if (RunnerCodeControlNegotiation.FEEDBACK_CONTEXT in base && pinned("gate-feedback-context", FEEDBACK_HASH, FEEDBACK_COMMIT))
                    add(RunnerCodeControlNegotiation.GATE_FEEDBACK)
            }
        }
    }
}
