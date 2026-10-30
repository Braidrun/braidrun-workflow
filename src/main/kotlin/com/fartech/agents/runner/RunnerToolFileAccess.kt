package com.fartech.agents.runner

import com.fartech.agents.tools.ToolFileAccess
import java.nio.file.Path

/** Every byte passes through the attempt's shared confinement and write quota. */
class RunnerToolFileAccess(
    private val files: RunnerFileConfinement,
    private val workingDirectory: Path
) : ToolFileAccess {
    override fun readBytes(path: String, maxBytes: Int): ByteArray =
        files.readBinary(files.locate(path, workingDirectory), maxBytes)

    override fun writeBytes(path: String, bytes: ByteArray) {
        files.write(files.locate(path, workingDirectory), bytes, append = false)
    }
}
