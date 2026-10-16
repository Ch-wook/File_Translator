package kr.documentstudio.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile
import kr.dogfoot.hwpxlib.reader.HWPXReader

class HangulFormattingTest {
    private fun temp()=File.createTempFile("hangul-style-",".hwpx").apply {deleteOnExit()}
    private fun style(file: File,props: Map<String,String>): File {val p=PackageDocument.open(file);return temp().also {p.write(it,Edits(styles=mapOf(p.sections.first().fields.first().id to props)))}}
    @Test fun nativeCharacterParagraphListsStylesAndColumnsSurviveIndependentParser() {
        val base=temp();Templates.create(Format.HWPX,base)
        val styled=style(base,mapOf("tracking" to "-15","scale" to "85","offset" to "20","beforePt" to "12","afterPt" to "6","indentMm" to "4","firstLineMm" to "-2","lineSpacing" to "1.7","headingType" to "NUMBER","headingLevel" to "3","styleId" to "0"))
        val shown=PackageDocument.open(styled).sections.first().fields.first().styles
        assertEquals("-15",shown["tracking"]);assertEquals("85",shown["scale"]);assertEquals("20",shown["offset"]);assertEquals("3",shown["headingLevel"])
        val external=HWPXReader.fromFile(styled);assertNotNull(external)
        ZipFile(styled).use {zip->val header=Xml.parse(zip.getInputStream(zip.getEntry("Contents/header.xml")).readBytes());val para=Xml.elements(header,"paraPr").last()
            assertTrue(Xml.elements(para,"lineSpacing").all {it.getAttribute("value")=="170"})
            assertTrue(Xml.elements(para,"margin").all {Xml.child(it,"prev")!!.getAttribute("value")=="1200"})
            assertEquals("2",Xml.child(para,"heading")!!.getAttribute("level"))
            val char=Xml.elements(header,"charPr").last();assertEquals("85",Xml.child(char,"ratio")!!.getAttribute("latin"))
        }
        val bullets=style(styled,mapOf("headingType" to "BULLET"));assertNotNull(HWPXReader.fromFile(bullets));assertEquals("BULLET",PackageDocument.open(bullets).sections.first().fields.first().styles["headingType"])
        val columns=temp();PackageFeatures.apply(bullets,columns,change=DocumentChange("hwpx.columns",PackageDocument.open(bullets).sections.first().id,mapOf("columns" to "3","gapMm" to "5")))
        assertNotNull(HWPXReader.fromFile(columns));ZipFile(columns).use {z->val doc=Xml.parse(z.getInputStream(z.getEntry("Contents/section0.xml")).readBytes());assertEquals("3",Xml.elements(doc,"colPr").single().getAttribute("colCount"))}
    }
    @Test fun invalidShapeValuesAndMissingStyleDoNotWriteOutput() {
        val base=temp();Templates.create(Format.HWPX,base);val before=base.readBytes()
        for(p in listOf(mapOf("tracking" to "51"),mapOf("scale" to "20"),mapOf("offset" to "-101"),mapOf("styleId" to "9999"),mapOf("headingType" to "NUMBER","numberingId" to "9999"))) assertThrows(IllegalArgumentException::class.java) {style(base,p)}
        assertArrayEquals(before,base.readBytes())
    }
}
