package com.fartech.agents.runner

/** P/S hand off only unchanged original file gates; the existing E per-call transport is unchanged. */
internal object RunnerFileGateResumeProtocol {
    const val CAPABILITY = "control.fileGateResume.v1"
    const val HASH = "77827d95e8c82d8810ba9ef462b15d5deba7a5088264643fc81f140a74be2b58"
    const val ORIGIN = "478dd7edc8ba7c74e677030889900be88ed08663"
    private fun resource(name: String) = javaClass.getResourceAsStream("/runner-workflow-v1/$name")?.use { it.readBytes() }
    fun available() = resource("file-gate-resume.schema.json")?.let { sha256(it) == HASH } == true &&
        resource("file-gate-resume-origin.txt")?.toString(Charsets.UTF_8)?.trim() == ORIGIN
}
