package kr.documentstudio

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import kr.documentstudio.core.Format
import kr.documentstudio.data.DocumentRepository
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.regex.Pattern

/** Exercises exported ACTION_VIEW/EDIT, import and native WebView together. */
@RunWith(AndroidJUnit4::class)
class DocumentOpenRegressionTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val device get()=UiDevice.getInstance(instrumentation)
    private val probe get()=Uri.parse("content://"+context.packageName+".hangulprobe")
    private val output get()=File(context.getExternalFilesDir(null),"native-document-open").apply {mkdirs()}
    private fun await(label: String,test: ()->Boolean) {
        val deadline=SystemClock.uptimeMillis()+120000
        while(SystemClock.uptimeMillis()<deadline) {
            if(runCatching(test).getOrDefault(false)) return
            SystemClock.sleep(150)
        }
        device.dumpWindowHierarchy(File(output,"failure.xml"))
        device.takeScreenshot(File(output,"failure.png"))
        fail(label)
    }
    // The private engine intentionally exits on close. A stable provider reference
    // makes Android kill its dependent test/host process too when that happens.
    private fun probeCall(method: String,argument: String?=null) =
        context.contentResolver.acquireUnstableContentProviderClient(probe)?.use {client->client.call(method,argument,null)}
    private fun eval(script: String): String {
        val result=requireNotNull(probeCall("eval",script))
        require(result.getBoolean("ready")) {"Native Hangul Activity is not ready"}
        return requireNotNull(result.getString("result"))
    }
    private fun open(source: File,edit: Boolean=false): Uri {
        device.wakeUp();device.executeShellCommand("am force-stop com.android.launcher3")
        val uri=Uri.parse("content://"+context.packageName+".openfixtures/"+source.name)
        context.startActivity(Intent(if(edit) Intent.ACTION_EDIT else Intent.ACTION_VIEW)
            .setClass(context,MainActivity::class.java).setDataAndType(uri,"application/octet-stream")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        return uri
    }
    private fun hostState(name: String,state: String): Boolean =
        eval("JSON.parse(window.DocumentStudio.describe()).name==="+JSONObject.quote(name)+" && document.body.dataset.state==="+JSONObject.quote(state)+" && !document.getElementById('mode').disabled")=="true"
    private fun button(label: String): UiObject2 {
        var control: UiObject2?=null
        await("Visible enabled button: $label") {
            control=device.findObject(By.pkg(context.packageName).text(label)) ?: device.findObject(By.pkg(context.packageName).desc(label))
            control?.isEnabled==true
        }
        return requireNotNull(control)
    }
    private fun diagnostics(label: String,error: Throwable) {
        runCatching {device.dumpWindowHierarchy(File(output,label+"-failure.xml"))}
        runCatching {device.takeScreenshot(File(output,label+"-failure.png"))}
        runCatching {File(output,label+"-failure.txt").writeText(error.stackTraceToString()+"\n"+device.executeShellCommand("dumpsys activity activities"))}
    }
    private fun close() {
        assertEquals("true",eval("setTimeout(()=>window.documentStudioHost.returnToWorkspace(),100);true"))
        assertTrue("Native WebView must close",device.wait(Until.gone(By.clazz("android.webkit.WebView").pkg(context.packageName)),20_000))
        device.waitForIdle(1_000)
    }
    @Test fun contentUrisWithoutNameOrMimeReachActualHwpAndHwpxOriginalView()=runBlocking {
        val repo=DocumentRepository(context)
        for(format in listOf(Format.HWP,Format.HWPX)) {
            val baseline=repo.create(format)
            val source=File(context.cacheDir,"open-fixtures/"+UUID.randomUUID()+"."+format.extension).apply {parentFile!!.mkdirs();baseline.base.copyTo(this)}
            val hash=DocumentRepository.digest(source);val uri=open(source)
            var closed=false
            try {
            await("Metadata-free $format must reach its own real document engine") {hostState(source.name,"READING")}
            button("편집하기").click()
            await("Imported $format must enter explicit edit mode") {hostState(source.name,"EDITING")}
            button("보기로").click()
            await("Mode-only changes must return to original view") {hostState(source.name,"READING")}
            device.takeScreenshot(File(output,"metadata-free-"+format.extension+".png"))
            close();closed=true
            val imported=repo.recents().first {it.uri==uri.toString()}
            val restored=repo.restore(imported.id)
            assertEquals(format,restored.format);assertEquals(hash,DocumentRepository.digest(restored.base))
            assertEquals(hash,DocumentRepository.digest(source));assertEquals(hash,DocumentRepository.digest(baseline.base))
            File(output,"metadata-free-"+format.extension+".json").writeText(JSONObject()
                .put("nativeOriginalView",true).put("externalIntent",true).put("metadataUnavailable",true).put("reopened",true)
                .put("originalSha256",hash).put("readOnlyCloseUnchanged",true).toString())
            } catch(error: Throwable) {diagnostics(format.extension,error);throw error}
            finally {if(!closed) runCatching {probeCall("close")}}
        }
    }
    @Test fun distributionHwpAllowsViewingWhileEditIntentAndNativeWritesRemainDenied()=runBlocking {
        val source=File(context.cacheDir,"open-fixtures/"+UUID.randomUUID()+".hwp").apply {parentFile!!.mkdirs()}
        instrumentation.context.assets.open("distribution.hwp").use {input->source.outputStream().use {input.copyTo(it)}}
        val hash=DocumentRepository.digest(source);val uri=open(source,edit=true)
        var closed=false
        try {
        await("Distributable HWP must open in its own enforced read-only mode") {hostState(source.name,"READ_ONLY")}
        await("Read-only original pages must also be visible on the Android screen") {
            val status=Pattern.compile("^[0-9]+쪽 · 읽기 전용.*")
            device.hasObject(By.pkg(context.packageName).text(status)) || device.hasObject(By.pkg(context.packageName).desc(status))
        }
        assertEquals("false",eval("window.DocumentStudio.setEditing(true)"))
        assertEquals("false",eval("window.DocumentStudio.beginWrite(64)"))
        assertEquals("true",eval("document.getElementById('mode').hidden && document.getElementById('save').hidden"))
        assertTrue(eval("document.getElementById('status').textContent").contains("읽기 전용"))
        assertNull(device.findObject(By.pkg(context.packageName).text("편집하기")));assertNull(device.findObject(By.pkg(context.packageName).desc("편집하기")))
        device.takeScreenshot(File(output,"distribution-read-only.png"))
        close();closed=true
        val repo=DocumentRepository(context);val imported=repo.recents().first {it.uri==uri.toString()}
        val restored=repo.restore(imported.id)
        assertNotNull(restored.readOnlyReason);assertEquals(hash,DocumentRepository.digest(restored.base))
        assertEquals(hash,DocumentRepository.digest(source))
        File(output,"distribution-read-only.json").writeText(JSONObject().put("nativeReadOnlyView",true).put("editIntentDenied",true)
            .put("nativeWriteDenied",true).put("originalSha256",hash).put("reopenedUnchanged",true).toString())
        } catch(error: Throwable) {diagnostics("distribution",error);throw error}
        finally {if(!closed) runCatching {probeCall("close")}}
    }
}
