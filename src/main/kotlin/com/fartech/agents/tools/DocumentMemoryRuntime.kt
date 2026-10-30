package com.fartech.agents.tools

import javax.imageio.ImageIO

/**
 * Install once, before accepting work, in a dedicated Runner JVM only. Some POI/PDFBox image
 * helpers internally call ImageIO.read(InputStream); the JVM default otherwise caches to disk.
 * The library/server defaults never invoke this process-wide setup.
 */
object DocumentMemoryRuntime {
    @Synchronized
    fun installForDedicatedRunnerProcess() {
        ImageIO.setUseCache(false)
        org.apache.poi.openxml4j.util.ZipInputStreamZipEntrySource.setThresholdBytesForTempFiles(-1)
        org.apache.poi.openxml4j.opc.ZipPackage.setUseTempFilePackageParts(false)
        DocumentMemoryFonts.installForDedicatedRunnerProcess()
        checkInstalled()
    }

    fun checkInstalled() {
        if (ImageIO.getUseCache()) throw SecurityException("Runner document ImageIO memory policy must be installed in its dedicated JVM")
        checkPoiMemoryPolicy()
        DocumentMemoryFonts.checkInstalled()
    }

    fun checkPoiMemoryPolicy() {
        if (org.apache.poi.openxml4j.util.ZipInputStreamZipEntrySource.getThresholdBytesForTempFiles() != -1 ||
            org.apache.poi.openxml4j.opc.ZipPackage.useTempFilePackageParts()) {
            throw SecurityException("Runner document POI memory policy must be installed in its dedicated JVM")
        }
    }
}
