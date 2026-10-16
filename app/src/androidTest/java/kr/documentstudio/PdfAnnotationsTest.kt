package kr.documentstudio

import android.content.Context
import android.graphics.Color
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.*
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import kr.documentstudio.core.DocumentChange
import kr.documentstudio.data.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

class PdfAnnotationsTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    @Before fun setup() {PDFBoxResourceLoader.init(context)}
    private fun file()=File(context.cacheDir,"tests/${UUID.randomUUID()}.pdf").apply {parentFile!!.mkdirs()}
    private fun uri(f: File)=FileProvider.getUriForFile(context,"kr.documentstudio.testfiles",f)
    private fun source(rotation: Int=0): File = file().also {out ->PDDocument().use {doc ->
        val page=PDPage(PDRectangle(430f,320f)).apply {cropBox=PDRectangle(20f,30f,350f,240f);this.rotation=rotation};doc.addPage(page)
        PDPageContentStream(doc,page).use {stream ->
            stream.beginText();stream.setFont(PDType1Font.HELVETICA,20f);stream.newLineAtOffset(60f,200f);stream.showText("ALPHA BETA")
            stream.newLineAtOffset(0f,-50f);stream.showText("SECOND LINE");stream.endText()
        }
        page.annotations.add(PDAnnotationText().apply {contents="FOREIGN_NOTE";rectangle=PDRectangle(290f,60f,20f,20f);cosObject.setString("VendorFlag","KEEP")})
        doc.save(out)
    }}
    private fun add(base: File,kind: String="Highlight",content: String="BETA"): File {
        val text=PdfAnnotations.selectable(base,0).text;val start=text.indexOf(content);assertTrue(text,start>=0)
        return file().also {PdfEngine.change(base,it,DocumentChange("pdf.markupAdd","0",mapOf("first" to "$start","last" to "${start+content.length}","expected" to content,"kind" to kind,"color" to "FFE36B")))}
    }
    @Test fun standardMarkupCoordinatesAppearancesAndNativeOverlayRespectRotationAndCrop() {
        for(rotation in listOf(0,90,180,270)) for(kind in listOf("Highlight","Underline","StrikeOut")) {
            val original=source(rotation);val changed=add(original,kind)
            PDDocument.load(original).use {before ->PDDocument.load(changed).use {after ->
                assertEquals(PDFTextStripper().getText(before),PDFTextStripper().getText(after))
                assertEquals(2,after.getPage(0).annotations.size)
                assertEquals("KEEP",after.getPage(0).annotations[0].cosObject.getString("VendorFlag"))
                val ann=after.getPage(0).annotations[1] as PDAnnotationTextMarkup
                assertEquals(kind,ann.subtype);assertEquals("BETA",ann.contents);assertNotNull(ann.normalAppearanceStream)
                assertTrue(ann.quadPoints.size>=8 && ann.quadPoints.size%8==0)
                val xs=ann.quadPoints.filterIndexed {i,_ -> i%2==0};val ys=ann.quadPoints.filterIndexed {i,_ -> i%2==1}
                assertTrue("crop/rotation x: $xs",xs.all {it in 100f..250f})
                assertTrue("crop/rotation y: $ys",ys.all {it in 190f..235f})
            }}
            val before=PdfEngine.render(original,0,800,800);val after=PdfEngine.render(changed,0,800,800)
            try {
                assertEquals(before.width,after.width);assertEquals(before.height,after.height)
                var changedPixels=0;var untouchedDark=0
                for(y in 0 until before.height) for(x in 0 until before.width) {
                    val a=before.getPixel(x,y);val b=after.getPixel(x,y)
                    if(kotlin.math.abs(Color.red(a)-Color.red(b))+kotlin.math.abs(Color.green(a)-Color.green(b))+kotlin.math.abs(Color.blue(a)-Color.blue(b))>30) changedPixels++
                    if(Color.red(a)<60 && Color.green(a)<60 && Color.blue(a)<60 && a==b) untouchedDark++
                }
                assertTrue("$rotation/$kind appearance must be visible",changedPixels>10)
                assertTrue("native body must remain visible",untouchedDark>30)
            } finally {before.recycle();after.recycle()}
        }
    }
    @Test fun editDeleteStaleSelectionAndForeignPropertiesArePreserved() {
        val original=source();val highlighted=add(original);val ann=PdfAnnotations.list(highlighted,0).last()
        val edited=file();PdfEngine.change(highlighted,edited,DocumentChange("pdf.annotationEdit","0",mapOf("id" to "${ann.index}","expected" to ann.expected,"text" to "REVIEW","color" to "BC4949")))
        assertEquals("REVIEW",PdfAnnotations.list(edited,0).last().text)
        assertThrows(IllegalArgumentException::class.java) {PdfEngine.change(edited,file(),DocumentChange("pdf.annotationDelete","0",mapOf("id" to "${ann.index}","expected" to ann.expected)))}
        val fresh=PdfAnnotations.list(edited,0).last();val deleted=file()
        PdfEngine.change(edited,deleted,DocumentChange("pdf.annotationDelete","0",mapOf("id" to "${fresh.index}","expected" to fresh.expected)))
        PDDocument.load(deleted).use {doc ->assertEquals(1,doc.getPage(0).annotations.size);assertEquals("KEEP",doc.getPage(0).annotations[0].cosObject.getString("VendorFlag"))}
        assertEquals(1,PdfAnnotations.list(original,0).size)
        val bad=DocumentChange("pdf.markupAdd","0",mapOf("first" to "0","last" to "4","expected" to "WRONG","kind" to "Highlight"))
        assertThrows(IllegalArgumentException::class.java) {PdfEngine.change(original,file(),bad)}
    }
    @Test fun multiLineSelectionSaveReopenAndUndoBaselineKeepNativeAnnotations() = runBlocking {
        val original=source();val repo=DocumentRepository(context);val opened=repo.open(uri(original))
        val text=PdfAnnotations.selectable(opened.base,0).text
        val changed=repo.change(opened,DocumentChange("pdf.markupAdd","0",mapOf("first" to "0","last" to "${text.trimEnd().length}","expected" to text.trimEnd(),"kind" to "Underline","color" to "315FBB")))
        PDDocument.load(changed.base).use {val ann=it.getPage(0).annotations.last() as PDAnnotationTextMarkup;assertTrue(ann.quadPoints.size>=16)}
        assertEquals(1,PdfAnnotations.list(opened.base,0).size)
        val output=file().apply {writeBytes(byteArrayOf())};repo.save(changed,uri(output))
        val portable=file();output.copyTo(portable,overwrite=true)
        val reopened=repo.open(uri(portable));assertEquals(2,PdfAnnotations.list(reopened.base,0).size)
        assertTrue(0 in reopened.pdf!!.annotatedPages)
        val bitmap=repo.render(reopened,0);bitmap.recycle()
    }
    @Test fun repliesProtectTheirParentFromDeletion() {
        val highlighted=add(source());val withReply=file()
        PDDocument.load(highlighted).use {doc ->
            val parent=doc.getPage(0).annotations.last()
            doc.getPage(0).annotations.add(PDAnnotationText().apply {contents="REPLY";rectangle=PDRectangle(50f,50f,20f,20f);cosObject.setItem(COSName.getPDFName("IRT"),parent.cosObject)})
            doc.save(withReply)
        }
        val ann=PdfAnnotations.list(withReply,0)[1]
        assertThrows(IllegalArgumentException::class.java) {PdfEngine.change(withReply,file(),DocumentChange("pdf.annotationDelete","0",mapOf("id" to "${ann.index}","expected" to ann.expected)))}
        assertEquals(3,PdfAnnotations.list(withReply,0).size)
    }
}
