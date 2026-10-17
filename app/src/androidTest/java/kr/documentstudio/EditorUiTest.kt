package kr.documentstudio

import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.EditText
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kr.documentstudio.ui.InkView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before

class EditorUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Before fun resetPersistedInputPreferences() {
        val vm=androidx.lifecycle.ViewModelProvider(compose.activity)[EditorViewModel::class.java]
        compose.runOnUiThread {vm.settings(Settings())}
    }
    private fun awaitReady() {
        val vm=androidx.lifecycle.ViewModelProvider(compose.activity)[EditorViewModel::class.java]
        compose.waitUntil(15000) {!vm.state.value.busy && vm.state.value.session!=null}
        compose.waitForIdle()
    }
    private fun <T: View> find(root: View,type: Class<T>): T? {
        if(type.isInstance(root)) return type.cast(root)
        if(root is ViewGroup) for(i in 0 until root.childCount) {find(root.getChildAt(i),type)?.let {return it}}
        return null
    }
    private inline fun <reified T: View> find(root: View): T? = find(root,T::class.java)
    @Test fun createEditDrawUndoAndRotationPreserveWork() {
        compose.onNodeWithText("새 문서",useUnmergedTree=true).performClick()
        compose.onNodeWithText("텍스트  .txt").performClick()
        compose.waitUntil(15000) {compose.onAllNodesWithText("텍스트 편집").fetchSemanticsNodes().isNotEmpty()}
        awaitReady()
        compose.runOnUiThread {val edit=find<EditText>(compose.activity.window.decorView)!!;edit.setText("화면 회전 후에도 유지\n두 번째 줄")}
        compose.onNodeWithText("필기 페이지").performClick()
        compose.waitForIdle()
        compose.runOnUiThread {
            val ink=find<InkView>(compose.activity.window.decorView)!!
            val time=android.os.SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN to 0.2f,MotionEvent.ACTION_MOVE to 0.5f,MotionEvent.ACTION_UP to 0.7f).forEachIndexed {i,(action,x) ->
                val event=MotionEvent.obtain(time,time+i*16,action,ink.width*x,ink.height*0.4f,0)
                ink.dispatchTouchEvent(event);event.recycle()
            }
        }
        compose.waitForIdle()
        compose.runOnUiThread {assertEquals(1,find<InkView>(compose.activity.window.decorView)!!.strokes.size)}
        compose.onNodeWithContentDescription("실행 취소").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("다시 실행").assertIsEnabled().performClick()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithText("자동 복구 저장 실패",substring=true).assertDoesNotExist()
        compose.onNodeWithText("텍스트 편집").performClick()
        compose.waitForIdle()
        compose.runOnUiThread {assertEquals("화면 회전 후에도 유지\n두 번째 줄",find<EditText>(compose.activity.window.decorView)!!.text.toString())}
        compose.onNodeWithContentDescription("홈").performClick()
        compose.waitUntil(15000) {compose.onAllNodesWithText("최근 작업 · 복구").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("최근 작업 · 복구").assertExists()
    }
    @Test fun partialEraserGestureUndoRedoAndSettingsSurviveRecreation() {
        compose.onNodeWithText("새 문서",useUnmergedTree=true).performClick()
        compose.onNodeWithText("텍스트  .txt").performClick()
        compose.waitUntil(15000) {compose.onAllNodesWithText("텍스트 편집").fetchSemanticsNodes().isNotEmpty()}
        awaitReady()
        compose.onNodeWithText("필기 페이지").performClick();compose.waitForIdle()
        val vm=androidx.lifecycle.ViewModelProvider(compose.activity)[EditorViewModel::class.java]
        val original=kr.documentstudio.core.Stroke(anchor="text",tool=kr.documentstudio.core.InkTool.PEN,color=android.graphics.Color.BLUE,width=0.006f,
            points=listOf(kr.documentstudio.core.InkPoint(0.1f,0.5f),kr.documentstudio.core.InkPoint(0.9f,0.5f)))
        compose.runOnUiThread {vm.addStroke(original);vm.settings(vm.state.value.settings.copy(penTool="ERASER",partialEraser=false,stylusOnly=false))}
        compose.onNodeWithText("지나간 부분만").performClick();compose.waitForIdle()
        compose.runOnUiThread {
            val ink=find<InkView>(compose.activity.window.decorView)!!
            val time=android.os.SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN to 0.2f,MotionEvent.ACTION_UP to 0.8f).forEachIndexed {i,(action,y)->
                val event=MotionEvent.obtain(time,time+i*16,action,ink.width*0.5f,ink.height*y,0)
                ink.dispatchTouchEvent(event);event.recycle()
            }
        }
        compose.waitUntil(15000) {!vm.state.value.busy && vm.state.value.session!!.edits.strokes.size==2}
        val fragments=vm.state.value.session!!.edits.strokes
        compose.onNodeWithContentDescription("실행 취소").performClick()
        compose.runOnUiThread {assertEquals(listOf(original),vm.state.value.session!!.edits.strokes)}
        compose.onNodeWithContentDescription("다시 실행").performClick()
        compose.runOnUiThread {assertEquals(fragments,vm.state.value.session!!.edits.strokes)}
        compose.activityRule.scenario.recreate();compose.waitForIdle()
        compose.runOnUiThread {assertTrue(vm.state.value.settings.partialEraser);assertEquals(fragments,vm.state.value.session!!.edits.strokes)}
        compose.runOnUiThread {vm.settings(vm.state.value.settings.copy(penTool="PEN",partialEraser=false))}
    }
    @Test fun continuousPdfScrollNavigationRecreationAndPageInkStayConnected() {
        compose.onNodeWithText("새 문서",useUnmergedTree=true).performClick()
        compose.onNodeWithText("PDF  .pdf").performClick()
        val vm=androidx.lifecycle.ViewModelProvider(compose.activity)[EditorViewModel::class.java]
        compose.waitUntil(15000) {vm.state.value.session?.pdf!=null}
        awaitReady()
        repeat(2) {i ->
            compose.runOnUiThread {vm.change(kr.documentstudio.core.DocumentChange("pdf.add","0"))}
            compose.waitUntil(15000) {!vm.state.value.busy && vm.state.value.session!!.pdf!!.pages==i+2}
        }
        val original=kr.documentstudio.core.Stroke(anchor="pdf:2",tool=kr.documentstudio.core.InkTool.PEN,color=android.graphics.Color.BLUE,width=0.006f,
            points=listOf(kr.documentstudio.core.InkPoint(0.2f,0.5f),kr.documentstudio.core.InkPoint(0.8f,0.5f)))
        compose.runOnUiThread {vm.addStroke(original)}
        compose.onNodeWithText("연속 보기").performClick()
        compose.onNodeWithContentDescription("PDF 연속 페이지 목록").performScrollToIndex(2)
        compose.waitUntil(15000) {compose.onAllNodesWithContentDescription("연속 보기 3페이지").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("3 / 3").assertExists()
        compose.onNodeWithContentDescription("이전 페이지").performClick()
        compose.waitUntil(15000) {compose.onAllNodesWithText("2 / 3").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("다음 페이지").performClick()
        compose.waitUntil(15000) {compose.onAllNodesWithText("3 / 3").fetchSemanticsNodes().isNotEmpty()}
        compose.activityRule.scenario.recreate();compose.waitForIdle()
        compose.onNodeWithText("연속 보기").assertIsSelected()
        compose.onNodeWithContentDescription("PDF 연속 페이지 목록").performScrollToIndex(2)
        compose.onNodeWithText("3페이지 필기").performClick();compose.waitForIdle()
        compose.onNodeWithText("3 / 3").assertExists()
        compose.runOnUiThread {val ink=find<InkView>(compose.activity.window.decorView)!!;assertEquals("pdf:2",ink.anchor);assertEquals(listOf(original),ink.strokes)}
    }
    @Test fun structuralWordUndoRedoAndRecreationUseMatchingBaseline() {
        compose.onNodeWithText("새 문서",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Word  .docx").performClick()
        compose.waitUntil(15000) {compose.onAllNodesWithText("삽입·문서 도구").fetchSemanticsNodes().isNotEmpty()}
        awaitReady()
        compose.onNodeWithText("삽입·문서 도구").performClick();compose.onNodeWithText("문단 추가").performScrollTo().performClick()
        compose.onNodeWithText("새 문단").performTextReplacement("구조 변경 복구")
        compose.onNodeWithText("적용").performClick()
        fun fields(): Int=androidx.lifecycle.ViewModelProvider(compose.activity)[EditorViewModel::class.java].state.value.session!!.sections.first().fields.size
        compose.waitUntil(15000) {fields()==3};awaitReady();compose.onNodeWithContentDescription("실행 취소").performClick();compose.waitUntil {fields()==2}
        compose.onNodeWithContentDescription("다시 실행").performClick();compose.waitUntil {fields()==3}
        compose.activityRule.scenario.recreate();compose.waitForIdle();assertEquals(3,fields())
    }
    @Test fun slidePreviewAndPresentationControlsFitTheWindow() {
        compose.onNodeWithText("새 문서",useUnmergedTree=true).performClick()
        compose.onNodeWithText("PowerPoint  .pptx").performScrollTo().performClick()
        compose.waitUntil(15000) {compose.onAllNodesWithText("개체 미리보기").fetchSemanticsNodes().isNotEmpty()}
        awaitReady()
        compose.onNodeWithText("개체 미리보기").performScrollTo().performClick()
        compose.waitUntil(15000) {compose.onAllNodesWithText("슬라이드 쇼").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("슬라이드 쇼").assertIsDisplayed().performClick()
        compose.onNodeWithText("종료").assertIsDisplayed().performClick()
        compose.onNodeWithText("슬라이드 쇼").assertIsDisplayed()
    }
    @Test fun spreadsheetClipboardStructureUndoAndRecreationWorkThroughUi() {
        compose.onNodeWithText("새 문서",useUnmergedTree=true).performClick();compose.onNodeWithText("Excel  .xlsx").performScrollTo().performClick()
        compose.waitUntil(15000) {compose.onAllNodesWithText("붙여넣기").fetchSemanticsNodes().isNotEmpty()}
        awaitReady()
        compose.runOnUiThread {(compose.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("test","1\t=A1*2\n3\t=A2*2"))}
        fun cell(address: String)=androidx.lifecycle.ViewModelProvider(compose.activity)[EditorViewModel::class.java].state.value.session!!.sections[0].cells[address]
        compose.onNodeWithText("붙여넣기").performClick();compose.waitUntil(15000) {cell("B2")=="=A2*2"};awaitReady()
        compose.onNodeWithContentDescription("실행 취소").performClick();compose.waitUntil {cell("A1")==null}
        compose.onNodeWithContentDescription("다시 실행").performClick();compose.waitUntil {cell("A1")=="1"}
        compose.onNodeWithText("삽입·문서 도구").performClick();compose.onNodeWithText("행 삽입").performScrollTo().performClick();compose.onNodeWithText("적용").performClick()
        compose.waitUntil(15000) {cell("B3")=="=A3*2"};awaitReady();compose.activityRule.scenario.recreate();compose.waitForIdle();assertEquals("=A3*2",cell("B3"))
    }
}
