package kr.documentstudio

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import kr.documentstudio.core.DocumentChange
import kr.documentstudio.core.Format
import kr.documentstudio.data.DocumentRepository
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Exercises input, navigation, persistence and shortcuts together. */
class EditingWorkflowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val vm get()=ViewModelProvider(compose.activity)[EditorViewModel::class.java]
    private fun create(format: Format) {
        compose.waitUntil(15000) {!vm.state.value.busy}
        val previous=vm.state.value.active
        compose.runOnUiThread {vm.create(format)}
        compose.waitUntil(15000) {!vm.state.value.busy && vm.state.value.active!=previous}
        compose.waitForIdle()
    }
    private fun valueField()=compose.onNodeWithText("값 또는 =수식")
    private fun addressField()=compose.onNode(hasSetTextAction() and hasText("셀 주소"))
    private fun nativeEditor(root: View): EditText? {
        if(root is EditText) return root
        if(root is ViewGroup) for(i in 0 until root.childCount) nativeEditor(root.getChildAt(i))?.let {return it}
        return null
    }
    private fun shortcut(key: Int,shift: Boolean=false) {
        val now=android.os.SystemClock.uptimeMillis()
        compose.activity.window.callback.dispatchKeyShortcutEvent(KeyEvent(now,now,KeyEvent.ACTION_DOWN,key,0,
            KeyEvent.META_CTRL_ON or if(shift) KeyEvent.META_SHIFT_ON else 0))
    }

    @Test fun cellInputSurvivesSelectionAndEachCellHasItsOwnUndoStep() {
        create(Format.XLSX)
        val section=vm.state.value.session!!.sections.first().id
        valueField().performTextReplacement("11")
        addressField().performTextReplacement("B1")
        valueField().performTextReplacement("22")
        compose.runOnUiThread {
            assertEquals("11",vm.state.value.session!!.edits.values["$section#A1"])
            assertEquals("22",vm.state.value.session!!.edits.values["$section#B1"])
        }
        compose.onNodeWithContentDescription("실행 취소").performClick()
        compose.runOnUiThread {
            assertEquals("11",vm.state.value.session!!.edits.values["$section#A1"])
            assertNull(vm.state.value.session!!.edits.values["$section#B1"])
        }
        compose.onNodeWithContentDescription("다시 실행").performClick()
        compose.runOnUiThread {assertEquals("22",vm.state.value.session!!.edits.values["$section#B1"])}
        compose.activityRule.scenario.recreate()
        valueField().assertTextContains("22")
    }

    @Test fun cellInputIsSavedWithoutAnExtraApplyClick() {
        create(Format.XLSX)
        valueField().performTextReplacement("=SUM(12,8)")
        val output=File(compose.activity.cacheDir,"tests/${UUID.randomUUID()}.xlsx").apply {parentFile!!.mkdirs();writeBytes(byteArrayOf())}
        val uri=FileProvider.getUriForFile(compose.activity,"kr.documentstudio.testfiles",output)
        compose.runOnUiThread {vm.save(uri)}
        compose.waitUntil(15000) {!vm.state.value.busy && vm.state.value.session?.uri==uri.toString()}
        assertEquals("=SUM(12,8)",kr.documentstudio.core.PackageDocument.open(output).sections.first().cells["A1"])
        val recovered=runBlocking {DocumentRepository(compose.activity).restore(vm.state.value.active!!)}
        assertEquals("=SUM(12,8)",recovered.edits.values[recovered.sections.first().id+"#A1"])
    }

    @Test fun structureUndoAndNumberFormatRefreshTheSelectedCell() {
        create(Format.XLSX)
        valueField().performTextReplacement("7")
        val section=vm.state.value.session!!.sections.first().id
        compose.runOnUiThread {assertEquals("7",vm.state.value.session!!.edits.values["$section#A1"])}
        compose.onNodeWithText("선택 셀 서식").performClick()
        compose.onNodeWithText("숫자 형식 (0.00, #,##0 등)").performScrollTo().performTextReplacement("0.00")
        compose.onNodeWithText("적용").performClick()
        compose.onNodeWithContentDescription("셀로 이동").performClick()
        compose.runOnUiThread {assertEquals("0.00",vm.state.value.session!!.edits.styles["$section#A1"]?.get("numberFormat"))}
        try {
            compose.waitUntil(15000) {compose.onAllNodesWithText("7.00").fetchSemanticsNodes().isNotEmpty()}
        } catch(e: Throwable) {
            val session=vm.state.value.session!!
            throw AssertionError("Cell=${session.edits.values["$section#A1"]}, style=${session.edits.styles["$section#A1"]}, formatted=${kr.documentstudio.core.CellFormatting.format("7","0.00")}\n"+compose.onRoot().printToString(),e)
        }
        compose.onNodeWithText("7.00").assertIsDisplayed()
        compose.runOnUiThread {vm.change(DocumentChange("sheet.clearRange",section,mapOf("range" to "A1")))}
        compose.waitUntil(15000) {!vm.state.value.busy && vm.state.value.session!!.revision>0}
        valueField().assertTextEquals("값 또는 =수식","")
        compose.onNodeWithContentDescription("실행 취소").performClick()
        valueField().assertTextContains("7")
    }

    @Test fun recentAndProviderReopenPreserveAnOpenTabsUndoHistory() {
        create(Format.TXT)
        val id=vm.state.value.active!!
        val output=File(compose.activity.cacheDir,"tests/${UUID.randomUUID()}.txt").apply {parentFile!!.mkdirs();writeBytes(byteArrayOf())}
        val uri=FileProvider.getUriForFile(compose.activity,"kr.documentstudio.testfiles",output)
        compose.runOnUiThread {vm.save(uri)}
        compose.waitUntil(15000) {!vm.state.value.busy && vm.state.value.session!!.uri==uri.toString()}
        compose.runOnUiThread {vm.text("복구와 실행 취소")}
        compose.onNodeWithContentDescription("홈").performClick()
        compose.runOnUiThread {vm.restore(id)}
        compose.waitUntil(15000) {!vm.state.value.busy && vm.state.value.active==id}
        compose.runOnUiThread {vm.open(uri)}
        compose.waitUntil(15000) {!vm.state.value.busy}
        compose.onNodeWithContentDescription("실행 취소").assertIsEnabled().performClick()
        compose.runOnUiThread {assertEquals("",vm.state.value.session!!.edits.text)}
    }

    @Test fun focusedTextEditorUsesDocumentShortcutsAndBusyCommandsKeepTheSnapshot() {
        create(Format.TXT)
        compose.runOnUiThread {
            val editor=nativeEditor(compose.activity.window.decorView)!!
            editor.requestFocus();editor.setText("저장해야 할 본문")
            shortcut(KeyEvent.KEYCODE_Z)
            assertEquals("",vm.state.value.session!!.edits.text)
            shortcut(KeyEvent.KEYCODE_Z,shift=true)
            assertEquals("저장해야 할 본문",vm.state.value.session!!.edits.text)
            vm.change(DocumentChange("unsupported"))
            assertTrue(vm.state.value.busy)
            shortcut(KeyEvent.KEYCODE_Z)
            vm.home()
            assertNotNull(vm.state.value.session)
            assertEquals("저장해야 할 본문",vm.state.value.session!!.edits.text)
        }
        compose.waitUntil(15000) {!vm.state.value.busy}
        val recovered=runBlocking {DocumentRepository(compose.activity).restore(vm.state.value.active!!)}
        assertEquals("저장해야 할 본문",recovered.edits.text)
    }

    @Test fun addressNavigationShowsTheTargetAndRespectsLegacySheetLimits() {
        create(Format.XLSX)
        addressField().performTextReplacement("XFD1048576")
        compose.onNodeWithContentDescription("셀로 이동").performClick()
        compose.onNodeWithText("XFD").assertIsDisplayed()
        compose.onNodeWithText("1048576").assertIsDisplayed()
        valueField().assertIsEnabled()
        create(Format.XLS)
        addressField().performTextReplacement("IW65536")
        valueField().assertIsNotEnabled()
        addressField().performTextReplacement("IV65536")
        compose.onNodeWithContentDescription("셀로 이동").performClick()
        compose.onNodeWithText("IV").assertIsDisplayed()
        compose.onNodeWithText("65536").assertIsDisplayed()
        valueField().assertIsEnabled()
    }

    @Test fun failedOfficeLaunchOffersRecoveryAndAllowsAnotherAttempt() {
        create(Format.DOCX)
        compose.runOnUiThread {vm.field(vm.state.value.session!!.sections.first().fields.first().id,"실패 전 편집")}
        compose.runOnUiThread {vm.openOffice {_,_->error("Injected launch failure")}}
        compose.waitUntil(15000) {!vm.state.value.busy && vm.state.value.session!!.recoveryWarning!=null}
        compose.onNodeWithContentDescription("실행 취소").performClick()
        compose.runOnUiThread {assertNotNull(vm.state.value.session!!.recoveryWarning)}
        compose.onNodeWithContentDescription("문서 메뉴").performClick()
        compose.onNodeWithText("정밀 편집 복구본 보관 후 다시 시작").performClick()
        compose.waitUntil(15000) {!vm.state.value.busy && vm.state.value.session!!.recoveryWarning==null}
        assertFalse(File(vm.state.value.session!!.base.parentFile,"office-pending.json").exists())
        var launched=false
        compose.runOnUiThread {vm.openOffice {_,_->launched=true;error("Second injected launch failure")}}
        compose.waitUntil(15000) {launched && !vm.state.value.busy}
    }

    @Test fun rapidSettingsChangesRestoreTheLastSelectionInANewViewModel() {
        create(Format.TXT)
        val original=vm.state.value.settings
        val latest=original.copy(penWidth=0.0215f,penColor=0xFF315FBB.toInt(),partialEraser=true)
        val preferences=compose.activity.getSharedPreferences("settings",0)
        val store=androidx.lifecycle.ViewModelStore()
        try {
            compose.runOnUiThread {
                repeat(40) {i -> vm.settings(original.copy(penWidth=0.002f+i*0.0005f,penColor=0xFF294E46.toInt()+i))}
                vm.settings(latest)
            }
            compose.waitUntil(15000) {preferences.getFloat("penWidth",0f)==latest.penWidth && preferences.getInt("penColor",0)==latest.penColor && preferences.getBoolean("partialEraser",false)}
            lateinit var restored: EditorViewModel
            compose.runOnUiThread {restored=ViewModelProvider(store,ViewModelProvider.AndroidViewModelFactory(compose.activity.application))[EditorViewModel::class.java]}
            compose.waitUntil(15000) {restored.state.value.settings==latest}
        } finally {
            compose.runOnUiThread {store.clear();vm.settings(original)}
            compose.waitUntil(15000) {preferences.getFloat("penWidth",0f)==original.penWidth && preferences.getInt("penColor",0)==original.penColor && preferences.getBoolean("partialEraser",false)==original.partialEraser}
        }
    }
}
