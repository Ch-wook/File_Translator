package kr.documentstudio

import android.net.Uri
import kr.documentstudio.core.Format
import kr.documentstudio.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=StudioApplication::class)
class ProviderWriteRecoveryTest {
    private val context get()=RuntimeEnvironment.getApplication()
    @Before fun cleanTestJournals(){File(context.filesDir,"provider-recovery").deleteRecursively()}
    private fun file(value: String)=File.createTempFile("recovery-test-",".txt",context.cacheDir).apply {writeText(value)}
    private fun prepare(owner: String,target: File,payload: File)=ProviderWrites(context).prepare(owner,payload,Uri.fromFile(target),DocumentRepository.digest(target),"문서.txt","text/plain")
    @Test fun interruptedWriteCannotOverwriteThePreviousOriginalBackupOnRetry() {
        val owner=UUID.randomUUID().toString();val target=file("이전 원본");val payload=file("최신 편집 결과")
        val receipt=prepare(owner,target,payload);val before=DocumentRepository.digest(receipt.original)
        target.writeText("중단된 일부")
        val reopened=ProviderWrites(context)
        assertThrows(SaveRecoveryRequired::class.java){reopened.publish(owner,payload,Uri.fromFile(target),before)}
        assertEquals("이전 원본",reopened.snapshot(receipt.recovery.id,true).readText())
        assertEquals("최신 편집 결과",reopened.snapshot(receipt.recovery.id,false).readText())
        assertEquals("중단된 일부",target.readText());assertEquals(before,DocumentRepository.digest(receipt.original))
        assertEquals(1,reopened.list().size)
    }
    @Test fun verifiedProviderBytesCanBeAcknowledgedAfterRestartWithoutRewriting() {
        val owner=UUID.randomUUID().toString();val target=file("원본");val payload=file("최신")
        val receipt=prepare(owner,target,payload)
        payload.copyTo(target,overwrite=true);assertTrue(target.setLastModified(1000))
        val writes=ProviderWrites(context);val resumed=writes.publish(owner,payload,Uri.fromFile(target),receipt.recovery.before)
        assertEquals(receipt.recovery.id,resumed.recovery.id);assertEquals("VERIFIED",resumed.recovery.phase)
        assertEquals(1000L,target.lastModified());assertEquals("최신",target.readText())
        writes.finish(resumed);assertTrue(writes.list().isEmpty())
    }
    @Test fun anExternalChangeBlocksMutationBeforeTheFirstTruncate() {
        val target=file("원본");val expected=DocumentRepository.digest(target);target.writeText("외부 앱의 새 내용")
        assertTrue(target.setLastModified(1000))
        val payload=file("앱의 편집 결과")
        assertThrows(OriginalChanged::class.java){ProviderWrites(context).publish(UUID.randomUUID().toString(),payload,Uri.fromFile(target),expected)}
        assertEquals("외부 앱의 새 내용",target.readText());assertEquals(1000L,target.lastModified())
    }
    @Test fun tamperedRecoveryPayloadIsRejectedAndStillPreserved() {
        val receipt=prepare(UUID.randomUUID().toString(),file("원본"),file("최신"))
        receipt.payload.appendText("corrupt")
        assertThrows(IllegalArgumentException::class.java){ProviderWrites(context).snapshot(receipt.recovery.id,false)}
        assertTrue(receipt.payload.isFile);assertEquals("원본",receipt.original.readText())
    }
    @Test fun explicitlyDiscardingARecoveryNeverDeletesTheExternalFile() {
        val target=file("사용자 파일");val receipt=prepare(UUID.randomUUID().toString(),target,file("새 내용"))
        ProviderWrites(context).discard(receipt.recovery.id)
        assertEquals("사용자 파일",target.readText());assertTrue(ProviderWrites(context).list().isEmpty())
    }
    @Test fun reopeningTheDocumentSurfacesInterruptedSaveWithoutLosingItsLatestEdits()=runBlocking {
        val repo=DocumentRepository(context);val initial=repo.create(Format.TXT)
        val edited=initial.copy(edits=initial.edits.copy(text="보호할 최신 한글"));repo.checkpoint(edited)
        val target=file("이전 내용");val receipt=prepare(edited.id,target,file(edited.edits.text))
        target.writeText("partial")
        val restored=DocumentRepository(context).restore(edited.id)
        assertEquals(edited.edits,restored.edits);assertNotNull(restored.recoveryWarning)
        assertTrue(receipt.original.isFile);assertTrue(receipt.payload.isFile)
    }
}
