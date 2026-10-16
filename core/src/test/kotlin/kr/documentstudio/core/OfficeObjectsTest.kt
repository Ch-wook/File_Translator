package kr.documentstudio.core

import org.junit.Assert.*
import org.junit.Test
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xslf.usermodel.XSLFPictureShape
import java.io.File
import javax.imageio.ImageIO
import java.awt.image.BufferedImage

class OfficeObjectsTest {
    private fun file(suffix: String=".zip")=File.createTempFile("office-objects-",suffix).apply {deleteOnExit()}
    private fun create(f: Format)=file().also {Templates.create(f,it)}
    private fun change(src: File,type: String,args: Map<String,String> = emptyMap(),attachment: File?=null)=file().also {PackageFeatures.apply(src,it,change=DocumentChange(type,PackageDocument.open(src).sections[0].id,args),attachment=attachment)}
    private fun png()=file(".png").also {ImageIO.write(BufferedImage(40,20,BufferedImage.TYPE_INT_ARGB),"png",it)}
    @Test fun picturesAreEmbeddedAndReadableByIndependentOfficeModels() {
        val image=png();val word=change(create(Format.DOCX),"word.image",mapOf("width" to "8"),image)
        XWPFDocument(word.inputStream()).use {doc->assertEquals(1,doc.allPictures.size);assertArrayEquals(image.readBytes(),doc.allPictures[0].data);assertTrue(doc.paragraphs.last().runs.last().embeddedPictures.isNotEmpty())}
        val slides=change(create(Format.PPTX),"slide.image",mapOf("width" to "8","x" to "2","y" to "3"),image)
        XMLSlideShow(slides.inputStream()).use {doc->val shape=doc.slides[0].shapes.last() as XSLFPictureShape;assertArrayEquals(image.readBytes(),shape.pictureData.data);assertEquals(8*72/2.54,shape.anchor.width,0.01);assertEquals(4*72/2.54,shape.anchor.height,0.01);assertEquals(2*72/2.54,shape.anchor.x,0.01)}
    }
    @Test fun hyperlinksHeadersAndPageFieldsRetainNativeRelationships() {
        val source=create(Format.DOCX)
        val linked=change(source,"word.hyperlink",mapOf("url" to "https://example.com/?a=1&b=2","text" to "실제 링크"))
        val header=change(linked,"word.header",mapOf("text" to "첫 머리글"));val header2=change(header,"word.header",mapOf("text" to "추가 문단"))
        val footer=change(header2,"word.footer",mapOf("text" to "문서 꼬리글","pageNumber" to "true"))
        XWPFDocument(footer.inputStream()).use {doc->assertEquals(1,doc.headerList.size);assertTrue(doc.headerList[0].text.contains("첫 머리글"));assertTrue(doc.headerList[0].text.contains("추가 문단"));assertTrue(doc.footerList[0].text.contains("문서 꼬리글"));assertEquals("https://example.com/?a=1&b=2",doc.hyperlinks.single().url);assertTrue(doc.footerList[0]._getHdrFtr().toString().contains("PAGE"))}
        try {change(source,"word.hyperlink",mapOf("url" to "javascript:alert(1)","text" to "bad"));fail("Expected blocked URI scheme")} catch(_: IllegalArgumentException) { }
    }
    @Test fun tableRowsColumnsCellStylesAreNativeAndPreserveText() {
        val table=change(create(Format.DOCX),"word.table",mapOf("rows" to "3","columns" to "3"))
        val row=change(table,"word.tableAddRow",mapOf("row" to "2"));val col=change(row,"word.tableAddColumn",mapOf("column" to "2"))
        val styled=change(col,"word.tableCellStyle",mapOf("row" to "2","column" to "2","background" to "EEDDCC","border" to "112233","vertical" to "center"))
        XWPFDocument(styled.inputStream()).use {doc->val t=doc.tables.single();assertEquals(4,t.numberOfRows);assertEquals(4,t.rows[1].tableCells.size);val cell=t.rows[1].getCell(1);assertEquals("EEDDCC",cell.color);assertNotNull(cell.ctTc.tcPr.tcBorders)}
        val removed=change(change(styled,"word.tableDeleteRow",mapOf("row" to "2")),"word.tableDeleteColumn",mapOf("column" to "2"))
        XWPFDocument(removed.inputStream()).use {doc->assertEquals(3,doc.tables.single().numberOfRows);assertEquals(3,doc.tables.single().rows[0].tableCells.size)}
    }
    @Test fun rectangularMergeAndSplitKeepAllContents() {
        val source=file();XWPFDocument().use {doc->val table=doc.createTable(3,3);for(r in 0..2) for(c in 0..2) table.getRow(r).getCell(c).setText("R${r+1}C${c+1}");source.outputStream().use {doc.write(it)}}
        val merged=change(source,"word.tableMerge",mapOf("row" to "1","column" to "1","lastRow" to "2","lastColumn" to "2"))
        XWPFDocument(merged.inputStream()).use {doc->val table=doc.tables.single();assertEquals(2,table.rows[0].tableCells.size);assertEquals(2,table.rows[0].getCell(0).ctTc.tcPr.gridSpan.`val`.toInt());assertNotNull(table.rows[1].getCell(0).ctTc.tcPr.vMerge);assertTrue(table.rows[0].getCell(0).text.contains("R2C2"))}
        val split=change(merged,"word.tableSplit",mapOf("row" to "1","column" to "1"))
        XWPFDocument(split.inputStream()).use {doc->val t=doc.tables.single();assertTrue(t.rows.all {it.tableCells.size==3});assertTrue(t.rows[0].getCell(0).text.contains("R2C2"));assertFalse(t.rows[0].getCell(0).ctTc.tcPr.isSetVMerge)}
    }
    @Test fun slideObjectOrderingAndDeletionPreserveUnrelatedObjects() {
        val source=create(Format.PPTX);val image=change(source,"slide.image",emptyMap(),png());val ordered=change(image,"slide.objectOrder",mapOf("shape" to "1","index" to "0"))
        XMLSlideShow(ordered.inputStream()).use {assertTrue(it.slides[0].shapes.first() is XSLFPictureShape)}
        val deleted=change(ordered,"slide.deleteObject",mapOf("shape" to "0"));XMLSlideShow(deleted.inputStream()).use {assertEquals(1,it.slides[0].shapes.size);assertEquals("새 프레젠테이션",(it.slides[0].shapes[0] as org.apache.poi.xslf.usermodel.XSLFTextShape).text)}
    }
}
