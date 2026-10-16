package kr.documentstudio.core

import org.junit.Test
import org.junit.Assert.*
import java.io.File
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xslf.usermodel.XMLSlideShow

class FeatureTest {
    private fun temp()=File.createTempFile("feature-",".zip").apply {deleteOnExit()}
    private fun change(src: File,c: DocumentChange)=temp().also {PackageFeatures.apply(src,it,change=c)}
    @Test fun wordStylesTablesAndLayoutAreNative() {
        val source=temp();Templates.create(Format.DOCX,source);val pack=PackageDocument.open(source)
        val styled=temp();pack.write(styled,Edits(styles=mapOf(pack.sections[0].fields[0].id to mapOf("italic" to "true","size" to "20","color" to "CC2244","align" to "center","lineSpacing" to "1.5"))))
        val shown=PackageDocument.open(styled).sections[0].fields[0].styles;assertEquals("true",shown["italic"]);assertEquals("20.0",shown["size"]);assertEquals("CC2244",shown["color"])
        val table=change(styled,DocumentChange("word.table",arguments=mapOf("rows" to "2","columns" to "3")))
        val paragraph=change(table,DocumentChange("word.paragraph",arguments=mapOf("text" to "추가 문단")))
        val layout=change(paragraph,DocumentChange("word.layout",arguments=mapOf("landscape" to "true","marginMm" to "15")))
        XWPFDocument(layout.inputStream()).use {d ->assertTrue(d.paragraphs[0].runs[0].isItalic);assertEquals(20,d.paragraphs[0].runs[0].fontSize);assertEquals("CC2244",d.paragraphs[0].runs[0].color);assertEquals(2,d.tables[0].numberOfRows);assertEquals(3,d.tables[0].rows[0].tableCells.size);assertEquals("추가 문단",d.paragraphs.last().text)}
    }
    @Test fun spreadsheetStyleMergeFreezeAndSheetsRoundtrip() {
        val source=temp();Templates.create(Format.XLSX,source);val pack=PackageDocument.open(source);val section=pack.sections[0].id
        val styled=temp();pack.write(styled,Edits(values=mapOf("$section#A1" to "12"),styles=mapOf("$section#A1" to mapOf("bold" to "true","background" to "FFEEDD","numberFormat" to "0.00"))))
        val shown=PackageDocument.open(styled).sections[0].cellStyles["A1"]!!;assertEquals("true",shown["bold"]);assertEquals("FFEEDD",shown["background"]);assertEquals("0.00",shown["numberFormat"])
        val merged=change(styled,DocumentChange("sheet.merge",section,mapOf("range" to "A1:B1")))
        val frozen=change(merged,DocumentChange("sheet.freeze",section,mapOf("rows" to "1","columns" to "1")))
        val duplicate=change(frozen,DocumentChange("sheet.duplicate",section,mapOf("name" to "복사본")))
        val renamed=change(duplicate,DocumentChange("sheet.rename",section,mapOf("name" to "원본")))
        XSSFWorkbook(renamed.inputStream()).use {w ->assertEquals(2,w.numberOfSheets);assertEquals("원본",w.getSheetName(0));val sheet=w.getSheetAt(1);assertEquals(1,sheet.numMergedRegions);assertTrue(sheet.paneInformation.isFreezePane);assertTrue(w.getFontAt(sheet.getRow(0).getCell(0).cellStyle.fontIndex).bold);assertEquals("0.00",sheet.getRow(0).getCell(0).cellStyle.dataFormatString)}
    }
    @Test fun presentationObjectsAndSlideOrderingRoundtrip() {
        val source=temp();Templates.create(Format.PPTX,source);val section=PackageDocument.open(source).sections[0].id
        val added=change(source,DocumentChange("slide.text",section,mapOf("text" to "실제 텍스트 상자","x" to "2","y" to "3","width" to "8","height" to "2")))
        val duplicate=change(added,DocumentChange("slide.duplicate",section))
        val newSec=PackageDocument.open(duplicate).sections[1].id
        val moved=change(duplicate,DocumentChange("slide.move",newSec,mapOf("index" to "0")))
        val deleted=change(moved,DocumentChange("slide.delete",section))
        XMLSlideShow(deleted.inputStream()).use {s ->assertEquals(1,s.slides.size);assertEquals(2,s.slides[0].shapes.size)}
        assertEquals("실제 텍스트 상자",PackageDocument.open(deleted).sections[0].fields.last().text)
    }
    @Test fun conditionalLookupAndFormattingFunctions() {
        val cells=mapOf("A1" to "apple","A2" to "pear","A3" to "apple","B1" to "10","B2" to "20","B3" to "30")
        fun evaluate(formula: String)=Formula(cells+("D1" to "=$formula")).display("D1")
        assertEquals("2",evaluate("COUNTIF(A1:A3,\"a*\")"));assertEquals("40",evaluate("SUMIF(A1:A3,\"apple\",B1:B3)"))
        assertEquals("20",evaluate("VLOOKUP(\"pear\",A1:B3,2,FALSE)"));assertEquals("30",evaluate("XLOOKUP(\"apple\",A1:A3,B1:B3,\"missing\",0,-1)"))
        assertEquals("20",evaluate("INDEX(A1:B3,MATCH(\"pear\",A1:A3,0),2)"));assertEquals("ok",evaluate("IFS(FALSE,1/0,TRUE,\"ok\")"))
        assertEquals("1,234.50",evaluate("TEXT(1234.5,\"#,##0.00\")"));assertEquals("#N/A",evaluate("MATCH(\"missing\",A1:A3,0)"))
    }
    @Test fun slideNotesRelationshipIsNotSharedByUnsafeDuplication() {
        val source=temp();XMLSlideShow().use {show->show.getNotesSlide(show.createSlide());source.outputStream().use {show.write(it)}}
        val section=PackageDocument.open(source).sections.single().id
        try {change(source,DocumentChange("slide.duplicate",section));fail("Expected notes relationship protection")} catch(e: IllegalArgumentException) {assertTrue(e.message!!.contains("노트"))}
        XMLSlideShow(source.inputStream()).use {assertNotNull(it.slides.single().notes)}
    }
    @Test fun hangulXmlTemplateStylesParagraphAndLayout() {
        val source=temp();Templates.create(Format.HWPX,source);val pack=PackageDocument.open(source);val field=pack.sections.first().fields.first()
        val styled=temp();pack.write(styled,Edits(values=mapOf(field.id to "정상 한글 문서"),styles=mapOf(field.id to mapOf("size" to "18","bold" to "true","color" to "334455","align" to "center","lineSpacing" to "1.5"))))
        val added=change(styled,DocumentChange("hwpx.paragraph",pack.sections.first().id,mapOf("text" to "새 문단")))
        assertEquals(listOf("정상 한글 문서","새 문단"),PackageDocument.open(added).sections.first().fields.map {it.text})
        val external=kr.dogfoot.hwpxlib.reader.HWPXReader.fromFile(added)
        assertNotNull(external)
        java.util.zip.ZipFile(added).use {zip->val header=Xml.parse(zip.getInputStream(zip.getEntry("Contents/header.xml")).readBytes());assertTrue(Xml.elements(header,"charPr").any {it.getAttribute("height")=="1800" && Xml.child(it,"bold")!=null})}
    }
    @Test fun sheetRenameUpdatesFormulasAndRetainsStringLiterals() {
        val source=temp();Templates.create(Format.XLSX,source);val section=PackageDocument.open(source).sections[0].id
        val values=temp();PackageDocument.open(source).write(values,Edits(values=mapOf("$section#A1" to "=SUM('시트1'!B1:B2)","$section#A2" to "=CONCAT(\"시트1!B1\")")))
        val renamed=change(values,DocumentChange("sheet.rename",section,mapOf("name" to "새 이름")))
        val cells=PackageDocument.open(renamed).sections[0].cells;assertEquals("=SUM('새 이름'!B1:B2)",cells["A1"]);assertEquals("=CONCAT(\"시트1!B1\")",cells["A2"])
    }
}
