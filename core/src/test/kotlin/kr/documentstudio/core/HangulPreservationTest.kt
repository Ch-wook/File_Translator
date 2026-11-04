package kr.documentstudio.core

import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.*

class HangulPreservationTest {
    private fun temp()=Files.createTempFile("preservation-",".bin").toFile().apply {deleteOnExit()}
    @Test fun hwpxKeepsUnrecognizedPartsWithoutReplacingEditedBody() {
        val blank=temp();Templates.create(Format.HWPX,blank);val original=temp()
        ZipFile(blank).use {zip->ZipOutputStream(original.outputStream()).use {out->
            zip.entries().toList().forEach {entry->out.putNextEntry(ZipEntry(entry.name));out.write(zip.getInputStream(entry).readBytes());out.closeEntry()}
            out.putNextEntry(ZipEntry("Custom/untouched.bin"));out.write(byteArrayOf(1,2,3,4));out.closeEntry()
        }}
        val candidate=temp();val doc=PackageDocument.open(blank);doc.write(candidate,Edits(values=mapOf(doc.sections.first().fields.first().id to "EDITED")))
        val sealed=temp();HangulPreservation.seal(original,candidate,sealed)
        assertEquals("EDITED",PackageDocument.open(sealed).sections.first().fields.first().text)
        ZipFile(sealed).use {assertArrayEquals(byteArrayOf(1,2,3,4),it.getInputStream(it.getEntry("Custom/untouched.bin")).readBytes())}
    }
    @Test fun hwpKeepsOpaqueStreamsAndEditedBodyWhileSourceRemainsIntact() {
        val blank=temp();LegacyDocument.create(Format.HWP,blank);val original=temp()
        POIFSFileSystem(blank.inputStream()).use {fs->fs.root.createDocument("CustomStream",byteArrayOf(9,8,7).inputStream());original.outputStream().use {fs.writeFilesystem(it)}}
        val doc=LegacyDocument.open(blank);val candidate=temp();doc.write(candidate,Edits(values=mapOf(doc.sections.first().fields.first().id to "EDITED")))
        val bytes=original.readBytes();val sealed=temp();HangulPreservation.seal(original,candidate,sealed)
        assertTrue(LegacyDocument.open(sealed).sections.flatMap {it.fields}.any {it.text=="EDITED"})
        POIFSFileSystem(sealed,true).use {assertArrayEquals(byteArrayOf(9,8,7),it.root.createDocumentInputStream("CustomStream").readBytes())}
        assertArrayEquals(bytes,original.readBytes())
    }
    @Test fun invalidExportNeverOverwritesDestination() {
        val original=temp();Templates.create(Format.HWPX,original);val invalid=temp().apply {writeText("broken")};val destination=temp().apply {writeText("KEEP")}
        assertThrows(Exception::class.java) {HangulPreservation.seal(original,invalid,destination)}
        assertEquals("KEEP",destination.readText())
    }
}
