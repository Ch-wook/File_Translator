package kr.documentstudio

import android.app.Application
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kr.documentstudio.core.*
import kr.documentstudio.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class EncryptedBackupIntegrationTest {
    private val app=ApplicationProvider.getApplicationContext<Application>()
    private fun file(ext: String)=File(app.cacheDir,"tests/${UUID.randomUUID()}.$ext").apply {parentFile!!.mkdirs();writeBytes(byteArrayOf())}
    private fun uri(f: File)=FileProvider.getUriForFile(app,"kr.documentstudio.testfiles",f)
    private fun main(block: ()->Unit)=InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private suspend fun ready(vm: EditorViewModel)=withTimeout(20000) {vm.state.first {!it.busy}}
    @Test fun encryptedArchivePreservesEditsLayersRecoveryAndRejectsCorruption()=runBlocking {
        val repo=DocumentRepository(app);val s=repo.create(Format.MD).let {it.copy(edits=it.edits.copy(text="비밀 문서 내용",paper=Paper.DOTS,layers=listOf(InkLayer(),InkLayer("review","검토",false,true)),strokes=listOf(Stroke(anchor="text",tool=InkTool.BRUSH,color=-1,width=.004f,points=listOf(InkPoint(.2f,.3f,.6f,.2f)),layerId="review",pressureCurve=1.8f,smoothing=.5f))))}
        File(s.base.parentFile,"office-proof.txt").writeText("RECOVERY_KEEP")
        val encrypted=file("dsbackup");val password="한글 암호 123!".toCharArray();repo.exportBackup(s,uri(encrypted),password);assertTrue(EncryptedBackup.isEncrypted(encrypted))
        val count=repo.recents().size
        try {repo.open(uri(encrypted));fail("Password should be required")} catch(e: BackupPasswordRequired) {assertEquals(uri(encrypted),e.uri)}
        try {repo.open(uri(encrypted),password="incorrect".toCharArray());fail("Wrong password should fail")} catch(e: IllegalArgumentException) {assertTrue(e.message!!.contains("암호"))}
        assertEquals(count,repo.recents().size)
        val damaged=file("dsbackup");damaged.writeBytes(encrypted.readBytes().also {it[it.lastIndex]=(it.last().toInt() xor 1).toByte()})
        try {repo.open(uri(damaged),password=password);fail("Tampering should fail")} catch(_: IllegalArgumentException) {}
        assertEquals(count,repo.recents().size)
        val restored=repo.open(uri(encrypted),password=password);assertEquals(s.edits,restored.edits);assertNull(restored.uri)
        assertEquals("RECOVERY_KEEP",File(restored.base.parentFile,"preserved-backup/office-recovery/office-proof.txt").readText())
        val normal=file("zip");repo.exportBackup(restored,uri(normal));assertEquals(s.edits,repo.open(uri(normal)).edits)
        val golden=file("dsbackup");InstrumentationRegistry.getInstrumentation().context.assets.open("backup-golden.b64").use {golden.writeBytes(android.util.Base64.decode(it.readBytes(),android.util.Base64.DEFAULT))}
        val plain=file("txt");EncryptedBackup.decrypt(golden,plain,"검증 pass 123!".toCharArray());assertEquals("독립 암호화 검증\n",plain.readText())
    }
    @Test fun pendingExportRetainsDocumentAndUnlockErasesPasswordsAndKeepsRetry()=runBlocking {
        lateinit var vm: EditorViewModel;main {vm=EditorViewModel(app);vm.create(Format.TXT)};ready(vm);val id=vm.state.value.active!!
        val password="test backup 123!".toCharArray()
        main {vm.text("FIRST_PRIVATE");assertTrue(vm.prepareEncryptedBackup(id,password));vm.create(Format.TXT)};ready(vm)
        val second=vm.state.value.active;main {vm.text("SECOND_KEEP")}
        val encrypted=file("dsbackup");main {vm.finishEncryptedBackup(uri(encrypted))};ready(vm)
        assertTrue(password.all {it=='\u0000'});assertEquals(second,vm.state.value.active)
        main {vm.open(uri(encrypted))};ready(vm);assertEquals(uri(encrypted).toString(),vm.state.value.lockedBackupUri)
        val wrong="incorrect".toCharArray();main {vm.unlockBackup(wrong)};ready(vm);assertTrue(wrong.all {it=='\u0000'});assertNotNull(vm.state.value.lockedBackupUri)
        val right="test backup 123!".toCharArray();main {vm.unlockBackup(right)};ready(vm)
        assertTrue(right.all {it=='\u0000'});assertNull(vm.state.value.lockedBackupUri);assertEquals("FIRST_PRIVATE",vm.state.value.session!!.edits.text)
        assertEquals("SECOND_KEEP",vm.state.value.tabs.first {it.id==second}.edits.text)
    }
}
