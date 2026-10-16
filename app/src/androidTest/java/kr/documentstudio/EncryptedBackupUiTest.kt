package kr.documentstudio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import kr.documentstudio.core.Format
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class EncryptedBackupUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun passwordRetryRestoresDocumentAndExportRequiresConfirmation() {
        val vm=ViewModelProvider(compose.activity)[EditorViewModel::class.java]
        compose.runOnUiThread {vm.create(Format.TXT)};compose.waitUntil(15000) {!vm.state.value.busy && vm.state.value.session!=null}
        compose.runOnUiThread {vm.text("ENCRYPTED_UI_PROOF")};compose.waitForIdle()
        val encrypted=File(compose.activity.cacheDir,"tests/${UUID.randomUUID()}.dsbackup").apply {parentFile!!.mkdirs();writeBytes(byteArrayOf())}
        val uri=FileProvider.getUriForFile(compose.activity,"kr.documentstudio.testfiles",encrypted)
        runBlocking {vm.repository.exportBackup(vm.state.value.session!!,uri,"test password123!".toCharArray())}
        compose.runOnUiThread {vm.open(uri)};compose.waitUntil(15000) {vm.state.value.lockedBackupUri!=null && !vm.state.value.busy}
        compose.onNodeWithText("백업 암호").performTextInput("incorrect");compose.onNodeWithText("복구").performClick()
        compose.waitUntil(25000) {!vm.state.value.busy && vm.state.value.message?.contains("암호")==true}
        compose.onNodeWithText("백업 암호").performTextReplacement("test password123!");compose.onNodeWithText("복구").performClick()
        compose.waitUntil(25000) {!vm.state.value.busy && vm.state.value.lockedBackupUri==null}
        assertEquals("ENCRYPTED_UI_PROOF",vm.state.value.session!!.edits.text)
        compose.onNodeWithContentDescription("문서 메뉴").performClick();compose.onNodeWithText("문서·필기 암호화 백업").performScrollTo().performClick()
        compose.onNodeWithText("백업 파일 선택").assertIsNotEnabled()
        compose.onNodeWithText("백업 암호").performTextInput("new password123!");compose.onNodeWithText("암호 다시 입력").performTextInput("mismatch")
        compose.onNodeWithText("백업 파일 선택").assertIsNotEnabled();compose.onNodeWithText("암호 다시 입력").performTextReplacement("new password123!")
        compose.onNodeWithText("백업 파일 선택").assertIsEnabled();compose.onNodeWithText("취소").performClick()
        compose.onNodeWithContentDescription("문서 메뉴").performClick();compose.onNodeWithText("설정").performScrollTo().performClick()
        compose.onNodeWithText("임시 변환 파일 정리").performScrollTo().performClick()
        compose.waitUntil(15000) {!vm.state.value.busy && vm.state.value.message?.contains("임시 변환")==true}
        assertEquals("ENCRYPTED_UI_PROOF",vm.state.value.session!!.edits.text)
        compose.onNodeWithText("완료").performClick()
    }
}
