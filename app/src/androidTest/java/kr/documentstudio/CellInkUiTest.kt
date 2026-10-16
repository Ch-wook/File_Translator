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

class CellInkUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun ink(v: View): InkView?=if(v is InkView) v else if(v is ViewGroup) (0 until v.childCount).firstNotNullOfOrNull {ink(v.getChildAt(it))} else null
    private fun gesture(x: Float,y: Float,dx: Float) {compose.runOnUiThread {
        val v=ink(compose.activity.window.decorView)!!;val time=android.os.SystemClock.uptimeMillis()
        for((i,action) in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP).withIndex()) MotionEvent.obtain(time,time+i*10,action,(x+dx*i/2)*v.width,y*v.height,0).also {v.dispatchTouchEvent(it);it.recycle()}
    }}
    @Test fun drawingViewportMovesCellInkAndProjectedEraserUndoPreservesOwner() {
        val vm=ViewModelProvider(compose.activity)[EditorViewModel::class.java]
        compose.runOnUiThread {vm.settings(Settings(toolbarCollapsed=true));vm.create(Format.XLSX)}
        compose.waitUntil(15000) {!vm.state.value.busy && vm.state.value.session?.format==Format.XLSX}
        compose.onNodeWithText("필기 페이지").performClick()
        compose.waitUntil(15000) {var ready=false;compose.runOnUiThread {ready=ink(compose.activity.window.decorView)?.isEnabled==true};ready}
        gesture(.26f,.25f,.04f);compose.waitForIdle()
        val stored=vm.state.value.session!!.edits.strokes.single();assertEquals("B3",stored.cell!!.address)
        compose.onNodeWithText("필기 시작 셀").performTextReplacement("B3");compose.onNodeWithText("셀로 이동").performClick()
        compose.waitUntil(15000) {var moved=false;compose.runOnUiThread {moved=ink(compose.activity.window.decorView)?.strokes?.firstOrNull()?.points?.firstOrNull()?.x?.let {it<.2f}==true};moved}
        var x=0f;var y=0f
        compose.runOnUiThread {val p=ink(compose.activity.window.decorView)!!.strokes.single().points; x=(p.first().x+p.last().x)/2;y=p.first().y;vm.settings(vm.state.value.settings.copy(penTool=InkTool.ERASER.name))}
        compose.waitForIdle();gesture(x,y,0f)
        compose.waitUntil(15000) {!vm.state.value.busy && vm.state.value.session!!.edits.strokes.isEmpty()}
        compose.onNodeWithContentDescription("실행 취소").performClick();compose.waitForIdle();assertEquals(stored,vm.state.value.session!!.edits.strokes.single())
    }
}
