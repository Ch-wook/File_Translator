package kr.documentstudio

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
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
    private fun eval(script: String): String {
        val result=requireNotNull(context.contentResolver.call(probe,"eval",script,null))
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
    private fun close() {
        assertEquals("true",eval("(window.documentStudioHost.returnToWorkspace(),true)"))
        await("Native editor must close") {context.contentResolver.call(probe,"eval","true",null)?.getBoolean("ready")!=true}
    }
    @Test fun contentUrisWithoutNameOrMimeReachActualHwpAndHwpxOriginalView()=runBlocking {
        val repo=DocumentRepository(context)
        for(format in listOf(Format.HWP,Format.HWPX)) {
            val baseline=repo.create(format)
            val source=File(context.cacheDir,"open-fixtures/"+UUID.randomUUID()+"."+format.extension).apply {parentFile!!.mkdirs();baseline.base.copyTo(this)}
            val hash=DocumentRepository.digest(source);val uri=open(source)
            await("Metadata-free $format must reach the real document engine") {eval("document.body.dataset.state")=="\"READING\""}
            val editButton=requireNotNull(device.findObject(By.text("편집하기")) ?: device.findObject(By.desc("편집하기")))
            editButton.click()
            await("Imported $format must enter explicit edit mode") {eval("document.body.dataset.state")=="\"EDITING\""}
            val readButton=requireNotNull(device.findObject(By.text("보기로")) ?: device.findObject(By.desc("보기로")))
            readButton.click()
            await("Mode-only changes must return to original view") {eval("document.body.dataset.state")=="\"READING\""}
            device.takeScreenshot(File(output,"metadata-free-"+format.extension+".png"))
            close()
            val imported=repo.recents().first {it.uri==uri.toString()}
            val restored=repo.restore(imported.id)
            assertEquals(format,restored.format);assertEquals(hash,DocumentRepository.digest(restored.base))
            assertEquals(hash,DocumentRepository.digest(source));assertEquals(hash,DocumentRepository.digest(baseline.base))
            File(output,"metadata-free-"+format.extension+".json").writeText(JSONObject()
                .put("nativeOriginalView",true).put("externalIntent",true).put("metadataUnavailable",true).put("reopened",true)
                .put("originalSha256",hash).put("readOnlyCloseUnchanged",true).toString())
        }
    }
    @Test fun distributionHwpAllowsViewingWhileEditIntentAndNativeWritesRemainDenied()=runBlocking {
        val source=File(context.cacheDir,"open-fixtures/"+UUID.randomUUID()+".hwp").apply {parentFile!!.mkdirs()}
        instrumentation.context.assets.open("distribution.hwp").use {input->source.outputStream().use {input.copyTo(it)}}
        val hash=DocumentRepository.digest(source);val uri=open(source,edit=true)
        await("Distributable HWP must open in enforced read-only mode") {eval("document.body.dataset.state")=="\"READ_ONLY\""}
        assertEquals("false",eval("window.DocumentStudio.setEditing(true)"))
        assertEquals("false",eval("window.DocumentStudio.beginWrite(64)"))
        assertEquals("true",eval("document.getElementById('mode').hidden && document.getElementById('save').hidden"))
        assertTrue(eval("document.getElementById('status').textContent").contains("읽기 전용"))
        assertNull(device.findObject(By.text("편집하기")));assertNull(device.findObject(By.desc("편집하기")))
        device.takeScreenshot(File(output,"distribution-read-only.png"))
        close()
        val repo=DocumentRepository(context);val imported=repo.recents().first {it.uri==uri.toString()}
        val restored=repo.restore(imported.id)
        assertNotNull(restored.readOnlyReason);assertEquals(hash,DocumentRepository.digest(restored.base))
        assertEquals(hash,DocumentRepository.digest(source))
        File(output,"distribution-read-only.json").writeText(JSONObject().put("nativeReadOnlyView",true).put("editIntentDenied",true)
            .put("nativeWriteDenied",true).put("originalSha256",hash).put("reopenedUnchanged",true).toString())
    }
}
