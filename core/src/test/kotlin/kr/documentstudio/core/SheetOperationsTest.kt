package kr.documentstudio.core

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SheetOperationsTest {
    private fun file()=File.createTempFile("sheet-ops-",".xlsx").apply {deleteOnExit()}
    private fun workbook(block: (XSSFWorkbook)->Unit)=file().also {f->XSSFWorkbook().use {w->block(w);f.outputStream().use {w.write(it)}}}
    private fun change(source: File,type: String,args: Map<String,String>,index: Int=0)=file().also {PackageFeatures.apply(source,it,change=DocumentChange(type,PackageDocument.open(source).sections[index].id,args))}
    @Test fun formulaCopyHonorsAbsoluteReferencesStringsFunctionsAndExternalBooks() {
        assertEquals("B3+\$B3+C\$3+\$D\$4+LOG10(100)+\"A1\"+'O''Brien'!B3+[1]Data!A1+Table1[A1]",SheetReferences.copy("A1+\$B1+B\$3+\$D\$4+LOG10(100)+\"A1\"+'O''Brien'!A1+[1]Data!A1+Table1[A1]",2,1))
        assertEquals("#REF!",SheetReferences.copy("A1",-1,0));assertEquals("B:D+3:5",SheetReferences.copy("A:C+1:3",2,1))
    }
    @Test fun rangeCopyRetainsFormattingAndTranslatesFormulaOnOverlap() {
        val source=workbook {w->val s=w.createSheet("Data");s.createRow(0).createCell(0).setCellValue(12.0);s.getRow(0).getCell(0).cellStyle=w.createCellStyle().apply {dataFormat=w.createDataFormat().getFormat("0.00")};s.getRow(0).createCell(1).cellFormula="A1*2"}
        val result=change(source,"sheet.copyRange",mapOf("source" to "A1:B1","destination" to "B1"))
        XSSFWorkbook(result.inputStream()).use {w->val r=w.getSheetAt(0).getRow(0);assertEquals(12.0,r.getCell(1).numericCellValue,0.0);assertEquals("0.00",r.getCell(1).cellStyle.dataFormatString);assertEquals("B1*2",r.getCell(2).cellFormula)}
    }
    @Test fun fillCreatesSeriesAndRepeatsRelativeFormula() {
        val source=workbook {w->val s=w.createSheet("Data");s.createRow(0).createCell(0).setCellValue(2.0);s.createRow(1).createCell(0).setCellValue(5.0);s.getRow(0).createCell(1).cellFormula="A1+\$A\$1"}
        val series=change(source,"sheet.fill",mapOf("source" to "A1:A2","destination" to "A1:A5"))
        val formulas=change(series,"sheet.fill",mapOf("source" to "B1","destination" to "B1:B5"))
        XSSFWorkbook(formulas.inputStream()).use {w->val s=w.getSheetAt(0);assertEquals(14.0,s.getRow(4).getCell(0).numericCellValue,0.0);assertEquals("A5+\$A\$1",s.getRow(4).getCell(1).cellFormula)}
    }
    @Test fun rowInsertUpdatesOtherSheetsNamesMergesWidthsAndAbsoluteReferences() {
        val source=workbook {w->val s=w.createSheet("Data");s.createRow(0).createCell(0).setCellValue(1.0);s.createRow(1).createCell(0).setCellValue(2.0);s.createRow(2).createCell(0).cellFormula="SUM(\$A\$1:A2)";s.addMergedRegion(org.apache.poi.ss.util.CellRangeAddress(3,4,1,2));s.createFreezePane(0,1);w.createSheet("Other").createRow(0).createCell(0).cellFormula="Data!\$A\$2+SUM(Data!A1:A3)";w.createName().apply {nameName="Source";refersToFormula="Data!\$A\$1:\$A\$3"}}
        val result=change(source,"sheet.insertRows",mapOf("index" to "2","count" to "2"))
        XSSFWorkbook(result.inputStream()).use {w->val s=w.getSheetAt(0);assertEquals(2.0,s.getRow(3).getCell(0).numericCellValue,0.0);assertEquals("SUM(\$A\$1:A4)",s.getRow(4).getCell(0).cellFormula);assertEquals("Data!\$A\$4+SUM(Data!A1:A5)",w.getSheetAt(1).getRow(0).getCell(0).cellFormula);assertEquals("Data!\$A\$1:\$A\$5",w.getName("Source").refersToFormula);assertEquals("B6:C7",s.getMergedRegion(0).formatAsString())}
    }
    @Test fun rowDeletionShrinksRangesAndInvalidatesDeletedCell() {
        val source=workbook {w->val s=w.createSheet("Data");for(i in 0..5) s.createRow(i).createCell(0).setCellValue((i+1).toDouble());s.getRow(5).createCell(1).cellFormula="SUM(A1:A5)+A3"}
        val result=change(source,"sheet.deleteRows",mapOf("index" to "2","count" to "3"))
        XSSFWorkbook(result.inputStream()).use {w->val s=w.getSheetAt(0);assertEquals(5.0,s.getRow(1).getCell(0).numericCellValue,0.0);assertEquals("SUM(A1:A2)+#REF!",s.getRow(2).getCell(1).cellFormula)}
    }
    @Test fun columnInsertionAndDeletionMoveCellsAndWholeColumnNames() {
        val source=workbook {w->val s=w.createSheet("Data");s.createRow(0).createCell(1).setCellValue("B");s.getRow(0).createCell(2).cellFormula="B1";w.createName().apply {nameName="Columns";refersToFormula="Data!\$A:\$C"}}
        val added=change(source,"sheet.insertColumns",mapOf("index" to "2","count" to "2"))
        XSSFWorkbook(added.inputStream()).use {w->assertEquals("B",w.getSheetAt(0).getRow(0).getCell(3).stringCellValue);assertEquals("D1",w.getSheetAt(0).getRow(0).getCell(4).cellFormula);assertEquals("Data!\$A:\$E",w.getName("Columns").refersToFormula)}
        val removed=change(added,"sheet.deleteColumns",mapOf("index" to "2","count" to "2"));XSSFWorkbook(removed.inputStream()).use {assertEquals("B1",it.getSheetAt(0).getRow(0).getCell(2).cellFormula)}
    }
    @Test fun tabularPasteSortAndClearKeepDataAndRelativeFormulas() {
        val source=workbook {it.createSheet("Data")}
        val pasted=change(source,"sheet.paste",mapOf("range" to "A1","text" to "Name\tValue\tFormula\npear\t20\t=B2*2\napple\t10\t=B3*2"))
        val sorted=change(pasted,"sheet.sort",mapOf("range" to "A1:C3","column" to "2","header" to "true"))
        XSSFWorkbook(sorted.inputStream()).use {w->val s=w.getSheetAt(0);assertEquals("apple",s.getRow(1).getCell(0).stringCellValue);assertEquals("B2*2",s.getRow(1).getCell(2).cellFormula);assertEquals("pear",s.getRow(2).getCell(0).stringCellValue)}
        val cleared=change(sorted,"sheet.clearRange",mapOf("range" to "B2:C3"));assertEquals("",PackageDocument.open(cleared).sections[0].cells["B2"] ?: "")
    }
    @Test fun mergedAndArrayFormulaEditsFailWithoutChangingOriginal() {
        val source=workbook {w->val s=w.createSheet("Data");s.addMergedRegion(org.apache.poi.ss.util.CellRangeAddress(0,1,0,1));s.setArrayFormula("SUM(C1:C2)",org.apache.poi.ss.util.CellRangeAddress(0,1,2,2))}
        val before=source.readBytes()
        for(range in listOf("A1:B2","C2")) try {change(source,"sheet.clearRange",mapOf("range" to range));fail("Expected preservation guard")} catch(_: IllegalArgumentException) {assertArrayEquals(before,source.readBytes())}
    }
    @Test fun clipboardRoundtripPreservesTabsNewlinesQuotesAndEmptyCells() {
        val rows=listOf(listOf("a\tb","line1\nline2","quote \" text",""),listOf("","=A1+2","", "last"))
        assertEquals(rows,TabularText.decode(TabularText.encode(rows)));assertEquals(listOf(listOf("a","")),TabularText.decode("a\t\r\n"))
        try {TabularText.decode("\"unterminated");fail("Expected parse rejection")} catch(_: IllegalArgumentException) { }
    }
}
