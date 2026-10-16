package kr.documentstudio

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kr.documentstudio.core.*
import kr.documentstudio.ui.InkView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class InkAdvancedUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun ink(v: View): InkView?=if(v is InkView) v else if(v is ViewGroup) (0 until v.childCount).firstNotNullOfOrNull {ink(v.getChildAt(it))} else null
    @Test fun dockCollapsePresetsLayersAndPaperWorkOnActualDrawingPage() {
        val vm=ViewModelProvider(compose.activity)[EditorViewModel::class.java]
        compose.runOnUiThread {vm.settings(Settings());vm.create(Format.TXT)}
        compose.waitUntil(15000) {!vm.state.value.busy && vm.state.value.session?.format==Format.TXT}
        compose.onNodeWithText("필기 페이지").performClick()
        compose.onNodeWithText("격자").performScrollTo().performClick();assertEquals(Paper.GRID,vm.state.value.session!!.edits.paper)
        compose.onNodeWithText("설정").performClick()
        compose.onNodeWithText("펜 이름").performScrollTo().performTextInput("회의펜")
        compose.onNodeWithText("현재 설정 저장").performScrollTo().performClick()
        compose.onNodeWithText("닫기").performClick();assertEquals("회의펜",vm.state.value.settings.presets.single().name)
        compose.onNodeWithText("레이어").performClick()
        compose.onNodeWithText("레이어 이름").performScrollTo().performTextInput("검토 필기")
        compose.onNodeWithText("새 레이어").performScrollTo().performClick();compose.onNodeWithText("닫기").performClick()
        compose.onNodeWithText("도구 위로").performClick();compose.onNodeWithText("필기 도구 접기").performClick()
        compose.waitUntil(10000) {ink(compose.activity.window.decorView)!=null}
        compose.runOnUiThread {
            val view=ink(compose.activity.window.decorView)!!;val time=android.os.SystemClock.uptimeMillis()
            for((i,action) in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP).withIndex()) {
                MotionEvent.obtain(time,time+i*10,action,view.width*(.2f+i*.1f),view.height*.3f,0).also {view.dispatchTouchEvent(it);it.recycle()}
            }
        }
        compose.waitForIdle();val s=vm.state.value.session!!
        assertEquals(s.edits.layers.last().id,s.edits.strokes.single().layerId)
        compose.onNodeWithText("레이어").performClick();compose.onAllNodesWithText("숨기기").onLast().performClick();compose.onNodeWithText("닫기").performClick()
        compose.runOnUiThread {assertTrue(ink(compose.activity.window.decorView)!!.strokes.isEmpty())}
        compose.onNodeWithText("필기 도구 펼치기").performClick();compose.onNodeWithText("도구 아래로").performClick()
        assertFalse(vm.state.value.settings.toolbarTop);assertFalse(vm.state.value.settings.toolbarCollapsed)
    }
}
