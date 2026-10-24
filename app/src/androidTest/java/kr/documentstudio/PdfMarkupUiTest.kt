package kr.documentstudio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kr.documentstudio.core.DocumentChange
import kr.documentstudio.data.PdfAnnotations
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class PdfMarkupUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun selectTextAddUnderlineUndoEditDeleteAndHorizontalPagingWork() {
        val vm=ViewModelProvider(compose.activity)[EditorViewModel::class.java]
        val file=File(compose.activity.cacheDir,"tests/${UUID.randomUUID()}.pdf").apply {parentFile!!.mkdirs()}
        PDDocument().use {doc ->val page=PDPage(PDRectangle(400f,300f));doc.addPage(page)
            PDPageContentStream(doc,page).use {it.beginText();it.setFont(PDType1Font.HELVETICA,20f);it.newLineAtOffset(40f,200f);it.showText("ALPHA BETA");it.endText()};doc.save(file)}
        val uri=FileProvider.getUriForFile(compose.activity,"kr.documentstudio.testfiles",file)
        compose.runOnUiThread {vm.open(uri)}
        compose.waitUntil(15000) {!vm.state.value.busy && vm.state.value.session?.pdf!=null}
        val original=vm.state.value.session!!.base
        compose.onNodeWithTag("ribbon-주석").performScrollTo().performClick()
        compose.onNodeWithText("텍스트 선택").performScrollTo().performClick()
        compose.waitUntil(15000) {compose.onAllNodesWithText("주석을 넣을 텍스트 선택").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("주석을 넣을 텍스트 선택").performTextInputSelection(TextRange(6,10))
        compose.onNodeWithText("밑줄").performClick();compose.onNodeWithText("적용").performClick()
        compose.waitUntil(15000) {!vm.state.value.busy && vm.state.value.session!!.base!=original}
        assertEquals("BETA",PdfAnnotations.list(vm.state.value.session!!.base,0).single().text)
        compose.onNodeWithContentDescription("실행 취소").performClick();compose.waitForIdle();assertEquals(original,vm.state.value.session!!.base)
        compose.onNodeWithContentDescription("다시 실행").performClick();compose.waitForIdle()
        compose.onNodeWithText("주석 관리").performScrollTo().performClick()
        compose.onNodeWithText("Underline · BETA").performClick()
        compose.onNodeWithText("주석 내용").performTextReplacement("REVIEW_UI")
        compose.onNodeWithText("적용").performClick()
        compose.waitUntil(15000) {!vm.state.value.busy && PdfAnnotations.list(vm.state.value.session!!.base,0).single().text=="REVIEW_UI"}
        compose.onNodeWithText("주석 관리").performScrollTo().performClick()
        compose.onNodeWithText("Underline · REVIEW_UI").performClick();compose.onNodeWithText("이 주석 삭제").performScrollTo().performClick()
        compose.waitUntil(15000) {!vm.state.value.busy && PdfAnnotations.list(vm.state.value.session!!.base,0).isEmpty()}
        compose.runOnUiThread {vm.change(DocumentChange("pdf.add","0"))}
        compose.waitUntil(15000) {!vm.state.value.busy && vm.state.value.session!!.pdf!!.pages==2}
        compose.onNodeWithTag("ribbon-보기").performScrollTo().performClick()
        compose.onNodeWithText("가로 보기").performScrollTo().performClick()
        compose.onNodeWithContentDescription("PDF 가로 페이지 목록").performTouchInput {swipeLeft()}
        compose.waitUntil(15000) {compose.onAllNodesWithText("2 / 2").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("현재 페이지 필기").performClick();compose.onNodeWithText("2 / 2").assertExists()
    }
}
