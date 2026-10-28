package kr.documentstudio

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.EditText
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import kr.documentstudio.core.Format
import kr.documentstudio.data.DocumentRepository
import kr.documentstudio.ui.PdfObjectCanvas
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class ReadingUxTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val vm get()=ViewModelProvider(compose.activity)[EditorViewModel::class.java]
    private fun ready(){compose.waitUntil(30000){!vm.state.value.busy&&vm.state.value.session!=null};compose.waitForIdle()}
    private fun <T: View> find(root: View,type: Class<T>): T? {
        if(type.isInstance(root))return type.cast(root)
        if(root is ViewGroup)for(i in 0 until root.childCount)find(root.getChildAt(i),type)?.let {return it}
        return null
    }
    private fun save(ext: String): File {
        val file=File(compose.activity.cacheDir,"tests/reading-${UUID.randomUUID()}.$ext").apply {parentFile!!.mkdirs();writeBytes(byteArrayOf())}
        val uri=FileProvider.getUriForFile(compose.activity,compose.activity.packageName+".testfiles",file)
        compose.runOnUiThread {vm.save(uri)};ready();return file
    }
    @Test fun textReadingHidesEditingControlsAndFullscreenKeepsSelectionAndBytes(){
        compose.runOnUiThread {vm.create(Format.TXT)};ready()
        compose.onNodeWithTag("edit-document").performClick();compose.runOnUiThread {vm.text("읽기 전용 선택 한글")};compose.waitForIdle()
        val original=save("txt");val hash=DocumentRepository.digest(original)
        compose.runOnUiThread {vm.readDocument()};compose.waitForIdle()
        compose.onNodeWithTag("mobile-editor-dock").assertDoesNotExist()
        compose.onNodeWithContentDescription("실행 취소").assertDoesNotExist()
        var height=0
        compose.runOnUiThread {find(compose.activity.window.decorView,EditText::class.java)!!.apply {assertTrue(isTextSelectable);setSelection(0,5);height=this.height}}
        compose.onNodeWithContentDescription("문서 메뉴").performClick();compose.onNodeWithText("전체 화면 보기").performClick();compose.waitForIdle()
        compose.onNodeWithTag("mobile-editor-header").assertDoesNotExist()
        compose.runOnUiThread {find(compose.activity.window.decorView,EditText::class.java)!!.apply {assertTrue(this.height>height);assertEquals(0,selectionStart);assertEquals(5,selectionEnd)}}
        compose.onNodeWithContentDescription("메뉴 표시").performClick();compose.onNodeWithContentDescription("문서 검색").performClick()
        compose.onNodeWithText("찾을 문자열").performTextInput("한글");compose.onNodeWithText("다음 결과").performClick()
        compose.onNodeWithText("바꿀 문자열").assertDoesNotExist();compose.onNodeWithText("모두 바꾸기").assertDoesNotExist()
        compose.onNodeWithText("닫기").performClick()
        assertFalse(vm.state.value.session!!.dirty);assertEquals(hash,DocumentRepository.digest(original))
    }
    @Test fun pdfModeOnlyTransitionKeepsPageBitmapAndCanvas(){
        compose.runOnUiThread {vm.create(Format.PDF)};ready()
        compose.waitUntil(30000){var loaded=false;compose.runOnUiThread {loaded=find(compose.activity.window.decorView,PdfObjectCanvas::class.java)?.bitmap!=null};loaded}
        lateinit var canvas: PdfObjectCanvas
        compose.runOnUiThread {canvas=find(compose.activity.window.decorView,PdfObjectCanvas::class.java)!!}
        val bitmap=canvas.bitmap;val hash=DocumentRepository.digest(vm.state.value.session!!.base)
        compose.onNodeWithTag("edit-document").performClick();compose.waitForIdle()
        android.os.SystemClock.sleep(500)
        compose.runOnUiThread {assertSame(canvas,find(compose.activity.window.decorView,PdfObjectCanvas::class.java));assertSame(bitmap,canvas.bitmap);vm.readDocument()}
        compose.waitForIdle();android.os.SystemClock.sleep(500)
        compose.runOnUiThread {assertSame(canvas,find(compose.activity.window.decorView,PdfObjectCanvas::class.java));assertSame(bitmap,canvas.bitmap)}
        assertEquals(hash,DocumentRepository.digest(vm.state.value.session!!.base))
    }
    @Test fun longPdfJumpScrollAndReopenKeepsLastPageWithoutSaving(){
        val file=File(compose.activity.cacheDir,"tests/reading-long-${UUID.randomUUID()}.pdf").apply {parentFile!!.mkdirs()}
        com.tom_roush.pdfbox.pdmodel.PDDocument().use {doc->repeat(80){doc.addPage(com.tom_roush.pdfbox.pdmodel.PDPage())};doc.save(file)}
        val uri=FileProvider.getUriForFile(compose.activity,compose.activity.packageName+".testfiles",file)
        val hash=DocumentRepository.digest(file)
        compose.runOnUiThread {vm.open(uri)};ready()
        val id=vm.state.value.session!!.id
        compose.onNodeWithContentDescription("문서 메뉴").performClick();compose.onNodeWithText("보기 설정").performClick()
        compose.onNodeWithText("페이지 번호").performScrollTo().performTextReplacement("41")
        compose.onNodeWithText("이동").performScrollTo().performClick()
        compose.waitUntil(30000){var found=false;compose.runOnUiThread {found=find(compose.activity.window.decorView,PdfObjectCanvas::class.java)?.page==40};found}
        compose.onNodeWithContentDescription("문서 메뉴").performClick();compose.onNodeWithText("보기 설정").performClick()
        compose.onNodeWithTag("pdf-continuous-toggle").performScrollTo().performClick()
        compose.onNodeWithContentDescription("설정 닫기").performClick()
        compose.onNodeWithContentDescription("PDF 연속 페이지 목록").performScrollToIndex(60)
        compose.waitUntil(15000){compose.activity.getSharedPreferences("reading-position",0).getInt(id+":page",-1)==60}
        compose.runOnUiThread {vm.home()};compose.waitForIdle()
        compose.runOnUiThread {vm.restore(id)};ready()
        compose.waitUntil(30000){var found=false;compose.runOnUiThread {found=find(compose.activity.window.decorView,PdfObjectCanvas::class.java)?.page==60};found}
        assertFalse(vm.state.value.session!!.dirty);assertEquals(hash,DocumentRepository.digest(file))
    }
    @Test fun markdownStartsFormattedAndSaveFailureKeepsEditableRecovery(){
        compose.runOnUiThread {vm.create(Format.MD)};ready()
        compose.waitUntil(15000){var found=false;compose.runOnUiThread {found=find(compose.activity.window.decorView,WebView::class.java)!=null};found}
        compose.onNodeWithTag("edit-document").performClick();compose.waitForIdle()
        compose.runOnUiThread {assertNotNull(find(compose.activity.window.decorView,EditText::class.java));vm.text("# 제목\n\n한글 **굵게**")}
        compose.runOnUiThread {vm.save(android.net.Uri.parse("content://invalid.document.studio/test"))}
        compose.waitUntil(30000){!vm.state.value.busy}
        assertTrue(vm.state.value.presentation(vm.state.value.session!!.id).allowsMutation)
        assertEquals("# 제목\n\n한글 **굵게**",runBlocking {vm.repository.restore(vm.state.value.session!!.id).edits.text})
        val original=save("md")
        compose.runOnUiThread {vm.readDocument()};compose.waitForIdle()
        compose.waitUntil(15000){var found=false;compose.runOnUiThread {found=find(compose.activity.window.decorView,WebView::class.java)?.progress==100};found}
        compose.onNodeWithTag("mobile-editor-dock").assertDoesNotExist()
        assertEquals("# 제목\n\n한글 **굵게**",original.readText())
    }
}
