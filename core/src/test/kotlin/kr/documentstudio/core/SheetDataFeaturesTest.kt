package kr.documentstudio.core

import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.ComparisonOperator
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class SheetDataFeaturesTest {
    private fun file()=File.createTempFile("sheet-data-",".xlsx").apply {deleteOnExit()}
    private fun source()=file().also {f->XSSFWorkbook().use {w->
        val s=w.createSheet("Data")
        listOf("Name","Amount","Status").forEachIndexed {i,v->(s.getRow(0) ?: s.createRow(0)).createCell(i).setCellValue(v)}
        s.createRow(1).createCell(1).setCellValue(125.0)
        s.getRow(1).getCell(1).cellStyle=w.createCellStyle().apply {dataFormat=w.createDataFormat().getFormat("0.00")}
        w.createSheet("Untouched").createRow(0).createCell(0).setCellValue("PRESERVE")
        f.outputStream().use {w.write(it)}
    }}
    private fun change(source: File,type: String,args: Map<String,String>)=file().also {
        PackageFeatures.apply(source,it,change=DocumentChange(type,PackageDocument.open(source).sections.first().id,args))
    }
    @Test fun bordersPreserveNumberFormatsAndUnrelatedParts() {
        val source=source();val result=change(source,"sheet.borders",mapOf("range" to "A1:C4","style" to "double","color" to "315D54"))
        XSSFWorkbook(result.inputStream()).use {w->
            val s=w.getSheetAt(0)
            assertEquals(BorderStyle.DOUBLE,s.getRow(3).getCell(2).cellStyle.borderBottom)
            assertEquals("0.00",s.getRow(1).getCell(1).cellStyle.dataFormatString)
            assertEquals("FF315D54",s.getRow(1).getCell(1).cellStyle.leftBorderXSSFColor.argbHex)
        }
        ZipFile(source).use {a->ZipFile(result).use {b->assertArrayEquals(a.getInputStream(a.getEntry("xl/worksheets/sheet2.xml")).readBytes(),b.getInputStream(b.getEntry("xl/worksheets/sheet2.xml")).readBytes())}}
        val cleared=change(result,"sheet.borders",mapOf("range" to "B2","style" to "none"))
        XSSFWorkbook(cleared.inputStream()).use {assertEquals(BorderStyle.NONE,it.getSheetAt(0).getRow(1).getCell(1).cellStyle.borderBottom)}
    }
    @Test fun conditionalRulesAndDropdownAreIndependentlyReadable() {
        var f=change(source(),"sheet.conditional",mapOf("range" to "B2:B8","kind" to "greaterThan","value" to "100","color" to "FFF2CC"))
        f=change(f,"sheet.conditional",mapOf("range" to "A2:A8","kind" to "duplicateValues"))
        f=change(f,"sheet.validation",mapOf("range" to "C2:C8","items" to "대기|진행|완료"))
        XSSFWorkbook(f.inputStream()).use {w->val s=w.getSheetAt(0)
            val cf=s.sheetConditionalFormatting
            assertEquals(2,cf.numConditionalFormattings)
            val rule=cf.getConditionalFormattingAt(0).getRule(0)
            assertEquals(ComparisonOperator.GT,rule.comparisonOperation);assertEquals("100.0",rule.formula1)
            assertEquals(2,rule.priority)
            assertEquals("FFFFF2CC",rule.patternFormatting!!.fillForegroundColorColor!!.let {it as org.apache.poi.xssf.usermodel.XSSFColor}.argbHex)
            val validation=s.dataValidations.single()
            assertEquals("C2:C8",validation.regions.cellRangeAddresses.single().formatAsString())
            assertArrayEquals(arrayOf("대기","진행","완료"),validation.validationConstraint.explicitListValues)
            // OOXML 0 displays the arrow in Excel/Calc; POI's boolean accessor is inverted.
            assertFalse(s.ctWorksheet.dataValidations.getDataValidationArray(0).showDropDown)
        }
        val cleared=change(f,"sheet.conditional",mapOf("range" to "B2:B8","kind" to "clear"))
        XSSFWorkbook(cleared.inputStream()).use {assertEquals(1,it.getSheetAt(0).sheetConditionalFormatting.numConditionalFormattings)}
    }
    @Test fun tableHasWorkingRelationshipsColumnsAndStyle() {
        val result=change(source(),"sheet.table",mapOf("range" to "A1:C4"))
        XSSFWorkbook(result.inputStream()).use {w->val table=w.getSheetAt(0).tables.single()
            assertEquals("A1:C4",table.area.formatAsString());assertEquals(listOf("Name","Amount","Status"),table.columns.map {it.name})
            assertEquals("TableStyleMedium2",table.styleName)
            assertEquals("PRESERVE",w.getSheetAt(1).getRow(0).getCell(0).stringCellValue)
        }
        val before=result.readBytes()
        try {change(result,"sheet.table",mapOf("range" to "B1:C3"));fail("Overlapping table")} catch(_: IllegalArgumentException) {assertArrayEquals(before,result.readBytes())}
    }
    @Test fun invalidAndOverlappingRequestsLeaveSourceUnchanged() {
        val source=change(source(),"sheet.validation",mapOf("range" to "A2:A4","items" to "one|two"));val before=source.readBytes()
        for((type,args) in listOf(
            "sheet.validation" to mapOf("range" to "A3:A5","items" to "a|b"),
            "sheet.validation" to mapOf("range" to "B2","items" to "a,b|c"),
            "sheet.table" to mapOf("range" to "A2:C5"),
            "sheet.borders" to mapOf("range" to "A1:XFD1048576"),
            "sheet.conditional" to mapOf("range" to "B2","value" to "NaN"))) {
            try {change(source,type,args);fail("Unsafe request: $type")} catch(_: IllegalArgumentException) {assertArrayEquals(before,source.readBytes())}
        }
    }
}
