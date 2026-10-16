package kr.documentstudio.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import org.apache.poi.xssf.usermodel.XSSFWorkbook

class SpreadsheetModelTest {
    @Test fun crossSheetReferencesUseCorrectContextAndDetectCycles() {
        val sheets=mapOf("Data" to mapOf("A1" to "10","A2" to "20","B1" to "=IF(A1>0,A1+A2,1/0)"),"한글 시트" to mapOf("A1" to "=Data!B1","B1" to "=SUM(Data!A1:A2)"))
        assertEquals("30",Formula(sheets.getValue("한글 시트"),sheets,"한글 시트").display("A1"));assertEquals("30",Formula(sheets.getValue("한글 시트"),sheets,"한글 시트").display("B1"))
        val cyclic=mapOf("One" to mapOf("A1" to "=Two!A1"),"Two" to mapOf("A1" to "=One!A1"));assertEquals("#CYCLE!",Formula(cyclic.getValue("One"),cyclic,"One").display("A1"))
        assertEquals("#REF!",Formula(mapOf("A1" to "=Missing!A1"),sheets,"Data").display("A1"))
    }
    @Test fun escapedSheetNamesAndLazyBranchKeepTheirSheetContext() {
        val sheets=mapOf("O'Brien" to mapOf("A1" to "5"),"Other" to mapOf("A1" to "=IF(TRUE,'O''Brien'!A1,1/0)"))
        assertEquals("5",Formula(sheets.getValue("Other"),sheets,"Other").display("A1"))
    }
    @Test fun hugeIntegerAndConcatenationCannotOverflowOrGrowWithoutBound() {
        assertEquals("100000000000000000000",Formula(mapOf("A1" to "=10^20")).display("A1"))
        assertEquals("TRUEFALSE",Formula(mapOf("A1" to "=CONCAT(TRUE,FALSE)")).display("A1"))
        val cells=mutableMapOf("A1" to "x".repeat(20000));for(i in 2..8) cells["A$i"]="=CONCAT(A${i-1},A${i-1})"
        assertEquals("#LIMIT!",Formula(cells).display("A8"))
    }
    @Test fun datesCurrencyPercentageAnd1904EpochAreFormatted() {
        assertEquals("2024-01-01",CellFormatting.format("45292","yyyy-mm-dd"));assertEquals("1904-01-01",CellFormatting.format("0","yyyy-mm-dd",true))
        assertEquals("12.5%",CellFormatting.format("0.125","0.0%"));assertEquals("\$1,234.50",CellFormatting.format("1234.5","\"$\"#,##0.00"))
    }
    @Test fun builtinDateFormatsAndWorkbookEpochAreExposed() {
        val file=File.createTempFile("date-format-",".xlsx").apply {deleteOnExit()}
        XSSFWorkbook().use {w->w.ctWorkbook.workbookPr.date1904=true;val c=w.createSheet("Dates").createRow(0).createCell(0);c.setCellValue(1.0);c.cellStyle=w.createCellStyle().apply {dataFormat=14};file.outputStream().use {w.write(it)}}
        val section=PackageDocument.open(file).sections[0];assertTrue(section.date1904);assertEquals("m/d/yy",section.cellStyles["A1"]!!["numberFormat"])
    }
}
