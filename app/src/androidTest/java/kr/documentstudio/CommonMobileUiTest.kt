package kr.documentstudio

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import kr.documentstudio.core.Format
import kr.documentstudio.ui.PdfObjectCanvas
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice

/** Native mobile chrome must preserve the underlying platform editor and saved text. */
class CommonMobileUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val vm get()=ViewModelProvider(compose.activity)[EditorViewModel::class.java]
    private fun ready(){compose.waitUntil(30000){!vm.state.value.busy&&vm.state.value.session!=null};compose.waitForIdle()}
    private fun <T: View> find(root: View,type: Class<T>): T? {
        if(type.isInstance(root))return type.cast(root)
        if(root is ViewGroup)for(i in 0 until root.childCount)find(root.getChildAt(i),type)?.let {return it}
        return null
    }
    private fun recordUi(label: String) {
        val out=File(compose.activity.getExternalFilesDir(null),"native-common-ui").apply {mkdirs()}
        File(out,label+".txt").writeText(compose.onRoot(useUnmergedTree=true).printToString())
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(out,label+".png"))
    }
    @Test fun textViewFontsKeepSelectionAndSavedBytes(){
        compose.runOnUiThread {compose.activity.getSharedPreferences("editor-display",0).edit().clear().commit();vm.create(Format.TXT)}
        ready();compose.onNodeWithTag("edit-document").performClick();compose.waitForIdle()
        compose.runOnUiThread {find(compose.activity.window.decorView,EditText::class.java)!!.setText("선택 AB 유지")}
        compose.waitForIdle()
        val file=File(compose.activity.cacheDir,"tests/ui-${UUID.randomUUID()}.txt").apply {parentFile!!.mkdirs();writeBytes(byteArrayOf())}
        val uri=FileProvider.getUriForFile(compose.activity,compose.activity.packageName+".testfiles",file)
        compose.runOnUiThread {vm.save(uri)};ready()
        val before=file.readBytes();assertEquals("선택 AB 유지",file.readText())
        compose.runOnUiThread {find(compose.activity.window.decorView,EditText::class.java)!!.setSelection(3,5)}
        compose.onNodeWithTag("mobile-editor-header").assertHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("mobile-editor-dock").assertHeightIsEqualTo(48.dp)
        compose.onNodeWithText("보기").performClick();compose.waitForIdle();recordUi("text-character-panel")
        compose.onNode(hasText("글꼴 ·",substring=true)).performScrollTo().performClick()
        compose.onNode(hasText("Noto Sans KR",substring=true) and hasClickAction()).performScrollTo().performClick();compose.waitForIdle()
        compose.onNode(hasText("글꼴 ·",substring=true)).performScrollTo().performClick()
        compose.onNode(hasText("시스템 바탕",substring=true) and hasClickAction()).performScrollTo().performClick();compose.waitForIdle()
        compose.onNodeWithContentDescription("설정 닫기").performClick()
        compose.runOnUiThread {
            val input=find(compose.activity.window.decorView,EditText::class.java)!!
            assertEquals(3,input.selectionStart);assertEquals(5,input.selectionEnd);assertEquals("선택 AB 유지",input.text.toString())
        }
        assertFalse(vm.state.value.session!!.dirty);assertArrayEquals(before,file.readBytes())
        assertEquals("선택 AB 유지",runBlocking {vm.repository.open(uri).edits.text})
        compose.onNodeWithText("찾기").performClick();compose.onNodeWithText("검색·바꾸기").performScrollTo().performClick()
        compose.onNodeWithText("찾을 문자열").performTextInput("AB");compose.onNodeWithText("다음 결과").performClick()
        compose.onNodeWithText("바꿀 문자열").performTextInput("교체");compose.onNodeWithText("바꾸기").performClick();compose.waitForIdle()
        assertEquals("선택 교체 유지",vm.state.value.session!!.edits.text)
        compose.onNodeWithText("닫기").performClick();compose.onNodeWithContentDescription("설정 닫기").performClick()
        compose.runOnUiThread {vm.save(uri)};ready()
        assertEquals("선택 교체 유지",runBlocking {vm.repository.open(uri).edits.text})
    }
    @Test fun markdownToolsKeepScopeAndReopenAsMarkdownSyntax(){
        compose.runOnUiThread {vm.create(Format.MD)};ready();compose.onNodeWithTag("edit-document").performClick();compose.waitForIdle()
        compose.runOnUiThread {find(compose.activity.window.decorView,EditText::class.java)!!.apply {setText("선택 AB 유지");setSelection(0,2)}}
        compose.onNodeWithText("글자").performClick()
        compose.runOnUiThread {find(compose.activity.window.decorView,EditText::class.java)!!.setSelection(3,5)}
        compose.onNodeWithText("굵게").performScrollTo().performClick();compose.waitForIdle()
        assertEquals("선택 **AB** 유지",vm.state.value.session!!.edits.text)
        compose.onNodeWithText("굵게").performScrollTo().performClick();compose.waitForIdle();assertEquals("선택 AB 유지",vm.state.value.session!!.edits.text)
        compose.onNodeWithText("굵게").performScrollTo().performClick();compose.onNodeWithText("기울임").performScrollTo().performClick();compose.onNodeWithText("취소선").performScrollTo().performClick();compose.waitForIdle()
        assertEquals("선택 ***~~AB~~*** 유지",vm.state.value.session!!.edits.text)
        val file=File(compose.activity.cacheDir,"tests/ui-${UUID.randomUUID()}.md").apply {parentFile!!.mkdirs();writeBytes(byteArrayOf())}
        val uri=FileProvider.getUriForFile(compose.activity,compose.activity.packageName+".testfiles",file)
        compose.runOnUiThread {vm.save(uri)};ready()
        assertEquals("선택 ***~~AB~~*** 유지",runBlocking {vm.repository.open(uri).edits.text})
        compose.onNodeWithContentDescription("설정 닫기").performClick()
        compose.onNodeWithTag("mobile-editor-sheet").assertDoesNotExist()
    }
    @Test fun pdfZoomChangesOnlyTheViewportAndKeepsTheExistingPageCanvas(){
        compose.runOnUiThread {vm.create(Format.PDF)};ready()
        compose.waitUntil(30000){var found=false;compose.runOnUiThread {found=find(compose.activity.window.decorView,PdfObjectCanvas::class.java)!=null};found}
        compose.onNodeWithTag("edit-document").performClick();compose.waitForIdle()
        lateinit var canvas: PdfObjectCanvas;var width=0
        val originalDirty=vm.state.value.session!!.dirty
        val hash=kr.documentstudio.data.DocumentRepository.digest(vm.state.value.session!!.base)
        compose.runOnUiThread {canvas=find(compose.activity.window.decorView,PdfObjectCanvas::class.java)!!;width=canvas.width}
        compose.onNodeWithText("더보기").performClick()
        compose.onNodeWithText("확대").performScrollTo().performClick()
        compose.waitUntil(10000){var wider=false;compose.runOnUiThread {wider=canvas.width>width};wider}
        compose.onNodeWithContentDescription("설정 닫기").performClick()
        compose.runOnUiThread {assertSame(canvas,find(compose.activity.window.decorView,PdfObjectCanvas::class.java))}
        assertEquals(originalDirty,vm.state.value.session!!.dirty)
        assertEquals(hash,kr.documentstudio.data.DocumentRepository.digest(vm.state.value.session!!.base))
    }
}
