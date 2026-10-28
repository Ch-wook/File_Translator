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
