package kr.documentstudio.core

import org.junit.Test
import org.junit.Assert.*
import java.io.File
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.hslf.usermodel.HSLFSlideShow
import org.apache.poi.hslf.usermodel.HSLFTextBox

class LegacyTest {
    private fun temp(ext: String)=File.createTempFile("legacy-", ".$ext").apply {deleteOnExit()}
    @Test fun binaryExcelKeepsStyleAndFormula() {
        val src=temp("xls")
        HSSFWorkbook().use { w ->
            val c=w.createSheet("Data").createRow(0).createCell(0); c.setCellValue(5.0)
            c.cellStyle=w.createCellStyle().apply {dataFormat=w.createDataFormat().getFormat("0.00")}
            src.outputStream().use {w.write(it)}
        }
        val d=LegacyDocument.open(src); val id=d.sections[0].id
        val dst=temp("xls"); d.write(dst,Edits(values=mapOf("$id#A1" to "7","$id#B1" to "=SUM(A1,3)")))
        assertEquals("10",Formula(LegacyDocument.open(dst).sections[0].cells).display("B1"))
        HSSFWorkbook(dst.inputStream()).use {assertEquals("0.00",it.getSheetAt(0).getRow(0).getCell(0).cellStyle.dataFormatString)}
    }
    @Test fun binaryPowerPointTextRoundtrip() {
        val src=temp("ppt")
        HSLFSlideShow().use {s -> val slide=s.createSlide(); slide.addShape(HSLFTextBox().apply {text="Original"}); src.outputStream().use {s.write(it)} }
        val d=LegacyDocument.open(src); val id=d.sections[0].fields[0].id
        val dst=temp("ppt");d.write(dst,Edits(values=mapOf(id to "편집한 슬라이드")))
        assertEquals("편집한 슬라이드",LegacyDocument.open(dst).sections[0].fields[0].text)
    }
    @Test fun binaryHangulCreatesEditsAndReopens() {
        val src=temp("hwp");LegacyDocument.create(Format.HWP,src)
        val d=LegacyDocument.open(src);val field=d.sections[0].fields[0]
        val dst=temp("hwp");d.write(dst,Edits(values=mapOf(field.id to "한글 원본 형식 편집")))
        assertEquals("한글 원본 형식 편집",LegacyDocument.open(dst).sections[0].fields[0].text)
    }
    @Test fun binaryWordPreservesRunFormattingAndUnknownStream() {
        val src=temp("doc");File("../app/src/androidTest/assets/legacy.doc").copyTo(src,true)
        val decorated=temp("doc")
        org.apache.poi.poifs.filesystem.POIFSFileSystem(src.inputStream()).use {fs->fs.root.createDocument("StudioUnknown",java.io.ByteArrayInputStream("retain me".toByteArray()));decorated.outputStream().use {fs.writeFilesystem(it)}}
        val d=LegacyDocument.open(decorated);val field=d.sections[0].fields[0];val dst=temp("doc")
        d.write(dst,Edits(values=mapOf(field.id to "한글 Word 원본"),bold=mapOf(field.id to true)))
        assertEquals("한글 Word 원본",LegacyDocument.open(dst).sections[0].fields[0].text)
        org.apache.poi.poifs.filesystem.POIFSFileSystem(dst,true).use {fs->assertEquals("retain me",fs.createDocumentInputStream("StudioUnknown").use {it.readBytes().toString(Charsets.UTF_8)})}
    }
}
