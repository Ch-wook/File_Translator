package kr.documentstudio

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import kr.documentstudio.core.*
import kr.documentstudio.ui.PdfObjectCanvas
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Device tests for the host contracts; native Office/Hangul acceptance is separate. */
class P0ModeUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val vm get()=ViewModelProvider(compose.activity)[EditorViewModel::class.java]
    private fun ready() {compose.waitUntil(30000) {!vm.state.value.busy && vm.state.value.session!=null};compose.waitForIdle()}
    private fun <T: View> find(root: View,type: Class<T>): T? {
        if(type.isInstance(root)) return type.cast(root)
        if(root is ViewGroup) for(i in 0 until root.childCount) find(root.getChildAt(i),type)?.let {return it}
        return null
    }
    @Test fun textStartsInViewAndRequiresExplicitEditingBeforeKoreanInputAndSaving() {
        compose.runOnUiThread {vm.create(Format.TXT)};ready()
        val id=vm.state.value.active!!
        compose.runOnUiThread {
            val input=find(compose.activity.window.decorView,EditText::class.java)!!
            assertNull(input.keyListener);assertFalse(input.isCursorVisible)
            vm.text("blocked");assertEquals("",vm.state.value.session!!.edits.text)
        }
        compose.onNodeWithTag("edit-document").performClick();compose.waitForIdle()
        compose.runOnUiThread {
            assertEquals(PresentationState.EDITING,vm.state.value.presentation(id))
            val input=find(compose.activity.window.decorView,EditText::class.java)!!
            assertNotNull(input.keyListener);input.setText("한국어 본문\n두 번째 줄")
        }
        compose.waitForIdle();assertEquals("한국어 본문\n두 번째 줄",vm.state.value.session!!.edits.text)
        val file=File(compose.activity.cacheDir,"tests/${UUID.randomUUID()}.txt").apply {parentFile!!.mkdirs();writeBytes(byteArrayOf())}
        val uri=FileProvider.getUriForFile(compose.activity,"kr.documentstudio.testfiles",file)
        compose.runOnUiThread {vm.save(uri)};ready()
        assertEquals("한국어 본문\n두 번째 줄",file.readText())
        compose.onNodeWithTag("read-document").performClick();compose.waitForIdle()
        compose.runOnUiThread {vm.text("blocked again");assertEquals(PresentationState.READING,vm.state.value.presentation(id))}
        assertEquals("한국어 본문\n두 번째 줄",runBlocking {vm.repository.open(uri).edits.text})
    }
    @Test fun pdfUsesOneCanvasAcrossExplicitViewEditTransitions() {
        compose.runOnUiThread {vm.create(Format.PDF)};ready()
        compose.waitUntil(30000) {var found=false;compose.runOnUiThread {found=find(compose.activity.window.decorView,PdfObjectCanvas::class.java)!=null};found}
        lateinit var canvas: PdfObjectCanvas
        compose.runOnUiThread {canvas=find(compose.activity.window.decorView,PdfObjectCanvas::class.java)!!;assertFalse(canvas.editable)}
        compose.onNodeWithTag("edit-document").performClick();compose.waitForIdle()
        compose.runOnUiThread {assertSame(canvas,find(compose.activity.window.decorView,PdfObjectCanvas::class.java));assertTrue(canvas.editable)}
        compose.onNodeWithTag("read-document").performClick();compose.waitForIdle()
        compose.runOnUiThread {assertSame(canvas,find(compose.activity.window.decorView,PdfObjectCanvas::class.java));assertFalse(canvas.editable)}
    }
}
