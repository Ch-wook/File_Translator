package kr.documentstudio

import android.content.Intent
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import kr.documentstudio.core.Format
import kr.documentstudio.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Comparative CI benchmark, not a physical phone benchmark or a frame-rate guarantee. */
class ReadingPerformanceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val context get()=compose.activity
    private val device get()=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val vm get()=ViewModelProvider(context)[EditorViewModel::class.java]
    private val output get()=File(context.getExternalFilesDir(null),"reading-performance").apply {mkdirs()}
    private fun timed(block: ()->Unit): Double {val start=SystemClock.elapsedRealtimeNanos();block();return (SystemClock.elapsedRealtimeNanos()-start)/1e6}
    private fun record(name: String,value: JSONObject) {File(output,"$name.json").writeText(value.put("api",android.os.Build.VERSION.SDK_INT).put("model",android.os.Build.MODEL).put("physicalDevice",false).toString(2))}
    private fun uri(file: File)=FileProvider.getUriForFile(context,context.packageName+".testfiles",file)
    private fun file(ext: String)=File(context.cacheDir,"tests/perf-${UUID.randomUUID()}.$ext").apply {parentFile!!.mkdirs();writeBytes(byteArrayOf())}
    private fun ready(){compose.waitUntil(30000){!vm.state.value.busy&&vm.state.value.session!=null};compose.waitForIdle()}
    @Test fun repeatedCheckpointsAndPdfGeometry()=runBlocking {
        val repo=DocumentRepository(context);val text=("한글 문서 성능 측정 ABC 123\n").repeat(5000)
        val source=file("txt").apply {writeText(text)};val sourceHash=DocumentRepository.digest(source)
        lateinit var session: Session;val opens=JSONArray()
        repeat(5){opens.put(timed {session=runBlocking {repo.open(uri(source))}})}
        val state=File(session.base.parentFile,"state.json");val checkpoints=JSONArray();var writes=0
        repeat(20){assertTrue(state.setLastModified(1000));checkpoints.put(timed {runBlocking {repo.checkpoint(session)}});if(state.lastModified()!=1000L)writes++}
        val unchangedSaves=JSONArray();repeat(5){unchangedSaves.put(timed {runBlocking {repo.save(session,uri(source))}})}
        assertEquals(sourceHash,DocumentRepository.digest(source))
        val pdf=file("pdf")
        com.tom_roush.pdfbox.pdmodel.PDDocument().use {doc->repeat(80){doc.addPage(com.tom_roush.pdfbox.pdmodel.PDPage())};doc.save(pdf)}
        val geometry=JSONArray()
        repeat(20){geometry.put(timed {assertTrue(PdfEngine.displaySize(pdf,it*3).first>0)})}
        val renders=JSONArray();repeat(3){renders.put(timed {PdfEngine.render(pdf,it,annotationPages=emptySet()).recycle()})}
        val memory=Debug.MemoryInfo().also {Debug.getMemoryInfo(it)}
        record("repository",JSONObject().put("textBytes",source.length()).put("pdfPages",80).put("openMs",opens).put("checkpointMs",checkpoints).put("checkpointStateWrites",writes)
            .put("unchangedOriginalSaveMs",unchangedSaves).put("pdfGeometryMs",geometry).put("pdfPageRenderMs",renders).put("processPssKiB",memory.totalPss).put("originalUnchanged",true))
    }
    @Test fun nativeReadingIdleAndClose()=runBlocking {
        device.wakeUp();device.executeShellCommand("am force-stop com.android.launcher3")
        val repo=DocumentRepository(context);val records=JSONArray()
        for(format in listOf(Format.DOCX,Format.XLSX,Format.PPTX,Format.HWPX)){
            val s=repo.create(format);lateinit var working: File
            val prepare=timed {working=runBlocking {repo.prepareOffice(s)}}
            val hash=DocumentRepository.digest(working);val hangul=format==Format.HWPX
            val authority=if(hangul)"hangulprobe" else "officeprobe"
            fun eval(js: String): String {
                val raw=context.contentResolver.acquireUnstableContentProviderClient(Uri.parse("content://${context.packageName}.$authority")).use {client->checkNotNull(client).call("eval",js,null)!!.getString("result")!!}
                return JSONTokener(raw).nextValue().toString()
            }
            fun waitFor(timeout: Long,condition: ()->Boolean){val end=SystemClock.uptimeMillis()+timeout;while(SystemClock.uptimeMillis()<end){if(runCatching(condition).getOrDefault(false))return;SystemClock.sleep(50)};assertTrue(condition())}
            val start=SystemClock.elapsedRealtimeNanos()
            val intent=if(hangul)Intent(context,HangulEditorActivity::class.java) else Intent().setClassName(context.packageName,"org.libreoffice.androidlib.StudioOfficeActivity")
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("sessionId",s.id).putExtra("workingPath",working.path).putExtra("displayName",s.name))
            try{
                waitFor(150000){eval(if(hangul)"document.body.dataset.state==='READING'&&!document.getElementById('mode').disabled" else "studioOffice.ready&&!document.getElementById('studio-office-edit').disabled")=="true"}
                val open=(SystemClock.elapsedRealtimeNanos()-start)/1e6
                eval("(function(){window.__readingMutations=0;window.__readingObserver=new MutationObserver(r=>window.__readingMutations+=r.length);window.__readingObserver.observe(document.querySelector('header'),{subtree:true,attributes:true,childList:true,characterData:true});})()")
                SystemClock.sleep(2000)
                val mutations=eval("window.__readingMutations").toInt();eval("window.__readingObserver.disconnect()")
                val header=JSONObject(eval("JSON.stringify(document.querySelector('header').getBoundingClientRect().toJSON())"))
                val memory=device.executeShellCommand("dumpsys meminfo ${context.packageName}:${if(hangul)"hangul" else "office"}")
                File(output,"${format.extension}-memory.txt").writeText(memory)
                device.takeScreenshot(File(output,"${format.extension}-view.png"))
                val close=timed {
                    eval(if(hangul)"documentStudioHost.returnToWorkspace()" else "window.postMobileMessage('BYE')")
                    waitFor(20000){device.executeShellCommand("pidof ${context.packageName}:${if(hangul)"hangul" else "office"}").trim().isEmpty()}
                    if(!hangul)runBlocking {assertFalse(repo.completeOfficeClose(s))}
                }
                assertEquals(hash,DocumentRepository.digest(working));assertFalse(File(working.path+".edited").exists())
                repo.acceptOffice(s)
                records.put(JSONObject().put("format",format.extension).put("prepareMs",prepare).put("engineReadyMs",open).put("idleHeaderMutationsOver2s",mutations).put("headerHeightCssPx",header.getDouble("height")).put("unchangedCloseMs",close).put("documentUnchanged",true))
            } finally {runCatching {eval(if(hangul)"documentStudioHost.returnToWorkspace()" else "window.postMobileMessage('BYE')")}}
        }
        record("native-readers",JSONObject().put("samplesPerFormat",1).put("results",records))
    }
    @Test fun textControlsAndRecoveryAfterSaveFailure(){
        compose.runOnUiThread {vm.create(Format.TXT)};ready()
        val modes=JSONArray();val menus=JSONArray()
        repeat(5){
            modes.put(timed {compose.onNodeWithTag("edit-document").performClick();compose.waitForIdle()})
            menus.put(timed {compose.onNodeWithText("더보기").performClick();compose.waitForIdle();compose.onNodeWithTag("mobile-editor-sheet").assertExists()})
            compose.onNodeWithContentDescription("설정 닫기").performClick()
            compose.runOnUiThread {vm.readDocument()};compose.waitForIdle()
        }
        compose.onNodeWithTag("edit-document").performClick();compose.waitForIdle()
        compose.runOnUiThread {vm.text("저장 실패에도 유지되는 최신 한글")}
        compose.waitForIdle()
        val id=vm.state.value.session!!.id
        compose.runOnUiThread {vm.save(Uri.parse("content://invalid.document.studio/test"))}
        compose.waitUntil(30000){!vm.state.value.busy};compose.waitForIdle()
        assertEquals("저장 실패에도 유지되는 최신 한글",vm.state.value.session!!.edits.text)
        assertEquals("저장 실패에도 유지되는 최신 한글",runBlocking {vm.repository.restore(id).edits.text})
        val target=file("txt");compose.runOnUiThread {vm.save(uri(target))};ready()
        assertEquals("저장 실패에도 유지되는 최신 한글",target.readText())
        record("text-ui",JSONObject().put("modeDriverRoundTripMs",modes).put("menuDriverRoundTripMs",menus).put("saveFailureRecoveryAndRetry",true).put("driverIncludesComposeIdling",true))
    }
}
