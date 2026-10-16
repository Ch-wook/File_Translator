package kr.documentstudio

import android.graphics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import kr.documentstudio.data.PdfEngine
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class OcrUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun rangeRecognitionReviewApplyUndoRedoAndTxtCreationWork() {
        val vm=ViewModelProvider(compose.activity)[EditorViewModel::class.java]
        val file=File(compose.activity.cacheDir,"tests/${UUID.randomUUID()}.pdf").apply {parentFile!!.mkdirs()}
        PDDocument().use {doc->repeat(2) {n->val page=PDPage(PDRectangle(600f,300f));doc.addPage(page)
            val bitmap=Bitmap.createBitmap(1200,600,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.WHITE)}
            Canvas(bitmap).drawText("OFFLINE OCR PAGE ${n+1}",60f,220f,Paint(Paint.ANTI_ALIAS_FLAG).apply {textSize=64f;color=Color.BLACK})
            val image=LosslessFactory.createFromImage(doc,bitmap);bitmap.recycle();PDPageContentStream(doc,page).use {it.drawImage(image,0f,0f,600f,300f)}
        };doc.save(file)}
        compose.runOnUiThread {vm.open(FileProvider.getUriForFile(compose.activity,"kr.documentstudio.testfiles",file))}
        compose.waitUntil(15000) {!vm.state.value.busy && vm.state.value.session?.pdf!=null}
        val original=vm.state.value.session!!.base
        compose.onNodeWithText("문자 인식").performScrollTo().performClick();compose.onNodeWithText("영어").performClick()
        compose.onNodeWithText("끝 페이지 · 최대 20장").performTextReplacement("2")
        compose.onNodeWithText("PDF 범위 인식").performScrollTo().performClick()
        compose.waitUntil(45000) {compose.onAllNodesWithText("1쪽 결과").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("2쪽 결과").performScrollTo().performClick()
        compose.onNodeWithText("인식 결과 · 수정 가능").assertTextContains("OFFLINE OCR PAGE 2",substring=true)
        compose.onNodeWithText("PDF에 검색 텍스트 추가").performScrollTo().performClick()
        compose.waitUntil(20000) {!vm.state.value.busy && vm.state.value.session!!.base!=original}
        assertEquals(listOf(0,1),PdfEngine.search(vm.state.value.session!!.base,"OFFLINE"))
        compose.onNodeWithContentDescription("실행 취소").performClick();compose.waitForIdle();assertEquals(original,vm.state.value.session!!.base)
        compose.onNodeWithContentDescription("다시 실행").performClick();compose.waitForIdle();assertEquals(listOf(0,1),PdfEngine.search(vm.state.value.session!!.base,"OFFLINE"))
        compose.onNodeWithText("문자 인식").performScrollTo().performClick();compose.onNodeWithText("영어").performClick();compose.onNodeWithText("PDF 범위 인식").performScrollTo().performClick()
        compose.waitUntil(45000) {compose.onAllNodesWithText("1쪽 결과").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("TXT 문서로 만들기").performScrollTo().performClick()
        compose.waitUntil(15000) {!vm.state.value.busy && vm.state.value.session?.name=="문자 인식.txt"}
        assertTrue(vm.state.value.session!!.edits.text.contains("OFFLINE"))
    }
}
