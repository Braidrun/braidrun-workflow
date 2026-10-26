package com.fartech.agents.runner

/** P/S perform these scoped controls over existing per-call IPC. E pins their HTTP companion. */
internal object RunnerTestContractProtocol {
    const val CAPABILITY = "control.testContract.v1"
    const val HASH = "a05eac3eb56c47ef988005664ff2d58416a9730d8ef8b4f297ddb5547c3d08dc"
    const val ORIGIN = "df4778bbc8445ffcf0fc2be8ae0891096a91e232"
    private fun resource(name: String) = javaClass.getResourceAsStream("/runner-workflow-v1/$name")?.use { it.readBytes() }
    fun capabilities(base: Set<String>, read: (String) -> ByteArray? = ::resource): Set<String> {
        if (RunnerCodeControlNegotiation.PER_CALL !in base) return emptySet()
        if (read("test-contract-control.schema.json")?.let { sha256(it) == HASH } != true ||
            read("test-contract-control-origin.txt")?.toString(Charsets.UTF_8)?.trim() != ORIGIN) return emptySet()
        return setOf(CAPABILITY)
    }
}
