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
import kr.documentstudio.ui.SlideView
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
    @Test fun hangulPaperInputSelectedFormattingAndRotationSaveActualFile() {
        create(Format.HWPX)
        val field=vm.state.value.session!!.sections.first().fields.first()
        compose.onNodeWithContentDescription(field.label).performTextReplacement("사용하기 쉬운 문서 편집")
        compose.onNodeWithContentDescription("굵게").performClick()
        assertEquals("true",vm.state.value.session!!.edits.styles[field.id]?.get("bold"))
        compose.onNodeWithContentDescription("실행 취소").performClick()
        compose.onNodeWithContentDescription("다시 실행").performClick()
        screenshot("hangul-paper")
        tab("레이아웃");compose.activityRule.scenario.recreate();compose.waitForIdle()
        compose.onNodeWithTag("ribbon-레이아웃").assertIsSelected()
        val reopened=PackageDocument.open(save("hwpx"))
        assertEquals("사용하기 쉬운 문서 편집",reopened.sections.first().fields.first().text)
        assertEquals("true",reopened.sections.first().fields.first().styles["bold"])
    }
    @Test fun selectedCellAcceptsInputAndSheetTabsPreserveValues() {
        create(Format.XLSX)
        compose.onNodeWithTag("cell-B2").performClick();compose.onNodeWithTag("cell-B2").performClick()
        compose.onNodeWithContentDescription("선택 셀 입력").performTextReplacement("42")
        compose.onNodeWithContentDescription("선택 셀 입력").performImeAction()
        val sec=vm.state.value.session!!.sections.first()
        assertEquals("42",vm.state.value.session!!.edits.values[sec.id+"#B2"])
        screenshot("sheet-cells")
        compose.onNodeWithContentDescription("시트 추가").performClick();ready()
        assertEquals(2,vm.state.value.session!!.sections.size)
        compose.onNodeWithTag("sheet-tab-1").performClick();compose.onNodeWithTag("sheet-tab-0").performClick()
        assertEquals("42",PackageDocument.open(save("xlsx")).sections.first().cells["B2"])
    }
    @Test fun hangulTableGridKeepsEachEditedCellAndSelectedStyle() {
        create(Format.HWPX)
        tab("삽입");compose.onNodeWithText("표 삽입").performScrollTo().performClick()
        compose.onNodeWithText("행 수 (1~50)").performTextReplacement("2")
        compose.onNodeWithText("열 수 (1~20)").performTextReplacement("2")
        compose.onNodeWithText("적용").performClick();ready();tab("홈")
        compose.onNodeWithTag("table-grid-1").assertExists()
        val fields=vm.state.value.session!!.sections.first().fields.filter {it.label.startsWith("표 ")}
        assertEquals(4,fields.size)
        fields.forEachIndexed {i,f->compose.onNodeWithContentDescription(f.label).performScrollTo().performTextReplacement("셀 ${i+1}")}
        compose.onNodeWithContentDescription("굵게").performClick()
        assertEquals("true",vm.state.value.session!!.edits.styles[fields.last().id]?.get("bold"))
        screenshot("hangul-table")
        val reopened=PackageDocument.open(save("hwpx")).sections.first().fields.associateBy {it.id}
        fields.forEachIndexed {i,f->assertEquals("셀 ${i+1}",reopened.getValue(f.id).text)}
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
    @Test fun slideCanvasAndSlideCommandsSaveSlides() {
        create(Format.PPTX)
        compose.waitUntil(20000) {find(compose.activity.window.decorView,SlideView::class.java)?.scene!=null}
        screenshot("slides")
        tab("슬라이드");compose.onNodeWithText("현재 슬라이드 복제").performScrollTo().performClick()
        compose.onNodeWithText("적용").performClick();ready()
        assertEquals(2,vm.state.value.session!!.sections.size)
        assertEquals(2,PackageDocument.open(save("pptx")).sections.size)
    }
    @Test fun searchedLayoutUsesSwitchAndNumericInputInsteadOfBooleanStrings() {
        create(Format.DOCX)
        compose.onNodeWithText("삽입·문서 도구").performClick()
        compose.onNodeWithText("도구 이름 검색").performTextReplacement("용지")
        compose.onNodeWithText("A4 용지·여백").performClick()
        compose.onNodeWithText("가로 방향 (true / false)").assertDoesNotExist()
        compose.onNodeWithContentDescription("가로 방향").performClick()
        compose.onNodeWithText("여백 mm").performTextReplacement("15")
        compose.onNodeWithText("적용").performClick();ready()
        val doc=PackageDocument.open(save("docx"))
        java.util.zip.ZipFile(doc.source).use {zip->
            val xml=zip.getInputStream(zip.getEntry("word/document.xml")).use {Xml.parse(it.readBytes())}
            assertEquals("landscape",Xml.elements(xml,"pgSz").last().getAttributeNS(PackageDocument.W,"orient"))
        }
        screenshot("word-basic")
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
}
