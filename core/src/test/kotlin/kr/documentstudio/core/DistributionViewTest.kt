package kr.documentstudio.core

import java.io.File
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.junit.Assert.*
import org.junit.Test

class DistributionViewTest {
    private val fixture get()=File("../artifacts/open-fixtures/android-assets/distribution.hwp").also {require(it.isFile) {"Run python3 scripts/prepare-open-fixtures.py before tests"}}
    @Test fun distributableHwpIsRecognizedForViewingButNotExtractedTextEditing() {
        assertEquals(Format.HWP,FormatDetector.detect(fixture,"문서.hwp"))
        assertNotNull(LegacyDocument.hwpReadOnlyReason(fixture))
        val destination=File.createTempFile("protected-convert-",".hwpx")
        try {
            try {HangulConversion.convert(fixture,destination);fail("Protected distribution must not be converted")} catch(e: IllegalArgumentException) {assertTrue(e.message!!.contains("읽기 전용"))}
            assertEquals(0L,destination.length())
        } finally {destination.delete()}
        try {LegacyDocument.open(fixture);fail("Distribution protection must remain enforced")} catch(e: IllegalArgumentException) {assertTrue(e.message!!.contains("읽기 전용"))}
    }
    @Test fun encryptedHwpRemainsRejected() {
        val encrypted=File.createTempFile("protected-", ".hwp")
        try {
            fixture.inputStream().use {input->POIFSFileSystem(input)}.use {fs->
                val header=fs.createDocumentInputStream("FileHeader").use {it.readBytes()}
                header[36]=(header[36].toInt() or 2).toByte()
                fs.root.createOrUpdateDocument("FileHeader",header.inputStream())
                encrypted.outputStream().use {fs.writeFilesystem(it)}
            }
            try {FormatDetector.detect(encrypted,"encrypted.hwp");fail("Encryption is still unsupported")} catch(e: IllegalArgumentException) {assertTrue(e.message!!.contains("암호"))}
        } finally {encrypted.delete()}
    }
}
