package kr.documentstudio

import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.webkit.WebView
import android.widget.EditText
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kr.documentstudio.core.Format
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MarkdownUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun <T: View> find(v: View,type: Class<T>): T? {
        if(type.isInstance(v)) return type.cast(v)
        if(v is ViewGroup) for(i in 0 until v.childCount) find(v.getChildAt(i),type)?.let {return it}
        return null
    }
    @Test fun sourceToolbarAndPreviewCheckboxUpdateHistoryWithoutJavascript() {
        val vm=ViewModelProvider(compose.activity)[EditorViewModel::class.java]
        compose.runOnUiThread {vm.create(Format.MD)};compose.waitUntil(15000) {!vm.state.value.busy && vm.state.value.session?.format==Format.MD}
        compose.onNodeWithTag("edit-document").performClick();compose.waitForIdle()
        compose.runOnUiThread {vm.text("- [ ] 할 일\n\n한글")};compose.waitForIdle()
        compose.runOnUiThread {find(compose.activity.window.decorView,EditText::class.java)!!.apply {requestFocus();setSelection(text.length-2,text.length)}}
        compose.onNodeWithText("글자").performClick()
        compose.onNodeWithText("굵게").performScrollTo().performClick();compose.waitForIdle();assertTrue(vm.state.value.session!!.edits.text.endsWith("**한글**"))
        compose.onNodeWithText("미리보기").performScrollTo().performClick()
        compose.waitUntil(15000) {var loaded=false;compose.runOnUiThread {loaded=find(compose.activity.window.decorView,WebView::class.java)?.progress==100};loaded}
        compose.runOnUiThread {val w=find(compose.activity.window.decorView,WebView::class.java)!!;assertFalse(w.settings.javaScriptEnabled);assertTrue(w.settings.blockNetworkLoads)}
        val automation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        fun checkbox(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
            if(node==null) return null
            if(node.contentDescription?.contains("완료 표시")==true || node.text?.contains("완료 표시")==true) return node
            for(i in 0 until node.childCount) checkbox(node.getChild(i))?.let {return it}
            return null
        }
        var target: android.view.accessibility.AccessibilityNodeInfo?=null
        compose.waitUntil(15000) {NativeUiActions.refresh();target=checkbox(automation.rootInActiveWindow);target!=null}
        assertTrue(target!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        compose.waitUntil(10000) {vm.state.value.session!!.edits.text.startsWith("- [x]")}
        compose.onNodeWithContentDescription("실행 취소").performClick();compose.waitForIdle();assertTrue(vm.state.value.session!!.edits.text.startsWith("- [ ]"))
        compose.onNodeWithContentDescription("다시 실행").performClick();compose.waitForIdle();assertTrue(vm.state.value.session!!.edits.text.startsWith("- [x]"))
        // Returning from preview must attach the latest source, not mutate an old detached EditText.
        compose.onNodeWithText("글자").performClick();compose.waitForIdle()
        compose.onNodeWithText("굵게").performScrollTo().performClick();compose.waitForIdle()
        assertTrue("Formatting after preview cannot revert the checked task",vm.state.value.session!!.edits.text.startsWith("- [x]"))
        assertTrue(vm.state.value.session!!.edits.text.endsWith("한글"))
    }
}
