package kr.documentstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.util.Matrix
import kr.documentstudio.core.DocumentChange
import kr.documentstudio.data.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

class PdfImageEditingTest {
    private lateinit var context: Context
    @Before fun setup() {context=ApplicationProvider.getApplicationContext();PDFBoxResourceLoader.init(context)}
    private fun file()=File(context.cacheDir,"tests/${UUID.randomUUID()}.pdf").apply {parentFile!!.mkdirs()}
    private fun source(rotation: Int=0,clip: Boolean=false): File = file().also {f->PDDocument().use {doc->
        val page=PDPage(PDRectangle(220f,240f)).apply {cropBox=PDRectangle(10f,20f,200f,200f);this.rotation=rotation};doc.addPage(page)
        val bitmap=Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.RED)}
        try {
            val image=LosslessFactory.createFromImage(doc,bitmap)
            PDPageContentStream(doc,page).use {stream->
                stream.saveGraphicsState();stream.transform(Matrix(1f,0f,0f,1f,10f,20f))
                if(clip) {stream.addRect(0f,0f,80f,80f);stream.clip()}
                stream.drawImage(image,20f,20f,40f,40f);stream.restoreGraphicsState()
                stream.drawImage(image,130f,40f,40f,40f)
            }
            doc.save(f)
        } finally {bitmap.recycle()}
    }}
    private fun transform(source: File,p: PdfImagePlacement,args: Map<String,String>)=file().also {out->PdfEngine.change(source,out,DocumentChange("pdf.transformImage","0",mapOf("id" to p.id.toString(),"expected" to p.token)+args))}
    @Test fun movingOneImageOccurrenceDoesNotMoveSharedResourceOrOtherImage() {
        val original=source();val placements=PdfImages.list(original,0);assertEquals(2,placements.size)
        val changed=transform(original,placements.first(),mapOf("x" to "10","y" to "10","width" to "20","height" to "20"))
        val bitmap=PdfEngine.render(changed,0,200)
        try {
            assertEquals(Color.RED,bitmap.getPixel(40,40));assertEquals(Color.WHITE,bitmap.getPixel(40,160));assertEquals(Color.RED,bitmap.getPixel(140,160))
        } finally {bitmap.recycle()}
        val updated=PdfImages.list(changed,0);assertEquals(placements[1],updated[1]);assertEquals(10f,updated[0].x,0.001f)
        assertEquals(70f,PdfImages.list(original,0)[0].y,0.001f)
    }
    @Test fun rotationAndCropCoordinatesRemainInDisplayedPageSpace() {
        for(rotation in listOf(0,90,180,270)) {
            val original=source(rotation);val changed=transform(original,PdfImages.list(original,0).first(),mapOf("x" to "60","y" to "5","width" to "25","height" to "15"))
            val p=PdfImages.list(changed,0).first();assertEquals(60f,p.x,0.001f);assertEquals(5f,p.y,0.001f);assertEquals(25f,p.width,0.001f);assertEquals(15f,p.height,0.001f)
            val bitmap=PdfEngine.render(changed,0,200)
            try {assertEquals("rotation=$rotation",Color.RED,bitmap.getPixel((bitmap.width*0.725f).toInt(),(bitmap.height*0.125f).toInt()))} finally {bitmap.recycle()}
        }
    }
    @Test fun imageDeletionRemovesOnlySelectedPaintOperation() {
        val original=source();val selected=PdfImages.list(original,0).first();val out=file()
        PdfEngine.change(original,out,DocumentChange("pdf.deleteImage","0",mapOf("id" to selected.id.toString(),"expected" to selected.token)))
        assertEquals(1,PdfImages.list(out,0).size)
        val bitmap=PdfEngine.render(out,0,200)
        try {assertEquals(Color.WHITE,bitmap.getPixel(40,160));assertEquals(Color.RED,bitmap.getPixel(140,160))} finally {bitmap.recycle()}
    }
    @Test fun clippedImagesStaleSelectionAndOutsideBoundsAreProtected() {
        val clipped=source(clip=true);val listed=PdfImages.list(clipped,0);assertEquals(1,listed.size);assertEquals(1,listed.single().id)
        val original=source();val bytes=original.readBytes();val first=PdfImages.list(original,0).first()
        for((p,args) in listOf(first.copy(token="stale") to mapOf("x" to "0","y" to "0","width" to "10","height" to "10"),first to mapOf("x" to "90","y" to "0","width" to "20","height" to "10"))) {
            try {transform(original,p,args);fail("Unsafe transform accepted")} catch(e: IllegalArgumentException) {assertArrayEquals(bytes,original.readBytes())}
        }
    }
}
