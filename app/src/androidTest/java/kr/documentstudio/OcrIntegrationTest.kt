package kr.documentstudio

import android.app.Application
import android.graphics.*
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kotlinx.coroutines.*
import kr.documentstudio.core.*
import kr.documentstudio.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class OcrIntegrationTest {
    private val app=ApplicationProvider.getApplicationContext<Application>()
    private fun file(ext: String)=File(app.cacheDir,"tests/${UUID.randomUUID()}.$ext").apply {parentFile!!.mkdirs();writeBytes(byteArrayOf())}
    private fun uri(file: File)=FileProvider.getUriForFile(app,"kr.documentstudio.testfiles",file)
    private fun bitmap(korean: Boolean)=Bitmap.createBitmap(1400,600,Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.WHITE);val p=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.BLACK;textSize=64f;typeface=Typeface.createFromAsset(app.assets,"fonts/NotoSansKR.ttf")}
        val c=Canvas(this);if(korean) c.drawText("문서 편집 한글 테스트",60f,160f,p);c.drawText("ANDROID FILE STUDIO 2026",60f,300f,p)
    }
    @Test fun bundledKoreanAndEnglishModelsRecognizeWithoutInternetPermission()=runBlocking {
        assertFalse(app.packageManager.getPackageInfo(app.packageName,android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().contains("android.permission.INTERNET"))
        for(korean in listOf(true,false)) {
            val bitmap=bitmap(korean);val result=OcrEngine.recognizeOwned(bitmap,korean);assertTrue(bitmap.isRecycled)
            val text=result.joinToString(" ") {it.text};assertTrue(text,text.contains("ANDROID"));assertTrue(text,text.contains("2026"));if(korean) assertTrue(text,text.contains("한글"))
            assertTrue(result.all {it.left>=0f && it.right<=1f && it.bottom>it.top})
        }
    }
    @Test fun scannedPdfBecomesSearchableWithSamePixelsAndNativeRoundTrip()=runBlocking {
        val scan=file("pdf");val bitmap=bitmap(false)
        val scanDoc=android.graphics.pdf.PdfDocument();try {val doc=scanDoc;val page=doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(700,300,1).create());page.canvas.drawBitmap(bitmap,null,RectF(0f,0f,700f,300f),null);doc.finishPage(page);scan.outputStream().use {doc.writeTo(it)}} finally {scanDoc.close();bitmap.recycle()}
        val repo=DocumentRepository(app);val s=repo.open(uri(scan));assertTrue(PdfEngine.text(s.base).isBlank())
        val before=PdfEngine.render(s.base,0);val recognize=before.copy(Bitmap.Config.ARGB_8888,false)
        val lines=OcrEngine.recognizeOwned(recognize,false);assertTrue(lines.isNotEmpty())
        val changed=repo.change(s,DocumentChange("pdf.ocrPages","0",mapOf("base" to s.base.name,"pages" to OcrEngine.json(listOf(OcrPage(0,lines))))))
        assertEquals(listOf(0),PdfEngine.search(changed.base,"ANDROID"));assertTrue(PdfEngine.text(s.base).isBlank())
        val after=PdfEngine.render(changed.base,0);assertEquals(before.width,after.width);assertEquals(before.height,after.height)
        for(y in 0 until before.height step 11) for(x in 0 until before.width step 11) assertEquals(before.getPixel(x,y),after.getPixel(x,y));before.recycle();after.recycle()
        val output=file("pdf");repo.save(changed,uri(output));val other=file("pdf");output.copyTo(other,true);val reopened=repo.open(uri(other));assertEquals(listOf(0),PdfEngine.search(reopened.base,"ANDROID"))
        try {repo.change(changed,DocumentChange("pdf.ocrPages","0",mapOf("base" to changed.base.name,"pages" to OcrEngine.json(listOf(OcrPage(0,lines))))));fail("Duplicate OCR should fail")} catch(e: IllegalArgumentException) {assertTrue(e.message!!.contains("이미"))}
    }
    @Test fun koreanInvisibleLayerHonorsCropRotationAndRejectsInvalidRequests() {
        val font=file("ttf");app.assets.open("fonts/NotoSansKR.ttf").use {input->font.outputStream().use {input.copyTo(it)}}
        for(rotation in listOf(0,90,180,270)) {
            val output=file("pdf");PDDocument().use {doc->val page=PDPage(PDRectangle(420f,320f));page.cropBox=PDRectangle(20f,30f,350f,240f);page.rotation=rotation;doc.addPage(page)
                PdfOcrText.add(doc,org.json.JSONArray(OcrEngine.json(listOf(OcrPage(0,listOf(OcrLine("한국어 TEST",.2f,.3f,.7f,.4f)))))),font);doc.save(output)}
            assertTrue("rotation $rotation",PdfEngine.text(output).contains("한국어 TEST"))
            val selection=PdfAnnotations.selectable(output,0);assertTrue(selection.text.contains("한국어 TEST"));assertTrue(selection.glyphs.any {it.quad!=null})
        }
        PDDocument().use {doc->doc.addPage(PDPage());assertThrows(IllegalArgumentException::class.java) {PdfOcrText.add(doc,org.json.JSONArray(OcrEngine.json(listOf(OcrPage(0,listOf(OcrLine("bad",.8f,.2f,.1f,.3f)))))),font)}}
    }
}
