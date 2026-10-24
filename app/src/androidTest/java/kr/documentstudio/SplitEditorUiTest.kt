package kr.documentstudio

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SplitEditorUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun edits(view: View): List<EditText> = if(view is EditText) listOf(view) else if(view is ViewGroup) (0 until view.childCount).flatMap {edits(view.getChildAt(it))} else emptyList()
    @Test fun twoVisibleEditorsKeepInputUndoRecreationAndCloseIndependent() {
        compose.runOnUiThread {compose.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
        compose.waitUntil(15000) {compose.activity.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE}
        try {
            val vm=ViewModelProvider(compose.activity)[EditorViewModel::class.java]
            repeat(2) {index ->
                if(index>0) compose.onNodeWithContentDescription("문서 메뉴").performClick()
                compose.onNodeWithText("새 문서",useUnmergedTree=true).performClick()
                compose.onNodeWithText("텍스트  .txt").performScrollTo().performClick()
                compose.waitUntil(15000) {!vm.state.value.busy && vm.state.value.tabs.size==index+1}
                compose.waitForIdle()
            }
            val left=vm.state.value.active!!;val right=vm.state.value.tabs.first {it.id!=left}.id
            compose.onNodeWithContentDescription("문서 메뉴").performClick()
            compose.onNodeWithText("두 문서 나란히 편집").performClick()
            compose.onNodeWithTag("document-pane-$left").assertIsDisplayed()
            compose.onNodeWithTag("document-pane-$right").assertIsDisplayed()
            compose.runOnUiThread {
                val views=edits(compose.activity.window.decorView);assertEquals(2,views.size)
                views[0].requestFocus();views[0].setText("LEFT_DOC")
                views[1].requestFocus();views[1].setText("RIGHT_DOC")
            }
            fun text(id: String)=vm.state.value.tabs.first {it.id==id}.edits.text
            compose.waitForIdle();assertEquals("LEFT_DOC",text(left));assertEquals("RIGHT_DOC",text(right))
            compose.onNodeWithContentDescription("왼쪽 실행 취소").performClick()
            compose.waitForIdle();assertEquals("",text(left));assertEquals("RIGHT_DOC",text(right))
            compose.onNodeWithContentDescription("왼쪽 다시 실행").performClick()
            compose.activityRule.scenario.recreate();compose.waitForIdle()
            compose.runOnUiThread {assertEquals(listOf("LEFT_DOC","RIGHT_DOC"),edits(compose.activity.window.decorView).map {it.text.toString()})}
            compose.runOnUiThread {vm.close(left)}
            compose.waitUntil(15000) {!vm.state.value.busy && vm.state.value.tabs.size==1}
            compose.waitForIdle()
            compose.runOnUiThread {assertEquals("RIGHT_DOC",edits(compose.activity.window.decorView).single().text.toString())}
        } finally {compose.runOnUiThread {compose.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED}}
    }
}
