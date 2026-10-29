package kr.documentstudio

import android.content.*
import android.net.Uri
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import kr.documentstudio.core.Format
import kr.documentstudio.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class SaveProcessRecoveryTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun crash(complete: Boolean): Triple<String,File,JSONObject> {
        val token=UUID.randomUUID().toString();val owner=UUID.randomUUID().toString()
        val dir=File(context.cacheDir,"tests/savekill-"+token).apply {mkdirs()}
        File(dir,"target.txt").writeText("서명과 표를 포함한 이전 원본")
        File(dir,"payload.txt").writeText("새로 수정한 한글 편집 내용")
        val disconnected=AtomicBoolean(false)
        val connection=object: ServiceConnection {
            override fun onServiceConnected(name: ComponentName,service: IBinder) {}
            override fun onServiceDisconnected(name: ComponentName){disconnected.set(true)}
        }
        assertTrue(context.bindService(Intent(context,SaveCrashFixtureService::class.java).putExtra("token",token).putExtra("owner",owner).putExtra("complete",complete),connection,Context.BIND_AUTO_CREATE))
        try {
            val deadline=SystemClock.uptimeMillis()+20000
            while((!File(dir,"crashed.json").isFile || !disconnected.get()) && SystemClock.uptimeMillis()<deadline)SystemClock.sleep(25)
            assertTrue("writer process must actually terminate",disconnected.get())
            val proof=JSONObject(File(dir,"crashed.json").readText());assertNotEquals(Process.myPid(),proof.getInt("pid"))
            return Triple(owner,dir,proof)
        } finally {context.unbindService(connection)}
    }
    @Test fun killedPartialProviderWriterRetainsBothVersionsAndBlocksAnUnsafeRetry() {
        val(owner,dir,proof)=crash(false);val writes=ProviderWrites(context);val id=proof.getString("id")
        try {
            assertEquals("서명과 표를 포함한 이전 원본",writes.snapshot(id,true).readText())
            assertEquals("새로 수정한 한글 편집 내용",writes.snapshot(id,false).readText())
            assertThrows(SaveRecoveryRequired::class.java){writes.publish(owner,File(dir,"payload.txt"),Uri.fromFile(File(dir,"target.txt")),writes.list(owner).single().before)}
            assertEquals("partial",File(dir,"target.txt").readText())
        } finally {writes.discard(id)}
    }
    @Test fun killedWriterAfterCompleteProviderBytesIsAcknowledgedWithoutAnotherWrite() {
        val(owner,dir,proof)=crash(true);val writes=ProviderWrites(context);val id=proof.getString("id")
        val target=File(dir,"target.txt");assertTrue(target.setLastModified(1000))
        val receipt=writes.publish(owner,File(dir,"payload.txt"),Uri.fromFile(target),writes.list(owner).single().before)
        assertEquals(id,receipt.recovery.id);assertEquals(1000L,target.lastModified())
        assertEquals("새로 수정한 한글 편집 내용",target.readText())
        writes.finish(receipt);assertTrue(writes.list(owner).isEmpty())
    }
    @Test fun recoveryCopyExportCannotOverwriteItsAffectedOriginal()=runBlocking {
        val(owner,dir,proof)=crash(false);val id=proof.getString("id");val writes=ProviderWrites(context)
        try {
            val repo=DocumentRepository(context);val output=File(dir,"recovered.txt").apply {writeText("")}
            repo.exportSaveRecovery(id,false,Uri.fromFile(output));assertEquals("새로 수정한 한글 편집 내용",output.readText())
            try {repo.exportSaveRecovery(id,true,Uri.fromFile(File(dir,"target.txt")));fail("Original recovery must be explicit, not an export")}
            catch(_:IllegalArgumentException){}
            assertEquals("partial",File(dir,"target.txt").readText());assertEquals(1,writes.list(owner).size)
        } finally {writes.discard(id)}
    }
}
