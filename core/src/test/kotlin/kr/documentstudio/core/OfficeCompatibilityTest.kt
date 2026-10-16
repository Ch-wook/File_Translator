package kr.documentstudio.core

import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xslf.usermodel.XSLFTextShape
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class OfficeCompatibilityTest {
    private fun temp()=Files.createTempFile("office-compat-",".bin").toFile().apply {deleteOnExit()}
    @Test fun newlyCreatedFilesOpenInIndependentOfficeParser() {
        val docx=temp();Templates.create(Format.DOCX,docx);XWPFDocument(docx.inputStream()).use {assertEquals("새 문서",it.paragraphs[0].text)}
        val xlsx=temp();Templates.create(Format.XLSX,xlsx);XSSFWorkbook(xlsx.inputStream()).use {assertEquals("시트1",it.getSheetAt(0).sheetName)}
        val pptx=temp();Templates.create(Format.PPTX,pptx);XMLSlideShow(pptx.inputStream()).use {assertEquals(1,it.slides.size);assertEquals("새 프레젠테이션",(it.slides[0].shapes[0] as XSLFTextShape).text)}
    }
    @Test fun existingWordFormattingAndTablesSurviveEdit() {
        val original=temp();XWPFDocument().use { doc ->
            val run=doc.createParagraph().createRun();run.setText("before");run.isItalic=true;run.fontSize=18;run.setColor("AA3322")
            doc.createTable(2,2).getRow(0).getCell(0).text="table kept"
            original.outputStream().use {doc.write(it)}
        }
        val pack=PackageDocument.open(original);val output=temp();pack.write(output,Edits(values=mapOf(pack.sections.first().fields.first().id to "after 한글")))
        XWPFDocument(output.inputStream()).use {doc ->
            assertEquals("after 한글",doc.paragraphs.first().text);assertTrue(doc.paragraphs.first().runs.first().isItalic)
            assertEquals(18,doc.paragraphs.first().runs.first().fontSize);assertEquals("AA3322",doc.paragraphs.first().runs.first().color)
            assertEquals("table kept",doc.tables.first().getRow(0).getCell(0).text)
        }
    }
    @Test fun existingSpreadsheetStylesHiddenSheetAndFormulaSurvive() {
        val original=temp();XSSFWorkbook().use { wb ->
            val sh=wb.createSheet("main");val a=sh.createRow(0).createCell(0);a.setCellValue("shared text")
            a.cellStyle=wb.createCellStyle().apply {dataFormat=wb.createDataFormat().getFormat("0.00")}
            sh.getRow(0).createCell(1).cellFormula="SUM(A2:A3)";sh.createRow(1).createCell(0).setCellValue(2.0);sh.createRow(2).createCell(0).setCellValue(3.0)
            wb.createSheet("hidden");wb.setSheetHidden(1,true)
            original.outputStream().use {wb.write(it)}
        }
        val pack=PackageDocument.open(original);val output=temp();pack.write(output,Edits(values=mapOf("${pack.sections.first().id}#A1" to "updated","${pack.sections.first().id}#A2" to "10")))
        XSSFWorkbook(output.inputStream()).use {wb ->
            assertTrue(wb.isSheetHidden(1));val sh=wb.getSheetAt(0)
            assertEquals("updated",sh.getRow(0).getCell(0).stringCellValue);assertEquals("0.00",sh.getRow(0).getCell(0).cellStyle.dataFormatString)
            assertEquals("SUM(A2:A3)",sh.getRow(0).getCell(1).cellFormula)
            assertEquals(13.0,wb.creationHelper.createFormulaEvaluator().evaluate(sh.getRow(0).getCell(1)).numberValue,0.0)
        }
    }
    @Test fun existingPowerPointShapePositionSurvivesTextEdit() {
        val original=temp();XMLSlideShow().use {ppt ->
            val slide=ppt.createSlide();val shape=slide.createTextBox();shape.text="before";shape.anchor=java.awt.geom.Rectangle2D.Double(31.0,42.0,180.0,100.0)
            original.outputStream().use {ppt.write(it)}
        }
        val pack=PackageDocument.open(original);val output=temp();pack.write(output,Edits(values=mapOf(pack.sections.first().fields.first().id to "changed")))
        XMLSlideShow(output.inputStream()).use {ppt ->val shape=ppt.slides.first().shapes.first() as XSLFTextShape;assertEquals("changed",shape.text);assertEquals(31.0,shape.anchor.x,0.01);assertEquals(42.0,shape.anchor.y,0.01)}
    }
}
