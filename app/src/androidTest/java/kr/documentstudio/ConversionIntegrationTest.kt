package kr.documentstudio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.runBlocking
import kr.documentstudio.core.Format
import kr.documentstudio.data.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ConversionIntegrationTest {
    private val context: Context=ApplicationProvider.getApplicationContext()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val device=UiDevice.getInstance(instrumentation)
    private val output get()=File(context.getExternalFilesDir(null),"native-conversion").apply {mkdirs()}
    private fun await(label: String,timeout: Long=60000,condition: ()->Boolean) {
        val end=SystemClock.uptimeMillis()+timeout
        while(SystemClock.uptimeMillis()<end){NativeUiActions.refresh();if(runCatching(condition).getOrDefault(false))return;SystemClock.sleep(200)}
        val stem=label.lowercase().replace(Regex("[^a-z0-9]+"),"-").take(96)
        runCatching {device.takeScreenshot(File(output,stem+"-failure.png"))}
        runCatching {device.dumpWindowHierarchy(File(output,stem+"-failure.xml"))}
        val pid=device.executeShellCommand("pidof "+context.packageName+":office").trim()
        val state=JSONObject().put("label",label).put("officePid",pid)
        if(pid.isNotBlank())runCatching {
            state.put("native",probe("state").toString())
            state.put("web",probe("eval","JSON.stringify({ready:window.studioOffice?.ready,loaded:window.app?.map?._docLoaded,family:window.app?.map?.getDocType?.(),header:!!document.getElementById('studio-office-header'),buttons:[...document.querySelectorAll('#studio-office-header button')].map(e=>({id:e.id,hidden:e.hidden,rect:e.getBoundingClientRect().toJSON()})),policy:window.COOLMessageHandler?.getStudioViewState?.(),focus:document.hasFocus()})")?.getString("result"))
        }
        File(output,stem+"-failure.json").writeText(state.toString(2));error(label)
    }
    private fun closeOffice() {
        // Acquiring a provider after its Activity died starts an empty new process.
        if(device.executeShellCommand("pidof "+context.packageName+":office").trim().isNotEmpty())runCatching {probe("close")}
        await("Office process must exit after conversion",30000){device.executeShellCommand("pidof "+context.packageName+":office").trim().isEmpty()}
    }
    private fun probe(method: String,arg: String?=null)=context.contentResolver.acquireUnstableContentProviderClient(Uri.parse("content://"+context.packageName+".officeprobe")).use {requireNotNull(it).call(method,arg,null)}
    private fun uri(file: File)=FileProvider.getUriForFile(context,context.packageName+if(file.canonicalPath.startsWith(File(context.cacheDir,"conversions").canonicalPath+"/"))".conversionfiles" else ".testfiles",file)
    @Test fun failedNativePdfWriteRetainsPreviousAndLatestPdfAndAllowsSafeSaveAs()=runBlocking {
        device.wakeUp();device.executeShellCommand("am force-stop com.android.launcher3")
        val repo=DocumentRepository(context);val writes=ProviderWrites(context)
        val directory=File(context.cacheDir,"tests/office-publication-fault").apply {mkdirs()}
        val original=File(directory,"source.docx")
        instrumentation.context.assets.open("conversion/layout.docx").use {input->original.outputStream().use {input.copyTo(it)}}
        val sourceHash=DocumentRepository.digest(original);val source=repo.open(uri(original));val working=repo.prepareOffice(source)
        val target=File(context.cacheDir,"tests/failing-provider.txt").apply {parentFile!!.mkdirs()}
        ExportEngine.blank(target);val previousHash=DocumentRepository.digest(target)
        val destination=Uri.parse("content://"+context.packageName+".faults/output.pdf")
        var recoveryId: String?=null
        var primaryFailure: Throwable?=null
        context.startActivity(Intent().setClassName(context.packageName,"org.libreoffice.androidlib.StudioOfficeActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("sessionId",source.id).putExtra("workingPath",working.absolutePath).putExtra("displayName",source.name).putExtra("originalUri",uri(original).toString()))
        try {
            await("Native Word source must load",120000){probe("state")?.getBoolean("ready")==true}
            FailingProvider.remainingFailures=2
            probe("convert-pdf",destination.toString())
            await("Failed native PDF must retain its durable recovery",60000){
                val state=probe("pdf-state");state?.getBoolean("busy")==false && !state.getString("error").isNullOrBlank()
            }
            val recovery=writes.list().single {it.uri==destination.toString()}
            recoveryId=recovery.id
            assertEquals(previousHash,DocumentRepository.digest(writes.snapshot(recovery.id,true)))
            assertEquals(2,PdfEngine.inspect(writes.snapshot(recovery.id,false)).pages)
            assertNull(device.findObject(By.text("PDF 변환 완료")))
            val partialHash=DocumentRepository.digest(target)
            FailingProvider.remainingFailures=0;probe("convert-pdf",destination.toString())
            await("An unsafe retry must be blocked",60000){val state=probe("pdf-state");state?.getBoolean("busy")==false && !state.getString("error").isNullOrBlank()}
            assertEquals(partialHash,DocumentRepository.digest(target))
            assertEquals(previousHash,DocumentRepository.digest(writes.snapshot(recovery.id,true)))
            val safe=File(directory,"safe-result.pdf").apply {writeBytes(byteArrayOf())}
            probe("convert-pdf",uri(safe).toString())
            await("A separate PDF must be saved successfully",60000){device.findObject(By.text("PDF 변환 완료"))!=null}
            assertEquals(2,PdfEngine.inspect(safe).pages);assertEquals(sourceHash,DocumentRepository.digest(original))
            NativeUiActions.tap(device,context.packageName,"닫기")
            File(output,"office-publish-recovery.json").writeText(JSONObject().put("previousPdfPreserved",true).put("latestPdfRetained",true)
                .put("unsafeRetryBlocked",true).put("saveAsRetryVerified",true).put("originalUnchanged",true).put("commonHostBridge",true).toString(2))
        } catch(failure: Throwable) {primaryFailure=failure;throw failure}
        finally {
            FailingProvider.remainingFailures=0
            recoveryId?.let {runCatching {writes.discard(it)}}
            try {closeOffice()} catch(cleanup: Throwable) {if(primaryFailure!=null)primaryFailure.addSuppressed(cleanup) else throw cleanup}
            runCatching {repo.acceptOffice(source)}
        }
    }
    @Test fun pptxTenSlidesToPdf(){nativePdf("pptx",10,listOf("TEST_SLIDE_01","TEST_SLIDE_10","TABLE"))}
    @Test fun docxPagesToPdf(){nativePdf("docx",2,listOf("DOCX_PAGE_ONE","DOCX_PAGE_TWO","HEADER_TEST","FOOTER_TEST","TABLE_TEST"))}
    @Test fun xlsxSheetsToPdf(){nativePdf("xlsx",2,listOf("XLSX_SHEET_ONE","XLSX_SHEET_TWO","42"))}
    private fun nativePdf(ext: String,pages: Int,tokens: List<String>)=runBlocking {
        device.wakeUp();device.executeShellCommand("am force-stop com.android.launcher3")
        val dir=File(context.cacheDir,"tests/conversion-"+ext).apply {mkdirs()}
        val original=File(dir,"layout."+ext);instrumentation.context.assets.open("conversion/layout."+ext).use {input->original.outputStream().use {input.copyTo(it)}}
        val hash=ConversionFiles.digest(original);val repo=DocumentRepository(context);val source=repo.open(uri(original),"UTF-8");val working=repo.prepareOffice(source)
        context.startActivity(Intent().setClassName(context.packageName,"org.libreoffice.androidlib.StudioOfficeActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("sessionId",source.id).putExtra("workingPath",working.absolutePath).putExtra("displayName",source.name).putExtra("originalUri",uri(original).toString()))
        var completed=false
        try {
            await("Actual native document must load",120000){probe("state")?.getBoolean("ready")==true}
            val geometry=probe("geometry")!!
            assertTrue("Office header must clear the system status bar",geometry.getInt("top")>=geometry.getInt("barTop"))
            assertTrue("Office content must clear display cutouts",geometry.getInt("left")>=geometry.getInt("barLeft"))
            await("Mobile PDF conversion menu must exist"){device.findObject(By.desc("문서 메뉴"))!=null || device.findObject(By.text("문서 메뉴"))!=null}
            assertEquals("Every visible Office header button needs a 48 CSS pixel target","true",probe("eval","[...document.querySelectorAll('#studio-office-header > button')].filter(e=>!e.hidden).every(e=>{const r=e.getBoundingClientRect();return r.width>=47.9&&r.height>=47.9})")?.getString("result"))
            NativeUiActions.tap(device,context.packageName,"문서 메뉴")
            await("PDF conversion action must be visible",10000){device.findObject(By.text("PDF로 변환"))!=null || device.findObject(By.desc("PDF로 변환"))!=null}
            val pdf=File(dir,"result.pdf").apply {writeBytes(byteArrayOf())};probe("convert-pdf",uri(pdf).toString())
            var exportError: String?=null
            await("Native filter must save and read back PDF",60000){exportError=probe("pdf-state")?.getString("error");device.findObject(By.text("PDF 변환 완료"))!=null || !exportError.isNullOrBlank()}
            assertTrue("Native PDF filter failure: "+exportError,exportError.isNullOrBlank())
            val info=PdfEngine.inspect(pdf);assertEquals(pages,info.pages)
            PDDocument.load(pdf).use {doc->val text=PDFTextStripper().getText(doc);for(token in tokens)assertTrue("PDF must preserve "+token,text.contains(token))
                if(ext=="pptx"){var previous=-1;for(i in 1..10){val next=text.indexOf("TEST_SLIDE_%02d".format(i));assertTrue(next>previous);previous=next};val box=doc.getPage(0).mediaBox;assertEquals(16.0/9.0,(box.width/box.height).toDouble(),.02)}
            }
            val bitmap=PdfEngine.render(pdf,0,600,600);try{assertTrue(bitmap.width>0);File(output,ext+"-page1.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}
            assertEquals(hash,ConversionFiles.digest(original))
            File(output,ext+".pdf").writeBytes(pdf.readBytes())
            NativeUiActions.tap(device,context.packageName,"닫기")
            var latestEditVerified=false
            if(ext=="pptx"){
                probe("eval","app.map._switchToEditMode()")
                await("PPTX editing must be explicitly enabled"){probe("state")?.getBoolean("editing")==true}
                probe("uno",".uno:InsertPage")
                await("Native slide edit must be applied"){org.json.JSONTokener(probe("eval","app.map.getNumberOfParts()")!!.getString("result")!!).nextValue().toString()=="11"}
                val editedPdf=File(dir,"edited-result.pdf").apply {writeBytes(byteArrayOf())}
                probe("convert-pdf",uri(editedPdf).toString());exportError=null
                await("PDF must use the latest native edit",60000){exportError=probe("pdf-state")?.getString("error");device.findObject(By.text("PDF 변환 완료"))!=null || !exportError.isNullOrBlank()}
                assertTrue("Edited PDF filter failure: "+exportError,exportError.isNullOrBlank())
                assertEquals(11,PdfEngine.inspect(editedPdf).pages)
                assertEquals(hash,ConversionFiles.digest(original))
                File(output,"pptx-edited.pdf").writeBytes(editedPdf.readBytes())
                NativeUiActions.tap(device,context.packageName,"닫기");latestEditVerified=true
            }
            File(output,ext+".json").writeText(JSONObject().put("pages",pages).put("tokensVerified",true).put("originalUnchanged",true).put("nativeFilter",true).apply {if(ext=="pptx")put("latestEditVerified",latestEditVerified)}.toString(2))
            completed=true
        }finally{
            runCatching {probe("close")}
            val deadline=SystemClock.uptimeMillis()+10000
            while(SystemClock.uptimeMillis()<deadline && device.executeShellCommand("pidof "+context.packageName+":office").trim().isNotEmpty())SystemClock.sleep(100)
            if(device.executeShellCommand("pidof "+context.packageName+":office").trim().isNotEmpty()){device.executeShellCommand("am kill "+context.packageName);if(completed)error("Successful PDF host must close cleanly")}
            runCatching {repo.acceptOffice(source)}
        }
    }
    @Test fun hwpxSignatureAndLayoutPdf()=runBlocking {
        val repo=DocumentRepository(context);val s=repo.create(Format.HWPX);val working=repo.prepareOffice(s);val original=ConversionFiles.digest(s.base)
        val provider=Uri.parse("content://"+context.packageName+".hangulprobe")
        fun eval(script: String): String?=context.contentResolver.call(provider,"eval",script,null)?.getString("result")
        context.startActivity(Intent(context,HangulEditorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("sessionId",s.id).putExtra("workingPath",working.absolutePath).putExtra("displayName",s.name).putExtra("openIntent","EDIT"))
        try {
            await("HWPX editor must load",120000){eval("document.body.dataset.state")?.contains("EDITING")==true}
            eval("window.documentStudioHost.signature()")
            var area=android.graphics.Rect()
            await("Signature panel visible"){
                val raw=eval("JSON.stringify((function(){const e=document.querySelector('.studio-signature canvas');if(!e)return {};const r=e.getBoundingClientRect();return {x:r.left,y:r.top,w:r.width,h:r.height,vw:innerWidth};})())")
                val first=org.json.JSONTokener(raw ?: "null").nextValue()
                val json=if(first is String)JSONObject(first) else JSONObject()
                val geo=context.contentResolver.call(provider,"geometry",null,null)!!
                if(json.optDouble("w")>0){val scale=geo.getInt("width")/json.getDouble("vw");area=android.graphics.Rect(geo.getInt("left")+(json.getDouble("x")*scale).toInt(),geo.getInt("top")+(json.getDouble("y")*scale).toInt(),geo.getInt("left")+((json.getDouble("x")+json.getDouble("w"))*scale).toInt(),geo.getInt("top")+((json.getDouble("y")+json.getDouble("h"))*scale).toInt());true}else false
            }
            device.swipe(area.left+30,area.centerY(),area.right-30,area.centerY()-40,24)
            NativeUiActions.tap(device,context.packageName,"파랑")
            device.swipe(area.left+60,area.centerY()-25,area.right-60,area.centerY()+40,24)
            NativeUiActions.tap(device,context.packageName,"완료")
            await("Native picture placement must be active"){eval("document.getElementById('engine').contentWindow.rhwpStudio.documentStudio.mobile.state().placement")=="true"}
            val web=device.findObject(By.clazz("android.webkit.WebView")).visibleBounds
            device.swipe(web.centerX()-80,web.top+150,web.centerX()+80,web.top+220,24)
            await("Signature must become native document picture"){eval("document.getElementById('engine').contentWindow.rhwpStudio.documentStudio.mobile.objects().some(o=>o.type==='image')")=="true"}
            eval("window.documentStudioHost.checkpoint()")
            await("Signed HWPX must be durably saved"){java.util.zip.ZipFile(working).use {z->z.entries().asSequence().any {it.name.startsWith("BinData/") && it.name.endsWith(".png")}}}
            eval("(async()=>{const e=document.getElementById('engine').contentWindow.rhwpStudio.documentStudio;const r=await fetch('/document');await e.open(new Uint8Array(await r.arrayBuffer()),'signed.hwpx');await e.setEditing(true);window.__signatureReopened=e.mobile.objects().some(o=>o.type==='image');})()")
            await("Persisted signed HWPX must reopen in the real engine"){eval("window.__signatureReopened")=="true"}
            await("Signature checkpoint must finish"){eval("document.getElementById('save').disabled")=="false"}
            val pdf=File(context.cacheDir,"tests/signed-hwpx.pdf").apply {parentFile!!.mkdirs();writeBytes(byteArrayOf())}
            context.contentResolver.call(provider,"pdf-target",uri(pdf).toString(),null)
            eval("window.documentStudioHost.writePdf()")
            var failureStatus=""
            await("Native PDF bridge must save signed page",60000){
                val raw=eval("JSON.stringify({text:document.getElementById('status').textContent,error:document.getElementById('status').dataset.error})")
                val decoded=org.json.JSONTokener(raw ?: "null").nextValue()
                val state=if(decoded is String)JSONObject(decoded) else JSONObject()
                if(state.optString("error")=="true")failureStatus=state.optString("text")
                device.findObject(By.text("PDF 변환 완료"))!=null || failureStatus.isNotBlank()
            }
            assertTrue("HWPX PDF bridge failure: "+failureStatus,failureStatus.isBlank())
            assertTrue(PdfEngine.inspect(pdf).pages>=1);assertEquals(original,ConversionFiles.digest(s.base))
            val bitmap=PdfEngine.render(pdf,0,600,800)
            try{
                var blue=0;for(y in 0 until bitmap.height)for(x in 0 until bitmap.width){val pixel=bitmap.getPixel(x,y);if(android.graphics.Color.blue(pixel)>android.graphics.Color.red(pixel)+60 && android.graphics.Color.blue(pixel)>100)blue++}
                assertTrue("Signed PDF must visibly contain the blue signature strokes",blue>10)
                File(output,"signed-hwpx.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}
            File(output,"signed.hwpx").writeBytes(working.readBytes());File(output,"signed.pdf").writeBytes(pdf.readBytes())
            File(output,"signature.json").writeText(JSONObject().put("nativeTouchSignature",true).put("embeddedPng",true).put("nativePdfSaved",true).put("engineReopenedBeforePdf",true).put("originalUnchanged",true).toString(2))
        }finally {runCatching {context.contentResolver.call(provider,"close",null,null)};await("Hangul process must exit",30000){device.executeShellCommand("pidof "+context.packageName+":hangul").trim().isEmpty()}}
    }
    @Test fun textMarkdownAndPdfConversions()=runBlocking {
        val repo=DocumentRepository(context);var txt=repo.create(Format.TXT);txt=txt.copy(edits=txt.edits.copy(text=("한국어 문장 English 123\n").repeat(120)))
        val hash=ConversionFiles.digest(txt.base);val pdf=PortableConversions.convert(context,txt,"pdf");assertTrue(PdfEngine.inspect(pdf).pages>1)
        val pdfSession=repo.open(uri(pdf),"UTF-8")
        val png=PortableConversions.convert(context,pdfSession,"png.zip",72,"1-2");java.util.zip.ZipFile(png).use {assertEquals(2,it.size())}
        val jpg=PortableConversions.convert(context,pdfSession,"jpg.zip",144,"1");java.util.zip.ZipFile(jpg).use {assertEquals(1,it.size())}
        assertEquals(listOf(0,2,3),PortableConversions.pages("1,3-4",4))
        val scan=File(context.cacheDir,"tests/text-source.pdf").apply {parentFile!!.mkdirs()}
        PDDocument().use {doc->val page=PDPage();doc.addPage(page);PDPageContentStream(doc,page).use {cs->cs.beginText();cs.setFont(PDType1Font.HELVETICA,14f);cs.newLineAtOffset(30f,700f);cs.showText("PDF_TEXT_TEST");cs.endText()};doc.save(scan)}
        val extracted=PortableConversions.convert(context,repo.open(uri(scan),"UTF-8"),"txt");assertTrue(extracted.readText().contains("PDF_TEXT_TEST"))
        var md=repo.create(Format.MD);md=md.copy(edits=md.edits.copy(text="# 제목\n\n**굵게**\n\n| A | B |\n|---|---|\n| 1 | 2 |\n"))
        assertTrue(PortableConversions.convert(context,md,"html").readText().contains("<table>"));assertTrue(PdfEngine.inspect(PortableConversions.convert(context,md,"pdf")).pages>=1)
        assertTrue(PortableConversions.convert(context,md,"txt").readText().contains("굵게"));assertEquals(hash,ConversionFiles.digest(txt.base))
        context.startActivity(Intent(context,ConversionPreviewActivity::class.java).setData(uri(pdf)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await("Converted PDF preview must render independently"){device.findObject(By.desc("연속 보기 1페이지"))!=null}
        assertNull(device.findObject(By.text("현재 페이지 필기")))
        NativeUiActions.tap(device,context.packageName,"닫기")
        File(output,"portable.json").writeText(JSONObject().put("txtPdf",true).put("mdPdfHtmlTxt",true).put("pdfPngJpgTxt",true).put("originalUnchanged",true).toString(2))
    }
}
