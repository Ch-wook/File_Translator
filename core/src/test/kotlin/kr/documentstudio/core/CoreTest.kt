package kr.documentstudio.core

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CoreTest {
    private fun temp()=Files.createTempFile("documentstudio-",".bin").toFile().apply {deleteOnExit()}
    @Test fun textEncodingAndLineEndingsRoundTrip() {
        listOf("UTF-8","UTF-16LE","UTF-16BE","MS949").forEach { charset ->
            val original=TextContent("한글과 English\r\n다음 줄\n",charset,charset!="MS949")
            val bytes=original.encode(); val loaded=TextContent.decode(bytes,charset)
            assertEquals(original,loaded);assertArrayEquals(bytes,loaded.encode())
        }
    }
    @Test fun unrepresentableEncodingIsRejected() {
        assertThrows(java.nio.charset.CharacterCodingException::class.java) {TextContent("😀","MS949").encode()}
    }
    @Test fun bomOverridesFallback() {assertEquals("UTF-16BE",TextContent.decode(TextContent("한글","UTF-16BE",true).encode(),"MS949").charset)}
    @Test fun binaryIsNotText() {assertThrows(IllegalArgumentException::class.java){TextContent.decode(byteArrayOf(65,0,66))}}
    @Test fun detectorUsesContainerRatherThanExtension() {
        val file=temp();Templates.create(Format.DOCX,file);assertEquals(Format.DOCX,FormatDetector.detect(file,"wrong.xlsx"))
    }
    @Test fun officeTextEditSaveAndReopenPreservesOtherParts() {
        listOf(Format.DOCX,Format.PPTX).forEach { format ->
            val src=temp();Templates.create(format,src);val opened=PackageDocument.open(src)
            val field=opened.sections.first().fields.first();val out=temp()
            opened.write(out,Edits(values=mapOf(field.id to "변경 & <한글>"),bold=if(format==Format.DOCX) mapOf(field.id to true) else emptyMap()))
            val result=PackageDocument.open(out).sections.first().fields.first()
            assertEquals("변경 & <한글>",result.text);if(format==Format.DOCX) assertTrue(result.bold)
            ZipFile(src).use {a -> ZipFile(out).use {b -> a.entries().toList().filter {it.name!=opened.sections.first().id}.forEach {e ->
                assertArrayEquals(e.name,a.getInputStream(e).readBytes(),b.getInputStream(b.getEntry(e.name)).readBytes())
            } } }
        }
    }
    @Test fun xlsxCellsFormulasSaveAndReopen() {
        val src=temp();Templates.create(Format.XLSX,src);val doc=PackageDocument.open(src);val sec=doc.sections.first();val out=temp()
        doc.write(out,Edits(values=mapOf("${sec.id}#A1" to "10","${sec.id}#A2" to "20","${sec.id}#B1" to "=SUM(A1:A2)","${sec.id}#C1" to "한글")))
        val result=PackageDocument.open(out).sections.first().cells
        assertEquals("=SUM(A1:A2)",result["B1"]);assertEquals("30",Formula(result).display("B1"));assertEquals("한글",result["C1"])
    }
    @Test fun zipTraversalAndUnsupportedContainersRejected() {
        val src=temp();ZipOutputStream(src.outputStream()).use {it.putNextEntry(ZipEntry("../bad.xml"));it.write("x".toByteArray());it.closeEntry()}
        assertThrows(IllegalArgumentException::class.java){PackageDocument.open(src)}
    }
    @Test fun xmlExternalEntitiesRejected() {assertThrows(IllegalArgumentException::class.java){Xml.parse("<!DOCTYPE a [<!ENTITY x SYSTEM 'file:///secret'>]><a>&x;</a>".toByteArray())}}
    @Test fun formulasReferencesArithmeticAndErrors() {
        val f=Formula(mapOf("A1" to "10","A2" to "20","B1" to "=SUM(\$A\$1:A2)+2*3","B2" to "=AVERAGE(A1:A2)","C1" to "=1/0","C2" to "=C2","C3" to "=XLOOKUP(A1,A1,A2)","D1" to "=IF(A1>5,ROUND(1.235,2),0)"))
        assertEquals("36",f.display("B1"));assertEquals("15",f.display("B2"));assertEquals("#DIV/0!",f.display("C1"));assertEquals("#CYCLE!",f.display("C2"));assertEquals("20",f.display("C3"));assertEquals("1.24",f.display("D1"))
    }
    @Test fun undoRedoAndBranching() {val h=History<String>(2);h.record("a");h.record("b");assertEquals("b",h.undo("c"));assertEquals("c",h.redo("b"));h.undo("c");h.record("b");assertFalse(h.canRedo)}
    @Test fun cellLimits() {assertTrue(validAddress("XFD1048576"));assertFalse(validAddress("XFE1"));assertFalse(validAddress("A0"));assertEquals("AA",columnName(27))}
    @Test fun lazyIfAndStringWhitespace() {
        val f=Formula(mapOf("A1" to "=IF(TRUE,CONCAT(\"hello \",\"world\"),1/0)","A2" to "=IF(FALSE,1/0,IF(TRUE,5,0))"))
        assertEquals("hello world",f.display("A1"));assertEquals("5",f.display("A2"))
    }
    @Test fun oleHeaderCannotBeMistakenForText() {
        val file=temp();file.writeBytes(byteArrayOf(0xD0.toByte(),0xCF.toByte(),0x11,0xE0.toByte(),0xA1.toByte(),0xB1.toByte(),0x1A,0xE1.toByte()))
        assertThrows(Exception::class.java){FormatDetector.detect(file,"binary.txt")}
    }
    @Test fun hwpxExistingTextRoundTripKeepsOtherParts() {
        val src=temp();val parts=mapOf("mimetype" to "application/hwp+zip","Contents/content.hpf" to "<package/>","Contents/header.xml" to "<header/>","Contents/section0.xml" to "<hp:sec xmlns:hp=\"http://www.hancom.co.kr/hwpml/2011/paragraph\"><hp:p><hp:run><hp:t>한글 원본</hp:t></hp:run><hp:linesegarray/></hp:p></hp:sec>","BinData/image.bin" to "preserved data")
        ZipOutputStream(src.outputStream()).use {zip ->parts.forEach {(name,data) ->zip.putNextEntry(ZipEntry(name));zip.write(data.toByteArray());zip.closeEntry()}}
        val opened=PackageDocument.open(src);assertEquals(Format.HWPX,opened.format);val field=opened.sections.first().fields.first();val out=temp()
        opened.write(out,Edits(values=mapOf(field.id to "한글 수정")));assertEquals("한글 수정",PackageDocument.open(out).sections.first().fields.first().text)
        ZipFile(out).use {assertEquals("preserved data",it.getInputStream(it.getEntry("BinData/image.bin")).bufferedReader().readText())}
    }
}
