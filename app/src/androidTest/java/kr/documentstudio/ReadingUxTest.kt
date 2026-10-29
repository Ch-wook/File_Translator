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
        compose.onNodeWithTag("edit-document").performClick();compose.waitForIdle()
        compose.runOnUiThread {
            val input=find(compose.activity.window.decorView,EditText::class.java)!!
            input.requestFocus()
            val connection=checkNotNull(input.onCreateInputConnection(android.view.inputmethod.EditorInfo()))
            assertTrue(connection.commitText("읽기 전용 선택 ",1))
            for(value in listOf("ㅎ","하","한","한글"))assertTrue(connection.setComposingText(value,1))
            assertTrue(connection.finishComposingText())
            assertEquals("읽기 전용 선택 한글",input.text.toString())
        }
        compose.waitForIdle();assertEquals("읽기 전용 선택 한글",vm.state.value.session!!.edits.text)
        val original=save("txt");val hash=DocumentRepository.digest(original)
        compose.runOnUiThread {vm.readDocument()};compose.waitForIdle()
        compose.onNodeWithTag("mobile-editor-dock").assertDoesNotExist()
        compose.onNodeWithContentDescription("실행 취소").assertDoesNotExist()
        var height=0
        compose.runOnUiThread {find(compose.activity.window.decorView,EditText::class.java)!!.apply {assertTrue(isTextSelectable);setSelection(0,5);height=this.height}}
        compose.onNodeWithContentDescription("문서 메뉴").performClick();compose.onNodeWithText("전체 화면 보기").performClick();compose.waitForIdle()
        compose.onNodeWithTag("mobile-editor-header").assertDoesNotExist()
        compose.runOnUiThread {find(compose.activity.window.decorView,EditText::class.java)!!.apply {assertTrue(this.height>height);assertEquals(0,selectionStart);assertEquals(5,selectionEnd)}}
        compose.onNodeWithContentDescription("메뉴 표시").performClick();compose.waitForIdle()
        val evidence=File(compose.activity.getExternalFilesDir(null),"native-reading-ux").apply {mkdirs()}
        File(evidence,"restored-header.json").writeText(org.json.JSONObject().put("tree",compose.onRoot(useUnmergedTree=true).printToString()).put("editing",vm.state.value.presentation(vm.state.value.session!!.id).allowsMutation).toString())
        compose.runOnUiThread {find(compose.activity.window.decorView,EditText::class.java)!!.setSelection(0,0)}
        compose.waitForIdle()
        compose.onNodeWithContentDescription("문서 검색").performClick()
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
        for(scale in listOf(2f,1f,3f,1f)) {
            compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.SetProgress)).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){it(scale)}
            compose.waitForIdle();android.os.SystemClock.sleep(400);compose.waitForIdle()
            compose.onNodeWithContentDescription("PDF 연속 페이지 목록").assertExists()
        }
        compose.waitUntil(15000){compose.activity.getSharedPreferences("reading-position",0).getInt(id+":page",-1)==60}
        compose.onNodeWithContentDescription("PDF 연속 페이지 목록").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy){it(0f,133f)}
        compose.waitForIdle()
        val position=compose.onNodeWithContentDescription("PDF 연속 페이지 목록").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription]
        assertTrue(position.startsWith("60:"));assertTrue(position.substringAfter(':').toInt()>0)
        compose.runOnUiThread {vm.home()};compose.waitForIdle()
        compose.runOnUiThread {vm.restore(id)};ready()
        compose.onNodeWithContentDescription("PDF 연속 페이지 목록").assertExists()
        compose.waitUntil(30000){compose.onNodeWithContentDescription("PDF 연속 페이지 목록").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription]==position}
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
        val original=save("md");assertNull("Successful save must clear the previous failure detail",vm.state.value.problem)
        compose.runOnUiThread {vm.readDocument()};compose.waitForIdle()
        compose.waitUntil(15000){var found=false;compose.runOnUiThread {found=find(compose.activity.window.decorView,WebView::class.java)?.progress==100};found}
        compose.onNodeWithTag("mobile-editor-dock").assertDoesNotExist()
        assertEquals("# 제목\n\n한글 **굵게**",original.readText())
    }

    @Test fun textModeAndToolsKeepTheNativeViewSelectionAndBlockReadingMutations(){
        compose.runOnUiThread {vm.create(Format.TXT)};ready()
        compose.onNodeWithTag("edit-document").performClick();compose.waitForIdle()
        val source=("한글 선택 범위와 원본 유지\n").repeat(2000)
        compose.runOnUiThread {vm.text(source)};compose.waitForIdle()
        lateinit var native: EditText
        compose.runOnUiThread {native=find(compose.activity.window.decorView,EditText::class.java)!!;native.setSelection(120,130)}
        repeat(3) {
            compose.onNodeWithText("더보기").performClick();compose.waitForIdle()
            compose.onNodeWithContentDescription("설정 닫기").performClick();compose.waitForIdle()
            compose.runOnUiThread {assertSame(native,find(compose.activity.window.decorView,EditText::class.java));assertEquals(120,native.selectionStart);assertEquals(130,native.selectionEnd);vm.readDocument()}
            compose.waitForIdle()
            compose.runOnUiThread {
                assertSame(native,find(compose.activity.window.decorView,EditText::class.java))
                assertNull(native.onCreateInputConnection(android.view.inputmethod.EditorInfo()))
                assertFalse(native.onTextContextMenuItem(android.R.id.cut));assertFalse(native.onTextContextMenuItem(android.R.id.paste))
                assertEquals(source,native.text.toString())
            }
            compose.onNodeWithTag("edit-document").performClick();compose.waitForIdle()
        }
        compose.runOnUiThread {
            assertEquals(120,native.selectionStart);assertEquals(130,native.selectionEnd)
            val connection=checkNotNull(native.onCreateInputConnection(android.view.inputmethod.EditorInfo()))
            assertTrue(connection.commitText("수정",1))
        }
        compose.waitForIdle()
        val expected=source.replaceRange(120,130,"수정")
        assertEquals(expected,vm.state.value.session!!.edits.text)
        val saved=save("txt");assertEquals(expected,saved.readText())
    }
    @Test fun pdfZoomRerasterizesWithinBudgetAndReleasesTheClosedPage(){
        compose.runOnUiThread {vm.create(Format.PDF)};ready()
        compose.waitUntil(30000){var ok=false;compose.runOnUiThread {ok=find(compose.activity.window.decorView,PdfObjectCanvas::class.java)?.bitmap!=null};ok}
        lateinit var initial: android.graphics.Bitmap
        compose.runOnUiThread {initial=find(compose.activity.window.decorView,PdfObjectCanvas::class.java)!!.bitmap!!}
        val source=vm.state.value.session!!.base;val hash=DocumentRepository.digest(source)
        compose.onNodeWithContentDescription("문서 메뉴").performClick();compose.onNodeWithText("보기 설정").performClick()
        repeat(4){compose.onNodeWithText("확대").performScrollTo().performClick()}
        compose.onNodeWithContentDescription("설정 닫기").performClick()
        compose.waitUntil(30000){var ok=false;compose.runOnUiThread {ok=(find(compose.activity.window.decorView,PdfObjectCanvas::class.java)?.bitmap?.width ?: 0)>initial.width};ok}
        lateinit var zoomed: android.graphics.Bitmap
        compose.runOnUiThread {zoomed=find(compose.activity.window.decorView,PdfObjectCanvas::class.java)!!.bitmap!!;assertTrue(zoomed.width.toLong()*zoomed.height<=4000000)}
        compose.waitUntil(5000){initial.isRecycled}
        val output=File(compose.activity.getExternalFilesDir(null),"native-reading-ux").apply {mkdirs()}
        File(output,"pdf-raster-budget.json").writeText(org.json.JSONObject().put("zoomWidth",zoomed.width).put("zoomHeight",zoomed.height).put("allocationBytes",zoomed.allocationByteCount).put("originalUnchanged",hash==DocumentRepository.digest(source)).toString())
        compose.runOnUiThread {vm.home()};compose.waitForIdle()
        compose.waitUntil(5000){zoomed.isRecycled}
        assertEquals(hash,DocumentRepository.digest(source))
    }
}
