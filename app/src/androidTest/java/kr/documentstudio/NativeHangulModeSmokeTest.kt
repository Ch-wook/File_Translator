package kr.documentstudio

import android.content.Intent
import android.os.SystemClock
import android.util.AtomicFile
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
import java.security.MessageDigest

/**
 * Exercises the actual separate-process Android System WebView host through its
 * accessible UI. This smoke checks modes, zoom metadata and document-file writes.
 * It does not establish Korean IME composition, editing gestures or in-memory
 * mutation safety; browser/bridge tests cover different contracts.
 */
@RunWith(AndroidJUnit4::class)
class NativeHangulModeSmokeTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val device get()=UiDevice.getInstance(instrumentation)

    @Test fun hwpOpensInViewAndModeOnlyRoundTripDoesNotWriteDocument()=exercise(Format.HWP)
    @Test fun hwpxOpensInViewAndModeOnlyRoundTripDoesNotWriteDocument()=exercise(Format.HWPX)

    private fun exercise(format: Format)=runBlocking {
        val repository=DocumentRepository(context)
        val session=repository.create(format)
        val working=repository.prepareOffice(session)
        val originalHash=hash(session.base)
        val workingHash=hash(working)
        val edited=File(working.path+".edited")
        val closed=File(working.path+".closed")
        val viewFile=File(working.parentFile,"hangul-view-state.json")
        // Deliberately omit openIntent: the real Activity must default to VIEW.
        val launch=Intent(context,HangulEditorActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("sessionId",session.id)
            .putExtra("workingPath",working.absolutePath)
            .putExtra("displayName","Android 원본 보기.${format.extension}")
        context.startActivity(launch)
        try {
            await("The real engine must report loaded original view",120_000) {
                device.hasObject(By.textContains("원본 보기")) || device.hasObject(By.descContains("원본 보기"))
            }
            button("편집하기")
            assertNull("A save control is hidden in VIEW",device.findObject(By.text("저장 후 닫기")))
            assertUnchanged(working,workingHash,session.base,originalHash,edited)

            // Normalize away from the upper zoom bound using visible VIEW tools.
            zoom(false);zoom(false)
            button("편집하기").click()
            button("보기로")
            val lowerZoom=readView(viewFile)
            assertValidView(lowerZoom)
            assertUnchanged(working,workingHash,session.base,originalHash,edited)
            button("보기로").click()
            button("편집하기")
            assertSameView(lowerZoom,readView(viewFile))

            zoom(true);zoom(true)
            button("편집하기").click()
            button("보기로")
            val higherZoom=readView(viewFile)
            assertValidView(higherZoom)
            assertTrue("The real WebView zoom command must update engine view metadata",
                higherZoom.getDouble("zoom")>lowerZoom.getDouble("zoom")+0.0001)
            button("보기로").click()
            button("편집하기")
            assertSameView(higherZoom,readView(viewFile))
            assertUnchanged(working,workingHash,session.base,originalHash,edited)
            screenshot(format,"view-after-mode-roundtrip")

            // The actual Android Back callback closes a reading session.
            device.pressBack()
            await("Read-only native close marker",20_000) {closed.isFile}
            assertUnchanged(working,workingHash,session.base,originalHash,edited)
            assertTrue("The private WebView host must close",
                device.wait(Until.gone(By.clazz("android.webkit.WebView").pkg(context.packageName)),20_000))
        } finally {
            runCatching {
                if(!closed.isFile) {
                    screenshot(format,"failure")
                    // First Back may leave EDIT; the second closes VIEW.
                    device.pressBack()
                    if(!closed.isFile) device.pressBack()
                    await("Private host cleanup",20_000) {closed.isFile}
                }
                // Let the finishing private :hangul process release its profile/lock.
                device.waitForIdle(2_000)
            }
        }
    }

    private fun zoom(increase: Boolean) {
        val text=if(increase) "＋" else "−"
        val description=if(increase) "확대" else "축소"
        var control: UiObject2?=null
        await("Visible enabled zoom control: $description",10_000) {
            control=device.findObject(By.text(text))
                ?: device.findObject(By.desc(description))
                ?: device.findObject(By.text(description))
            runCatching {control?.isEnabled==true}.getOrDefault(false)
        }
        requireNotNull(control).click()
        device.waitForIdle(1_000)
    }

    private fun button(text: String): UiObject2 {
        await("Enabled host button: $text",20_000) {
            runCatching {device.findObject(By.text(text))?.isEnabled==true}.getOrDefault(false)
        }
        return requireNotNull(device.findObject(By.text(text)))
    }

    private fun readView(file: File): JSONObject {
        var result: JSONObject?=null
        await("Atomic view state",10_000) {
            result=runCatching {
                if(!file.isFile) null
                else JSONObject(AtomicFile(file).openRead().bufferedReader().use {it.readText()})
            }.getOrNull()
            result!=null
        }
        return requireNotNull(result)
    }

    private fun assertValidView(view: JSONObject) {
        val zoom=view.getDouble("zoom")
        assertTrue("Finite supported zoom",zoom.isFinite() && zoom in 0.1..5.0)
        for(key in listOf("scrollTop","scrollLeft")) {
            val value=view.getDouble(key)
            assertTrue("Finite nonnegative $key",value.isFinite() && value>=0)
        }
    }

    private fun assertSameView(expected: JSONObject,actual: JSONObject) {
        assertEquals("Mode switch retains zoom",expected.getDouble("zoom"),actual.getDouble("zoom"),0.001)
        assertEquals("Mode switch retains vertical position",expected.getDouble("scrollTop"),actual.getDouble("scrollTop"),2.0)
        assertEquals("Mode switch retains horizontal position",expected.getDouble("scrollLeft"),actual.getDouble("scrollLeft"),2.0)
    }

    private fun assertUnchanged(working: File,workingHash: ByteArray,original: File,originalHash: ByteArray,edited: File) {
        assertArrayEquals("Mode-only interaction must not rewrite the working document",workingHash,hash(working))
        assertArrayEquals("The base original remains untouched",originalHash,hash(original))
        assertFalse("Mode-only interaction must not approve document adoption",edited.exists())
    }

    private fun hash(file: File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    private fun screenshot(format: Format,label: String) {
        val directory=File(context.getExternalFilesDir(null),"native-hangul-smoke").apply {mkdirs()}
        device.takeScreenshot(File(directory,"${format.extension}-$label.png"))
    }
    private fun await(label: String,timeout: Long,condition: ()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+timeout
        while(SystemClock.elapsedRealtime()<deadline) {
            if(condition()) return
            SystemClock.sleep(100)
        }
        assertTrue(label,condition())
    }
}
