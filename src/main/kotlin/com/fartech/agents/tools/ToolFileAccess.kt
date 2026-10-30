package com.fartech.agents.tools

/**
 * Bytes-only file service for format tools. Implementations retain ownership of path validation,
 * bounded reads and atomic/quota-controlled writes; callers never receive a File or open stream.
 * Runner adapters must inject their confined implementation, never [LocalToolFileAccess].
 */
interface ToolFileAccess {
    fun readBytes(path: String, maxBytes: Int = DEFAULT_READ_LIMIT_BYTES): ByteArray
    fun writeBytes(path: String, bytes: ByteArray)

    companion object {
        const val DEFAULT_READ_LIMIT_BYTES = 64 * 1024 * 1024
    }
}

/** Library/server default retains ToolPathSecurity's working-directory and temporary-directory policy. */
object LocalToolFileAccess : ToolFileAccess {
    override fun readBytes(path: String, maxBytes: Int): ByteArray {
        require(maxBytes in 1..ToolFileAccess.DEFAULT_READ_LIMIT_BYTES) { "Invalid binary read limit" }
        val file = ToolPathSecurity.validateInputPath(path)
        if (file.length() > maxBytes) throw SecurityException("File exceeds the binary read limit")
        return file.inputStream().use { input ->
            val bytes = input.readNBytes(maxBytes + 1)
            if (bytes.size > maxBytes) throw SecurityException("File grew past the binary read limit")
            bytes
        }
    }

    override fun writeBytes(path: String, bytes: ByteArray) {
        ToolPathSecurity.validateOutputPath(path).writeBytes(bytes)
    }
}
