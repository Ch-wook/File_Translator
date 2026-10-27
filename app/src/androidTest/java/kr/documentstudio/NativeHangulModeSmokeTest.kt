package kr.documentstudio

import android.content.Intent
import android.os.SystemClock
import android.net.Uri
import android.view.MotionEvent
import android.view.InputDevice
import org.json.JSONTokener
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
import java.util.regex.Pattern

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
        device.wakeUp()
        // AOSP Quickstep can show its own ANR over an already loaded WebView.
        // Reset that unrelated launcher before starting the document Activity.
        device.executeShellCommand("am force-stop com.android.launcher3")
        context.startActivity(launch)
        try {
            await("The real engine must enable explicit editing after loading VIEW",120_000) {findButton("편집하기")?.isEnabled==true}
            button("편집하기")
            assertNull("A save control is hidden in VIEW",findButton("저장 후 닫기"))
            assertUnchanged(working,workingHash,session.base,originalHash,edited)

            pinchDocument(format,"view")
            // Normalize away from the upper zoom bound using the document menu.
            zoom(false);zoom(false)
            screenshot(format,"before-edit")
            NativeUiActions.tap(device,context.packageName,"편집하기")
            screenshot(format,"edit-requested")
            button("보기로")
            pinchDocument(format,"edit")
            val lowerZoom=JSONObject(eval("document.getElementById('engine').contentWindow.rhwpStudio.documentStudio.captureView()"))
            assertValidView(lowerZoom)
            assertUnchanged(working,workingHash,session.base,originalHash,edited)
            NativeUiActions.tap(device,context.packageName,"보기로")
            button("편집하기")
            assertSameView(lowerZoom,readView(viewFile))

            zoom(true);zoom(true)
            NativeUiActions.tap(device,context.packageName,"편집하기")
            button("보기로")
            val higherZoom=JSONObject(eval("document.getElementById('engine').contentWindow.rhwpStudio.documentStudio.captureView()"))
            assertValidView(higherZoom)
            assertTrue("The real WebView zoom command must update engine view metadata",
                higherZoom.getDouble("zoom")>lowerZoom.getDouble("zoom")+0.0001)
            NativeUiActions.tap(device,context.packageName,"보기로")
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
                    device.dumpWindowHierarchy(File(context.getExternalFilesDir(null),"native-hangul-smoke/${format.extension}-failure.xml"))
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
        NativeUiActions.tap(device,context.packageName,"문서 메뉴")
        val selector=if(increase)By.textStartsWith("확대 ") else By.text("축소")
        NativeUiActions.tap(device,selector,10000);device.waitForIdle(1000)
    }
    private fun eval(script: String): String {
        val uri=Uri.parse("content://"+context.packageName+".hangulprobe")
        val raw=context.contentResolver.acquireUnstableContentProviderClient(uri).use {client->checkNotNull(client).call("eval",script,null)!!.getString("result")!!}
        return JSONTokener(raw).nextValue().toString()
    }
    private fun pinchDocument(format: Format,mode: String) {
        val expression="document.getElementById('engine').contentWindow.rhwpStudio.documentStudio"
        await("Mode transition settles before pinching",10000){eval("!document.getElementById('mode').disabled")=="true"}
        val before=eval(expression+".captureView().zoom").toDouble()
        val rect=device.findObject(By.pkg(context.packageName).clazz("android.webkit.WebView"))!!.visibleBounds
        val cx=rect.exactCenterX();val cy=rect.top+rect.height()*.4f
        val down=SystemClock.uptimeMillis()
        fun inject(action: Int,count: Int,spread: Float) {
            val properties=Array(count){index->MotionEvent.PointerProperties().apply {id=index;toolType=MotionEvent.TOOL_TYPE_FINGER}}
            val coords=Array(count){index->MotionEvent.PointerCoords().apply {x=cx+(if(index==0)-spread else spread);y=cy;pressure=1f;size=.1f}}
            val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,count,properties,coords,0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0)
            try{assertTrue(instrumentation.uiAutomation.injectInputEvent(event,true))}finally{event.recycle()}
            SystemClock.sleep(30)
        }
        inject(MotionEvent.ACTION_DOWN,1,50f)
        inject(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,50f)
        for(spread in listOf(60f,70f,85f,100f))inject(MotionEvent.ACTION_MOVE,2,spread)
        inject(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,100f)
        inject(MotionEvent.ACTION_UP,1,100f)
        await("Two Android touch pointers must zoom the document without editing it",10000){eval(expression+".captureView().zoom").toDouble()>before*1.5}
        assertEquals("Pinch must not dirty document bytes","false",eval(expression+".dirty()"))
        val out=File(context.getExternalFilesDir(null),"native-hangul-smoke").apply {mkdirs()}
        File(out,format.extension+"-"+mode+"-pinch.json").writeText(JSONObject().put("actualAndroidTwoPointerInput",true).put("viewOnlyZoom",true).put("unchangedDocument",true).put("zoomBefore",before).put("zoomAfter",eval(expression+".captureView().zoom").toDouble()).toString(2))
    }

    private fun findButton(text: String): UiObject2? {
        NativeUiActions.refresh()
        return device.findObject(By.pkg(context.packageName).text(text))
            ?: device.findObject(By.pkg(context.packageName).desc(text))
    }

    private fun button(text: String): UiObject2 {
        await("Enabled host button: $text",20_000) {
            runCatching {findButton(text)?.isEnabled==true}.getOrDefault(false)
        }
        return requireNotNull(findButton(text))
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
        device.dumpWindowHierarchy(File(directory,"${format.extension}-$label.xml"))
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
