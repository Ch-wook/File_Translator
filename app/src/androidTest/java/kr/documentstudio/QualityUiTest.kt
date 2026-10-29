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
    @Test fun recoveryShowsDamagedSignaturesAndRequiresConfirmationBeforeRestoring() {
        val context=compose.activity
        val files=listOf("signatures.json","signatures.json.bak","signatures-last-good.json")
        val before=files.associateWith {name->File(context.filesDir,name).takeIf {it.isFile}?.readBytes()}
        try {
            files.forEach {File(context.filesDir,it).delete()}
            SignatureStore(context).write("[]");SignatureStore(context).write("[]")
            File(context.filesDir,"signatures.json").writeText("damaged private signature fixture")
            compose.waitUntil(30000){!vm.state.value.busy}
            compose.onNodeWithContentDescription("저장 복구").performClick()
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
        } finally {before.forEach {(name,bytes)->File(context.filesDir,name).let {if(bytes==null)it.delete() else it.writeBytes(bytes)}}}
    }
    @Test fun homeAdaptsToSystemDarkModeAndLargeTextWithoutReducingTouchTargets() {
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val oldScale=device.executeShellCommand("settings get system font_scale").trim().toFloatOrNull() ?: 1f
        try {
            device.executeShellCommand("cmd uimode night yes")
            device.executeShellCommand("settings put system font_scale 1.6")
            compose.waitUntil(30000){compose.activity.resources.configuration.fontScale>1.5f &&
                compose.activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK==android.content.res.Configuration.UI_MODE_NIGHT_YES}
            compose.waitForIdle()
            val pixels=compose.onRoot().captureToImage().toPixelMap()
            assertTrue("native background must follow system dark mode",pixels[1,pixels.height/2].luminance()<.15f)
            val settings=compose.onNodeWithContentDescription("설정").fetchSemanticsNode().boundsInRoot
            val density=compose.activity.resources.displayMetrics.density
            assertTrue(settings.width/density>=47f);assertTrue(settings.height/density>=47f)
            compose.onNodeWithTag("new-docx").performScrollTo().assertIsDisplayed()
            val word=compose.onNodeWithTag("new-docx").fetchSemanticsNode().boundsInRoot
            val sheet=compose.onNodeWithTag("new-xlsx").fetchSemanticsNode().boundsInRoot
            assertEquals(word.top,sheet.top,2f)
            assertTrue(word.width>pixels.width*.35f)
            compose.onNodeWithTag("new-hwpx").performScrollTo().assertIsDisplayed()
        } finally {
            device.executeShellCommand("settings put system font_scale "+oldScale)
            device.executeShellCommand("cmd uimode night auto")
        }
    }
}
