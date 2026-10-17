package kr.documentstudio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kr.documentstudio.core.Format
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HangulConversionUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val vm get()=ViewModelProvider(compose.activity)[EditorViewModel::class.java]
    @Test fun cancelAndConvertReportEditingUndoKeepSource() {
        compose.waitUntil(20000) {!vm.state.value.busy}
        compose.runOnUiThread {vm.create(Format.HWP)}
        compose.waitUntil(20000) {!vm.state.value.busy && vm.state.value.session?.format==Format.HWP}
        val source=vm.state.value.session!!;val count=vm.state.value.tabs.size
        compose.runOnUiThread {vm.field(source.sections[0].fields[0].id,"UI 변환 내용")}
        compose.onNodeWithText("HWPX로 변환").performClick()
        compose.onNodeWithText("취소").performClick();assertEquals(count,vm.state.value.tabs.size)
        compose.onNodeWithText("HWPX로 변환").performClick()
        compose.onNodeWithText("새 작업으로 변환").performClick()
        compose.waitUntil(30000) {!vm.state.value.busy && vm.state.value.session?.format==Format.HWPX}
        assertEquals(count+1,vm.state.value.tabs.size);val target=vm.state.value.session!!
        compose.waitUntil(15000) {compose.onAllNodesWithText("변환 시점 점검 결과").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("변환 시점 점검 결과").performClick()
        compose.onNodeWithText("구역 1 · 문단 1",substring=true).assertExists()
        compose.onNodeWithText("닫기").performClick()
        compose.runOnUiThread {vm.field(target.sections[0].fields[0].id,"결과만 수정")}
        assertEquals("UI 변환 내용",vm.state.value.tabs.first {it.id==source.id}.edits.values[source.sections[0].fields[0].id])
        compose.runOnUiThread {vm.undo(target.id)}
        assertTrue(vm.state.value.session!!.edits.values.isEmpty())
    }
}
