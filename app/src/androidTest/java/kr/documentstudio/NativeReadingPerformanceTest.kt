package kr.documentstudio
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import kr.documentstudio.core.Format
import kr.documentstudio.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Native process benchmark uses an application Context, independent of Compose ActivityScenario. */
class NativeReadingPerformanceTest {
    private val context: Context=ApplicationProvider.getApplicationContext()
    private val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val output get()=File(context.getExternalFilesDir(null),"reading-performance").apply {mkdirs()}
    private fun timed(block: ()->Unit): Double {val start=SystemClock.elapsedRealtimeNanos();block();return (SystemClock.elapsedRealtimeNanos()-start)/1e6}
    private fun record(name: String,value: JSONObject) {File(output,"$name.json").writeText(value.put("api",android.os.Build.VERSION.SDK_INT).put("model",android.os.Build.MODEL).put("physicalDevice",false).toString(2))}
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
            fun waitFor(timeout: Long,condition: ()->Boolean){val end=SystemClock.uptimeMillis()+timeout;while(SystemClock.uptimeMillis()<end){if(runCatching(condition).getOrDefault(false))return;SystemClock.sleep(50)};device.takeScreenshot(File(output,"timeout.png"));device.dumpWindowHierarchy(File(output,"timeout.xml"));throw AssertionError("Native reader did not become ready: "+format.extension)}
            val start=SystemClock.elapsedRealtimeNanos()
            val intent=if(hangul)Intent(context,HangulEditorActivity::class.java) else Intent().setClassName(context.packageName,"org.libreoffice.androidlib.StudioOfficeActivity")
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("sessionId",s.id).putExtra("workingPath",working.path).putExtra("displayName",s.name))
            var completed=false
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
                repo.acceptOffice(s);completed=true
                records.put(JSONObject().put("format",format.extension).put("prepareMs",prepare).put("engineReadyMs",open).put("idleHeaderMutationsOver2s",mutations).put("headerHeightCssPx",header.getDouble("height")).put("unchangedCloseMs",close).put("documentUnchanged",true))
            } finally {if(!completed)runCatching {device.takeScreenshot(File(output,"failure.png"));device.dumpWindowHierarchy(File(output,"failure.xml"));eval(if(hangul)"documentStudioHost.returnToWorkspace()" else "window.postMobileMessage('BYE')")}}
        }
        record("native-readers",JSONObject().put("samplesPerFormat",1).put("results",records))
    }
}
