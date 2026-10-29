package kr.documentstudio

import android.content.Context
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kr.documentstudio.data.SignatureStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class QualityUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val vm get()=ViewModelProvider(compose.activity)[EditorViewModel::class.java]
    private fun nightSetting(device: UiDevice): String {
        val value=Regex("(?i)night mode: *(yes|no|auto)").find(device.executeShellCommand("cmd uimode night"))?.groupValues?.get(1)?.lowercase()
        return value ?: if(compose.activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK==android.content.res.Configuration.UI_MODE_NIGHT_YES)"yes" else "no"
    }
    private fun restoreAppearance(device: UiDevice,scale: Float,night: String,mask: Int) {
        device.executeShellCommand("settings put system font_scale "+scale)
        device.executeShellCommand("cmd uimode night "+night)
        compose.waitUntil(30000){kotlin.math.abs(compose.activity.resources.configuration.fontScale-scale)<.02f &&
            compose.activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK==mask}
        compose.waitForIdle()
    }
    private fun awaitRecoveryRows() {
        try {compose.waitUntil(30000){compose.onAllNodesWithText("이전 정상 서명 보관함 복원…").fetchSemanticsNodes().size==1}}
        catch(error: Throwable) {
            fun describe(node: androidx.compose.ui.semantics.SemanticsNode): String=node.config.toString()+"\n"+node.children.joinToString("\n"){describe(it)}
            val tree=compose.onAllNodes(isRoot(),useUnmergedTree=true).fetchSemanticsNodes().joinToString("\n"){describe(it)}
            File(compose.activity.getExternalFilesDir(null),"native-common-ui/quality-ui-failure.txt").apply {parentFile!!.mkdirs();writeText(tree)}
            val threads=Thread.getAllStackTraces().entries.joinToString("\n\n"){(thread,stack)->thread.name+" state="+thread.state+"\n"+stack.joinToString("\n")}
            File(compose.activity.getExternalFilesDir(null),"native-common-ui/quality-ui-threads.txt").apply {parentFile!!.mkdirs();writeText(threads)}
            println("QUALITY_RECOVERY_UI_FAILURE "+tree.take(16000));throw error
        }
    }
    @Test fun recoveryShowsDamagedSignaturesAndRequiresConfirmationBeforeRestoring() {
        val context=compose.activity
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val oldScale=context.resources.configuration.fontScale
        val oldNight=nightSetting(device)
        val oldMask=context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val files=listOf("signatures.json","signatures.json.bak","signatures-last-good.json")
        val before=files.associateWith {name->File(context.filesDir,name).takeIf {it.isFile}?.readBytes()}
        try {
            files.forEach {File(context.filesDir,it).delete()}
            SignatureStore(context).write("[]");SignatureStore(context).write("[]")
            File(context.filesDir,"signatures.json").writeText("damaged private signature fixture")
            assertTrue("A valid previous fixture must exist before opening the UI",SignatureStore(context).canRestorePrevious())
            compose.waitUntil(30000){!vm.state.value.busy}
            compose.onNodeWithContentDescription("저장 복구").performClick()
            awaitRecoveryRows()
            val activityBefore=compose.activity
            val newNight=if(oldMask==android.content.res.Configuration.UI_MODE_NIGHT_YES)"no" else "yes"
            device.executeShellCommand("cmd uimode night "+newNight)
            compose.waitUntil(30000){compose.activity!==activityBefore &&
                compose.activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK!=oldMask}
            awaitRecoveryRows()
            compose.onNodeWithText("이전 정상 서명 보관함 복원…").performScrollTo().assertIsDisplayed()
            assertEquals("damaged private signature fixture",File(context.filesDir,"signatures.json").readText())
            compose.onNodeWithText("이전 정상 서명 보관함 복원…").performClick()
            compose.onNodeWithText("이전 서명 보관함으로 바꿀까요?").assertIsDisplayed()
            compose.onNodeWithText("취소").performClick()
            assertEquals("damaged private signature fixture",File(context.filesDir,"signatures.json").readText())
            compose.onNodeWithText("이전 정상 서명 보관함 복원…").performClick()
            compose.onNodeWithText("복원",useUnmergedTree=true).performClick()
            compose.waitUntil(15000){runCatching {File(context.filesDir,"signatures.json").readText()=="[]"}.getOrDefault(false)}
            assertTrue(File(context.filesDir,"signature-recovery").listFiles().orEmpty().any {it.readText()=="damaged private signature fixture"})
            compose.waitUntil(15000){runCatching {compose.onNodeWithText("닫기").assertIsEnabled();true}.getOrDefault(false)}
            compose.onNodeWithText("닫기").performClick()
        } finally {restoreAppearance(device,oldScale,oldNight,oldMask);before.forEach {(name,bytes)->File(context.filesDir,name).let {if(bytes==null)it.delete() else it.writeBytes(bytes)}}}
    }
    @Test fun localDiagnosticsDoNotExposeTheExceptionMessageOrDocumentUri() {
        val secret="content://private-contract/김사용자?token=do-not-log"
        kr.documentstudio.data.SafeDiagnostics.record(compose.activity,java.io.IOException(secret))
        val result=kr.documentstudio.data.SafeDiagnostics.text(compose.activity)
        assertFalse(result.contains(secret));assertFalse(result.contains("김사용자"));assertFalse(result.contains("do-not-log"))
        assertTrue(result.contains("code=IO"))
    }
    @Test fun homeAdaptsToSystemDarkModeAndLargeTextWithoutReducingTouchTargets() {
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val oldScale=compose.activity.resources.configuration.fontScale
        val oldNight=nightSetting(device)
        val oldMask=compose.activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        try {
            device.executeShellCommand("cmd uimode night yes")
            device.executeShellCommand("settings put system font_scale 1.6")
            compose.waitUntil(30000){compose.activity.resources.configuration.fontScale>1.5f &&
                compose.activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK==android.content.res.Configuration.UI_MODE_NIGHT_YES}
            compose.waitForIdle()
            val pixels=compose.onRoot().captureToImage().toPixelMap()
            assertTrue("native background must follow system dark mode",pixels[1,pixels.height/2].luminance()<.15f)
            val settings=compose.onNodeWithTag("home-settings-button").fetchSemanticsNode().boundsInRoot
            val density=compose.activity.resources.displayMetrics.density
            assertTrue("settings width="+settings.width/density,settings.width/density>=47f);assertTrue("settings height="+settings.height/density,settings.height/density>=47f)
            compose.onNodeWithTag("new-docx").performScrollTo().assertIsDisplayed()
            val word=compose.onNodeWithTag("new-docx").fetchSemanticsNode().boundsInRoot
            val sheet=compose.onNodeWithTag("new-xlsx").fetchSemanticsNode().boundsInRoot
            assertEquals(word.top,sheet.top,2f)
            assertTrue(word.width>pixels.width*.35f)
            compose.onNodeWithTag("new-hwpx").performScrollTo().assertIsDisplayed()
        } finally {
            restoreAppearance(device,oldScale,oldNight,oldMask)
        }
    }
}
