package kr.documentstudio.core

import java.io.File
import java.util.zip.ZipFile
import kr.dogfoot.hwpxlib.reader.HWPXReader
import kr.dogfoot.hwpxlib.`object`.content.section_xml.paragraph.`object`.Table
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class HangulTablesTest {
    private fun temp()=File.createTempFile("hangul-table-",".hwpx").apply {deleteOnExit()}
    private fun blank()=temp().also {Templates.create(Format.HWPX,it)}
    private fun change(source: File,type: String,args: Map<String,String> = emptyMap())=temp().also {PackageFeatures.apply(source,it,change=DocumentChange("hwpx.$type","Contents/section0.xml",args))}
    private fun table(source: File)=HWPXReader.fromFile(source).sectionXMLFileList().get(0).paras().flatMap {it.runs().toList()}.flatMap {it.runItems().toList()}.filterIsInstance<Table>().single()
    private fun xml(source: File,name: String="Contents/section0.xml")=ZipFile(source).use {Xml.parse(it.getInputStream(it.getEntry(name)).readBytes())}
    private fun created()=change(blank(),"table",mapOf("rows" to "2","columns" to "3"))

    @Test fun newTableHasNativeDimensionsAndEditableCellLabels() {
        val source=created();val t=table(source)
        assertEquals(2,t.rowCnt().toInt());assertEquals(3,t.colCnt().toInt())
        assertEquals(3,t.getTr(0).countOfTc());assertEquals(1,t.getTr(1).getTc(2).cellAddr().rowAddr().toInt())
        assertEquals(t.sz().width().toInt(),t.getTr(0).tcs().sumOf {it.cellSz().width().toInt()})
        val pack=PackageDocument.open(source);assertEquals(6,pack.sections.first().fields.count {it.label.startsWith("표 ")})
        val lastCell=pack.sections.first().fields.single {it.label.contains("2행 3열")}
        val edited=temp();pack.write(edited,Edits(values=mapOf(lastCell.id to "한글 셀 입력")))
        assertEquals("한글 셀 입력",PackageDocument.open(edited).sections.first().fields.single {it.id==lastCell.id}.text)
        assertNotNull(table(edited))
    }
    @Test fun insertDeleteRowsAndColumnsPreserveSurvivingContent() {
        var source=created();val pack=PackageDocument.open(source)
        source=temp().also {pack.write(it,Edits(values=pack.sections.first().fields.filter {it.label.startsWith("표 ")}.mapIndexed {i,f->f.id to "셀$i"}.toMap()))}
        source=change(source,"tableAddRow",mapOf("row" to "2"));source=change(source,"tableAddColumn",mapOf("column" to "2"))
        val inserted=table(source);assertEquals(3,inserted.rowCnt().toInt());assertEquals(4,inserted.colCnt().toInt())
        source=change(source,"tableDeleteRow",mapOf("row" to "2"));source=change(source,"tableDeleteColumn",mapOf("column" to "2"))
        assertEquals((0..5).map {"셀$it"},PackageDocument.open(source).sections.first().fields.filter {it.label.startsWith("표 ")}.map {it.text})
        val t=table(source);assertEquals(2,t.rowCnt().toInt());assertEquals(3,t.colCnt().toInt())
        assertEquals(t.sz().height().toInt(),t.trs().sumOf {it.getTc(0).cellSz().height().toInt()})
    }
    @Test fun mergeSplitRetainsEveryCellParagraphAndRejectsUnsafeRowChange() {
        var source=created();val pack=PackageDocument.open(source)
        source=temp().also {pack.write(it,Edits(values=pack.sections.first().fields.filter {it.label.startsWith("표 ")}.mapIndexed {i,f->f.id to "내용$i"}.toMap()))}
        val merged=change(source,"tableMerge",mapOf("row" to "1","column" to "1","lastRow" to "2","lastColumn" to "2"))
        val t=table(merged);assertEquals(2,t.getTr(0).getTc(0).cellSpan().rowSpan().toInt());assertEquals(4,t.getTr(0).getTc(0).subList().countOfPara())
        try {change(merged,"tableAddRow",mapOf("row" to "2"));fail("Merged row must be protected")} catch(e: IllegalArgumentException) {assertTrue(e.message!!.contains("병합"))}
        val split=change(merged,"tableSplit",mapOf("row" to "1","column" to "1"));val result=table(split)
        assertEquals(3,result.getTr(1).countOfTc());assertEquals(1,result.getTr(0).getTc(0).cellSpan().rowSpan().toInt())
        assertEquals((0..5).map {"내용$it"}.toSet(),PackageDocument.open(split).sections.first().fields.map {it.text}.filter {it.isNotBlank()}.toSet())
    }
    @Test fun sizeAndCellStyleRoundtripThroughIndependentParser() {
        val resized=change(created(),"tableSize",mapOf("widthMm" to "150","heightMm" to "40"))
        val styled=change(resized,"tableCellStyle",mapOf("row" to "2","column" to "3","background" to "EEF3EF","border" to "315D54","vertical" to "BOTTOM"))
        val t=table(styled);assertEquals((150*7200/25.4).toInt(),t.sz().width().toInt());assertEquals("BOTTOM",t.getTr(1).getTc(2).subList().vertAlign().toString())
        val ref=t.getTr(1).getTc(2).borderFillIDRef();val fill=Xml.elements(xml(styled,"Contents/header.xml"),"borderFill").single {it.getAttribute("id")==ref}
        assertEquals("#EEF3EF",Xml.elements(fill,"winBrush").single().getAttribute("faceColor"));assertEquals("#315D54",Xml.elements(fill,"leftBorder").single().getAttribute("color"))
    }
    @Test fun invalidRangesAndLastRowDeletionNeverModifySource() {
        val source=change(blank(),"table",mapOf("rows" to "1","columns" to "1"));val bytes=source.readBytes()
        for((type,args) in listOf("tableDeleteRow" to mapOf("row" to "1"),"tableDeleteColumn" to mapOf("column" to "1"),"tableAddRow" to mapOf("row" to "0"),"tableCellStyle" to mapOf("background" to "invalid"),"tableMerge" to mapOf("lastRow" to "2","lastColumn" to "2"))) {
            try {change(source,type,args);fail(type)} catch(e: IllegalArgumentException) {assertArrayEquals(bytes,source.readBytes())}
        }
    }
}
