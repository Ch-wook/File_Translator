package kr.documentstudio

import android.graphics.Bitmap
import android.view.MotionEvent
import android.widget.FrameLayout
import org.robolectric.RuntimeEnvironment
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import kr.documentstudio.core.*
import kr.documentstudio.data.*
import kr.documentstudio.ui.PdfObjectCanvas
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=StudioApplication::class)
class PdfDirectEditorTest {
    private fun temp()=File.createTempFile("pdf-direct-",".pdf").apply {deleteOnExit()}
    private fun fixture(): File {
        val file=temp()
        PDDocument().use {doc->
            val page=PDPage(PDRectangle(600f,800f));doc.addPage(page);doc.addPage(PDPage(PDRectangle(600f,800f)))
            val bitmap=Bitmap.createBitmap(16,8,Bitmap.Config.ARGB_8888).apply {eraseColor(0xff2878a0.toInt())}
            val image=LosslessFactory.createFromImage(doc,bitmap);bitmap.recycle()
            PDPageContentStream(doc,page).use {s->
                s.beginText();s.setFont(PDType1Font.HELVETICA,12f);s.newLineAtOffset(100f,700f);s.showText("ORIGINAL");s.newLineAtOffset(160f,-30f);s.showText("KEEP");s.endText()
                s.drawImage(image,60f,300f,120f,60f);s.drawImage(image,300f,300f,120f,60f)
            }
            PDPageContentStream(doc,doc.getPage(1)).use {s->s.beginText();s.setFont(PDType1Font.HELVETICA,18f);s.newLineAtOffset(60f,600f);s.showText("UNTOUCHED PAGE");s.endText()}
            doc.save(file)
        }
        return file
    }
    @Test fun pageCoordinatesIdentifyExactTextOperatorsAndRespectCropAndRotation() {
        val file=fixture();val runs=PdfBodyText.runs(file,0)
        assertEquals(listOf("ORIGINAL","KEEP"),runs.map {it.text})
        assertEquals(100f/600*100,runs[0].bounds!!.x,0.01f)
        assertEquals(260f/600*100,runs[1].bounds!!.x,0.01f)
        assertTrue(runs[1].bounds!!.y>runs[0].bounds!!.y)
        val crop=temp();PDDocument.load(file).use {doc->doc.getPage(0).cropBox=PDRectangle(50f,30f,500f,700f);doc.save(crop)}
        assertEquals(10f,PdfBodyText.runs(crop,0)[0].bounds!!.x,0.01f)
        val rotated=temp();PDDocument.load(crop).use {doc->doc.getPage(0).rotation=90;doc.save(rotated)}
        assertTrue(PdfBodyText.runs(rotated,0).all {it.bounds==null})
    }
    @Test fun typingJournalSurvivesRecoveryAndSavingWithoutTouchingOtherPageOrImageOccurrences() {
        val file=fixture();val original=file.readBytes();val run=PdfBodyText.runs(file,0).first()
        val key=PdfTextEdits.key(0,run.id)
        val edits=Edits(values=mapOf(key to PdfTextEdits.value(null,run.text,"CHANGED")))
        val restored=StateCodec.decode(StateCodec.encode(edits));assertEquals(edits,restored)
        val output=temp();PdfEngine.write(file,output,emptyList(),false,restored.values)
        assertEquals(listOf("CHANGED","KEEP"),PdfBodyText.runs(output,0).map {it.text})
        assertEquals(PdfImages.list(file,0),PdfImages.list(output,0))
        PDDocument.load(file).use {a->PDDocument.load(output).use {b->assertArrayEquals(a.getPage(1).contents.readBytes(),b.getPage(1).contents.readBytes())}}
        assertArrayEquals(original,file.readBytes())
        val journal=PdfTextEdits.value(restored.values[key],"CHANGED","AGAIN")
        assertEquals("ORIGINAL",org.json.JSONObject(journal).getString("expected"))
    }
    @Test fun draggingAnImageOnThePageSavesOnlyThatOccurrenceAndCanBeCancelled() {
        val file=fixture();val image=PdfImages.list(file,0).first()
        val context=RuntimeEnvironment.getApplication()
        val changes=mutableListOf<DocumentChange>()
        val canvas=PdfObjectCanvas(context).apply {editable=true;page=0;images=PdfImages.list(file,0);onCommit={changes+=it}}
        FrameLayout(context).addView(canvas);canvas.layout(0,0,1000,1000)
        fun touch(action: Int,x: Float,y: Float) {val event=MotionEvent.obtain(0,1,action,x,y,0);canvas.onTouchEvent(event);event.recycle()}
        val x=(image.x+image.width/2)*10;val y=(image.y+image.height/2)*10
        touch(MotionEvent.ACTION_DOWN,x,y);touch(MotionEvent.ACTION_MOVE,x+50,y+30);touch(MotionEvent.ACTION_UP,x+50,y+30)
        assertEquals(1,changes.size)
        val output=temp();PdfEngine.change(file,output,changes.single())
        val after=PdfImages.list(output,0)
        assertEquals(image.x+5,after[0].x,0.01f);assertEquals(image.y+3,after[0].y,0.01f)
        assertEquals(PdfImages.list(file,0)[1],after[1])
        touch(MotionEvent.ACTION_DOWN,x,y);touch(MotionEvent.ACTION_MOVE,x+80,y+50);touch(MotionEvent.ACTION_CANCEL,x+80,y+50)
        assertEquals(1,changes.size)
    }
    @Test fun readingCannotSelectTypeOrDragAndReturningToReadingClearsTheEditor() {
        val file=fixture();val context=RuntimeEnvironment.getApplication()
        val canvas=PdfObjectCanvas(context).apply {page=0;textRuns=PdfBodyText.runs(file,0);images=PdfImages.list(file,0)}
        FrameLayout(context).addView(canvas);canvas.layout(0,0,1000,1000)
        val changes=mutableListOf<DocumentChange>();canvas.onCommit={changes+=it};canvas.onPreview={changes+=it}
        val image=canvas.images.first();val x=(image.x+image.width/2)*10;val y=(image.y+image.height/2)*10
        fun touch(action: Int): Boolean {val e=MotionEvent.obtain(0,1,action,x,y,0);return try {canvas.onTouchEvent(e)} finally {e.recycle()}}
        assertFalse(canvas.editable);assertFalse(touch(MotionEvent.ACTION_DOWN));assertNull(canvas.selection)
        canvas.editable=true;assertTrue(touch(MotionEvent.ACTION_DOWN));assertNotNull(canvas.selection)
        canvas.editable=false;assertNull(canvas.selection);assertFalse(touch(MotionEvent.ACTION_MOVE));assertTrue(changes.isEmpty())
    }
    @Test fun unsupportedGlyphsDoNotPublishOutputOrOverwriteTheSource() {
        val file=fixture();val bytes=file.readBytes();val run=PdfBodyText.runs(file,0).first();val output=temp()
        assertThrows(IllegalArgumentException::class.java) {PdfEngine.write(file,output,emptyList(),false,mapOf(PdfTextEdits.key(0,run.id) to PdfTextEdits.value(null,run.text,"한글 불가")))}
        assertArrayEquals(bytes,file.readBytes())
    }
}
