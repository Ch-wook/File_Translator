package kr.documentstudio

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kr.documentstudio.core.*
import kr.documentstudio.data.PdfAnnotations
import kr.documentstudio.ui.InkView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class WorkspaceRedesignUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val vm get()=ViewModelProvider(compose.activity)[EditorViewModel::class.java]
    private fun ready() {compose.waitUntil(25000) {!vm.state.value.busy && vm.state.value.session!=null};compose.waitForIdle()}
    private fun create(format: Format) {compose.runOnUiThread {vm.settings(Settings(visualOffice=false));vm.create(format)};ready()}
    private fun tab(name: String) {compose.onNodeWithTag("ribbon-$name").performScrollTo().performClick();compose.waitForIdle()}
    private fun screenshot(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.os.SystemClock.sleep(700) // Wait for the emulator compositor to display the asserted frame.
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir=File(compose.activity.getExternalFilesDir(null),"ui-060").apply {mkdirs()}
        File(dir,"$name.png").outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))};bitmap.recycle()
    }
    private fun save(ext: String): File {
        val f=File(compose.activity.cacheDir,"tests/${UUID.randomUUID()}.$ext").apply {parentFile!!.mkdirs();writeBytes(byteArrayOf())}
        val uri=FileProvider.getUriForFile(compose.activity,"kr.documentstudio.testfiles",f)
        compose.runOnUiThread {vm.save(uri)};ready();return f
    }
    private fun <T: View> find(root: View,type: Class<T>): T? {
        if(type.isInstance(root)) return type.cast(root)
        if(root is ViewGroup) for(i in 0 until root.childCount) find(root.getChildAt(i),type)?.let {return it}
        return null
    }
    @Test fun homeFormatCardStartsTextWithoutTypeDialog() {
        compose.waitForIdle();screenshot("home")
        compose.onNodeWithTag("new-txt").performScrollTo().performClick();ready()
        assertEquals(Format.TXT,vm.state.value.session!!.format)
        compose.onNodeWithTag("workspace-text").assertExists()
        compose.runOnUiThread {find(compose.activity.window.decorView,EditText::class.java)!!.setText("바로 시작하는 텍스트 작업")}
        assertEquals("바로 시작하는 텍스트 작업",save("txt").readText())
    }



    @Test fun pdfReadingAnnotationAndDrawingHaveSeparateTools() {
        create(Format.PDF)
        compose.onNodeWithContentDescription("PDF 연속 페이지 목록").assertExists()
        compose.onNodeWithText("기존 본문 문자열 편집").assertDoesNotExist()
        screenshot("pdf-reading")
        tab("주석");compose.onNodeWithText("메모 주석").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("메모")).performTextReplacement("검토할 내용")
        compose.onNodeWithText("적용").performClick();ready()
        assertEquals("검토할 내용",PdfAnnotations.list(vm.state.value.session!!.base,0).single().text)
        tab("그리기")
        compose.runOnUiThread {assertNotNull(find(compose.activity.window.decorView,InkView::class.java))}
        compose.onNodeWithContentDescription("볼펜").assertExists()
        screenshot("pdf-drawing")
        assertEquals("검토할 내용",PdfAnnotations.list(save("pdf"),0).single().text)
    }


    @Test fun textAndDrawingTabsPreserveInputAcrossRecreation() {
        create(Format.TXT)
        compose.runOnUiThread {find(compose.activity.window.decorView,EditText::class.java)!!.setText("첫 줄\n두 번째 줄")}
        tab("그리기");compose.onNodeWithContentDescription("추가 필기 도구").performClick()
        compose.onNodeWithText("격자").performScrollTo().performClick();compose.onNodeWithText("닫기").performClick()
        assertEquals(Paper.GRID,vm.state.value.session!!.edits.paper)
        screenshot("note-tools")
        compose.activityRule.scenario.recreate();compose.waitForIdle()
        compose.onNodeWithTag("ribbon-그리기").assertIsSelected();tab("홈")
        compose.runOnUiThread {assertEquals("첫 줄\n두 번째 줄",find(compose.activity.window.decorView,EditText::class.java)!!.text.toString())}
        assertEquals("첫 줄\n두 번째 줄",save("txt").readText())
    }

    @Test fun documentFamiliesNeverExposeAnExtractedTextInput() {
        for(format in listOf(Format.HWP,Format.HWPX,Format.DOCX,Format.XLSX,Format.PPTX)) {
            create(format)
            compose.onNodeWithTag("visual-document-workspace").assertExists()
            compose.onNodeWithTag("resume-visual-editor").assertIsEnabled()
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
            compose.onNodeWithText("그리기").performClick()
            compose.runOnUiThread {assertNotNull(find(compose.activity.window.decorView,InkView::class.java))}
            compose.onNodeWithText("문서",useUnmergedTree=true).performClick()
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        }
    }
    @Test fun pdfEditModeUsesItsOriginalPageCanvas() {
        create(Format.PDF);tab("편집")
        compose.onNodeWithTag("pdf-direct-editor").assertExists()
        compose.onNodeWithText("기존 본문 문자열 편집").assertDoesNotExist()
    }
}
