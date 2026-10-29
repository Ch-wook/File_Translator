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
    private fun viewportHash(view: View): String {
        val done=java.util.concurrent.CountDownLatch(1);var status=-1
        lateinit var image: android.graphics.Bitmap
        compose.runOnUiThread {
            val point=IntArray(2);view.getLocationInWindow(point)
            image=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
            val rect=android.graphics.Rect(point[0],point[1],point[0]+view.width,point[1]+view.height)
            android.view.PixelCopy.request(compose.activity.window,rect,image,{result->status=result;done.countDown()},android.os.Handler(android.os.Looper.getMainLooper()))
        }
        assertTrue(done.await(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(android.view.PixelCopy.SUCCESS,status)
        return try{val bytes=java.nio.ByteBuffer.allocate(image.byteCount);image.copyPixelsToBuffer(bytes);java.security.MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString(""){"%02x".format(it)}}
        finally{image.recycle()}
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
        com.tom_roush.pdfbox.pdmodel.PDDocument().use {doc->
            repeat(100){index->
                val page=com.tom_roush.pdfbox.pdmodel.PDPage(if(index%2==0)com.tom_roush.pdfbox.pdmodel.common.PDRectangle.A4 else com.tom_roush.pdfbox.pdmodel.common.PDRectangle.LETTER)
                if(index%7==0)page.rotation=90
                doc.addPage(page)
                com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc,page).use {content->
                    content.beginText();content.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA,16f)
                    content.newLineAtOffset(50f,500f);content.showText("VIEWER_PDF_PAGE_${index+1}");content.endText()
                }
            };doc.save(file)
        }
        val uri=FileProvider.getUriForFile(compose.activity,compose.activity.packageName+".testfiles",file)
        val hash=DocumentRepository.digest(file)
        compose.runOnUiThread {vm.open(uri)};ready()
        val id=vm.state.value.session!!.id
        assertEquals(100,vm.state.value.session!!.pdf!!.pages)
        assertEquals(100,vm.state.value.session!!.pdf!!.pageSizes.size)
        assertTrue(vm.state.value.session!!.pdf!!.pageSizes.first().width>vm.state.value.session!!.pdf!!.pageSizes.first().height)
        val pages=compose.onNodeWithContentDescription("PDF 연속 페이지 목록")
        pages.assertExists()
        // Default continuous reading must reach actual distinct pages, including 100.
        for(index in listOf(1,49,99,60)){
            pages.performScrollToIndex(index);compose.waitForIdle()
            compose.waitUntil(20000){compose.activity.getSharedPreferences("reading-position",0).getInt(id+":page",-1)==index}
            compose.onNodeWithContentDescription("연속 보기 ${index+1}페이지").assertExists()
        }
        fun jump(number: String){
            compose.onNodeWithContentDescription("문서 메뉴").performClick();compose.onNodeWithText("보기 설정").performClick()
            compose.onNodeWithText("페이지 번호").performScrollTo().performTextReplacement(number)
            compose.onNodeWithText("이동").performScrollTo().performClick();compose.waitForIdle()
        }
        jump("50")
        compose.waitUntil(15000){compose.activity.getSharedPreferences("reading-position",0).getInt(id+":page",-1)==49}
        // Preserve the explicit single-page option, then return to continuous.
        compose.onNodeWithContentDescription("문서 메뉴").performClick();compose.onNodeWithText("보기 설정").performClick()
        compose.onNodeWithTag("pdf-continuous-toggle").performScrollTo().performClick()
        compose.onNodeWithContentDescription("설정 닫기").performClick()
        compose.waitUntil(30000){var found=false;compose.runOnUiThread {found=find(compose.activity.window.decorView,PdfObjectCanvas::class.java)?.page==49};found}
        compose.onNodeWithContentDescription("문서 메뉴").performClick();compose.onNodeWithText("보기 설정").performClick()
        compose.onNodeWithTag("pdf-continuous-toggle").performScrollTo().performClick()
        compose.onNodeWithContentDescription("설정 닫기").performClick();pages.assertExists()
        pages.performScrollToIndex(60);compose.waitForIdle()
        val prefs=compose.activity.getSharedPreferences("reading-position",0)
        val before=prefs.getFloat(id+":zoom",1f)
        compose.onRoot().performTouchInput {
            val point=center
            pinch(start0=point+androidx.compose.ui.geometry.Offset(-45f,0f),end0=point+androidx.compose.ui.geometry.Offset(-95f,0f),
                  start1=point+androidx.compose.ui.geometry.Offset(45f,0f),end1=point+androidx.compose.ui.geometry.Offset(95f,0f),durationMillis=500)
        }
        compose.waitUntil(15000){prefs.getFloat(id+":zoom",1f)>before*1.4f}
        val pinched=prefs.getFloat(id+":zoom",1f)
        assertTrue("A 90→190 px two-finger spread must produce a natural scale",pinched/before in 1.7f..2.5f)
        pages.assertExists()
        // Zooming is view-only, and the same logical page remains near the focal point.
        assertTrue(prefs.getInt(id+":page",0) in 59..61)
        compose.onRoot().performTouchInput {doubleClick(center)}
        compose.waitUntil(15000){kotlin.math.abs(prefs.getFloat(id+":zoom",1f)-pinched)>.1f}
        pages.performScrollToIndex(99);compose.waitForIdle()
        compose.waitUntil(15000){prefs.getInt(id+":page",-1)==99}
        pages.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy){it(0f,133f)}
        compose.waitForIdle()
        val position=pages.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription]
        assertTrue(position.substringBefore(':').toInt() in 98..99)
        compose.onNodeWithContentDescription("연속 보기 100페이지").assertExists()
        compose.runOnUiThread {vm.home()};compose.waitForIdle()
        compose.runOnUiThread {vm.restore(id)};ready()
        compose.onNodeWithContentDescription("PDF 연속 페이지 목록").assertExists()
        compose.waitUntil(30000){compose.onNodeWithContentDescription("PDF 연속 페이지 목록").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription]==position}
        assertFalse(vm.state.value.session!!.dirty);assertEquals(hash,DocumentRepository.digest(file))
        com.tom_roush.pdfbox.pdmodel.PDDocument.load(file).use {doc->
            val text=com.tom_roush.pdfbox.text.PDFTextStripper().getText(doc)
            val normalized=text.filterNot {it.isWhitespace()}
            val missing=(1..100).filterNot {normalized.contains("VIEWER_PDF_PAGE_"+it)}
            val output=File(compose.activity.getExternalFilesDir(null),"native-reading-ux").apply {mkdirs()}
            File(output,"pdf-100-extraction.json").writeText(org.json.JSONObject().put("missingPageMarkers",org.json.JSONArray(missing))
                .put("extractedText",text).toString(2))
            assertTrue("Independent extraction must preserve all markers; missing: "+missing,missing.isEmpty())
        }
        val output=File(compose.activity.getExternalFilesDir(null),"native-reading-ux").apply {mkdirs()}
        File(output,"pdf-100-page-navigation.json").writeText(org.json.JSONObject().put("pages",100).put("mixedSizesAndRotation",true)
            .put("actualTwoPointerZoom",true).put("doubleTapZoom",true).put("page100ReachedAndRestored",true)
            .put("sourceHashUnchanged",true).put("zoomBefore",before).put("zoomAfterPinch",pinched).toString(2))
    }
    @Test fun markdownStartsFormattedAndSaveFailureKeepsEditableRecovery(){
        val source="# 제목\n\n한글 **굵게**\n\n"+(0 until 100).joinToString("\n\n") {"MD_SECTION_"+it+" 한글 읽기 문단"}
        compose.runOnUiThread {vm.create(Format.MD)};ready()
        compose.waitUntil(15000){var found=false;compose.runOnUiThread {found=find(compose.activity.window.decorView,WebView::class.java)!=null};found}
        compose.onNodeWithTag("edit-document").performClick();compose.waitForIdle()
        compose.runOnUiThread {assertNotNull(find(compose.activity.window.decorView,EditText::class.java));vm.text(source)}
        compose.runOnUiThread {vm.save(android.net.Uri.parse("content://invalid.document.studio/test"))}
        compose.waitUntil(30000){!vm.state.value.busy}
        assertTrue(vm.state.value.presentation(vm.state.value.session!!.id).allowsMutation)
        assertEquals(source,runBlocking {vm.repository.restore(vm.state.value.session!!.id).edits.text})
        val original=save("md");assertNull("Successful save must clear the previous failure detail",vm.state.value.problem)
        compose.runOnUiThread {vm.readDocument()};compose.waitForIdle()
        compose.waitUntil(15000){var found=false;compose.runOnUiThread {found=find(compose.activity.window.decorView,WebView::class.java)?.progress==100};found}
        compose.onNodeWithTag("mobile-editor-dock").assertDoesNotExist()
        assertEquals(source,original.readText())
        val hash=DocumentRepository.digest(original)
        compose.waitUntil(15000){var loaded=false;compose.runOnUiThread {loaded=(find(compose.activity.window.decorView,WebView::class.java)?.contentHeight?:0)>1500};loaded}
        lateinit var preview: WebView
        compose.runOnUiThread {preview=find(compose.activity.window.decorView,WebView::class.java)!!;assertFalse(preview.settings.javaScriptEnabled)}
        if(compose.onAllNodesWithContentDescription("Dismiss").fetchSemanticsNodes().isNotEmpty())compose.onNodeWithContentDescription("Dismiss").performClick()
        fun settings(){compose.onNodeWithContentDescription("문서 메뉴").performClick();compose.onNodeWithText("보기 설정").performClick()}
        settings();compose.onNodeWithText("문서 맨 아래").performScrollTo().performClick()
        compose.waitUntil(10000){var y=0;compose.runOnUiThread {y=preview.scrollY};y>500}
        compose.onNodeWithText("문서 맨 위").performScrollTo().performClick()
        compose.waitUntil(10000){var y=-1;compose.runOnUiThread {y=preview.scrollY};y==0}
        compose.onNodeWithContentDescription("설정 닫기").performClick();compose.waitForIdle()
        var targetFont="NanumMyeongjo"
        compose.runOnUiThread {if(preview.settings.standardFontFamily==targetFont)targetFont="NanumGothic"}
        val targetLabel=if(targetFont=="NanumMyeongjo")"나눔명조 · NanumMyeongjo" else "나눔고딕 · NanumGothic"
        val targetFile=if(targetFont=="NanumMyeongjo")"NanumMyeongjo-Regular.ttf" else "NanumGothic-Regular.ttf"
        val before=viewportHash(preview)
        settings();compose.onNodeWithText("보기 글꼴·크기").performScrollTo().performClick()
        compose.onNodeWithText("글꼴 ·",substring=true).performScrollTo().performClick()
        compose.onNodeWithText(targetLabel,substring=true).performScrollTo().performClick()
        compose.onNodeWithContentDescription("설정 닫기").performClick();compose.waitForIdle()
        compose.waitUntil(15000){
            var supplied=false
            compose.runOnUiThread {
                assertSame(preview,find(compose.activity.window.decorView,WebView::class.java))
                assertEquals(targetFont,preview.settings.standardFontFamily)
                val client=preview.webViewClient
                val field=client.javaClass.getDeclaredField("providedFonts").apply {isAccessible=true}
                supplied=(field.get(client) as Set<*>).contains(targetFile)
            };supplied
        }
        assertNotEquals("A bundled font must change actual viewport pixels",before,viewportHash(preview))
        var initialZoom=0
        compose.runOnUiThread {initialZoom=preview.settings.textZoom}
        val targetSize=if(kotlin.math.abs(initialZoom-150*compose.activity.resources.configuration.fontScale)<1)32 else 24
        settings();compose.onNodeWithText("보기 글꼴·크기").performScrollTo().performClick()
        compose.onNodeWithText("보기 크기(sp)").performScrollTo().performTextReplacement(targetSize.toString())
        compose.onNodeWithContentDescription("설정 닫기").performClick();compose.waitForIdle()
        compose.runOnUiThread {assertSame(preview,find(compose.activity.window.decorView,WebView::class.java));assertNotEquals(initialZoom,preview.settings.textZoom);assertFalse(preview.settings.javaScriptEnabled);assertTrue(preview.settings.blockNetworkLoads)}
        assertEquals(hash,DocumentRepository.digest(original));assertEquals(source,vm.state.value.session!!.edits.text)
        val evidence=File(compose.activity.getExternalFilesDir(null),"native-reading-ux").apply {mkdirs()}
        File(evidence,"markdown-reader-tools.json").writeText(org.json.JSONObject().put("nativeTopBottomNavigation",true).put("bundledFontSupplied",true)
            .put("fontChangesViewportPixels",true).put("displayZoomAppliedWithoutReplacingWebView",true).put("scriptsAndNetworkDisabled",true).put("sourceUnchanged",true).toString(2))
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
