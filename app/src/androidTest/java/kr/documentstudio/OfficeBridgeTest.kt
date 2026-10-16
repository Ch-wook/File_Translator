package kr.documentstudio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kr.documentstudio.core.*
import kr.documentstudio.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

@RunWith(AndroidJUnit4::class)
class OfficeBridgeTest {
    private val context: Context=ApplicationProvider.getApplicationContext()
    @Test fun nativeLoggerCanBeDisabledBeforeLoadingDocuments() {
        Class.forName("org.libreoffice.androidlib.LOActivity")
        val method=Class.forName("org.libreoffice.androidlib.OfficePrivacy").getDeclaredMethod("quiet")
        method.isAccessible=true
        assertEquals(true,method.invoke(null))
    }
    @Test fun workingCopyImportPreservesBaselineInkAndUndoInputs() = runBlocking {
        val repo=DocumentRepository(context)
        val original=repo.create(Format.DOCX)
        val field=original.sections.first().fields.first()
        val s=original.copy(edits=Edits(values=mapOf(field.id to "Before native"),strokes=listOf(Stroke(anchor=original.sections.first().id,tool=InkTool.PEN,color=-1,width=0.01f,points=listOf(InkPoint(0.1f,0.1f,0.5f))))))
        val originalHash=DocumentRepository.digest(s.base)
        val working=repo.prepareOffice(s)
        assertEquals("Before native",PackageDocument.open(working).sections.first().fields.first().text)
        val modified=File(working.parentFile,"bridge-modified.docx")
        PackageDocument.open(working).write(modified,Edits(values=mapOf(field.id to "After native")))
        modified.copyTo(working,overwrite=true)
        File(working.path+".closed").writeText("closed")
        val accepted=repo.acceptOffice(s)
        assertEquals("After native",accepted.sections.first().fields.first().text)
        assertEquals(originalHash,DocumentRepository.digest(s.base))
        assertEquals(s.edits.strokes,accepted.edits.strokes)
        assertEquals(s.revision+1,accepted.revision)
        assertEquals(s.savedRevision,accepted.savedRevision)
        assertEquals(accepted.base,DocumentRepository(context).restore(s.id).base)
        assertFalse(File(s.base.parentFile,"office-pending.json").exists())
    }
    @Test fun activeEngineAndBrokenCrashOutputNeverReplaceBaseline() = runBlocking {
        val repo=DocumentRepository(context);val s=repo.create(Format.XLSX)
        val working=repo.prepareOffice(s)
        RandomAccessFile(File(working.parentFile,"office.lock"),"rw").use {file ->file.channel.lock().use {
            try {repo.acceptOffice(s);fail("Active editor must be refused")} catch(expected: IllegalArgumentException) { }
        }}
        working.writeText("Interrupted partial write")
        val restored=DocumentRepository(context).restore(s.id)
        assertEquals(s.base,restored.base);assertNotNull(restored.recoveryWarning)
        assertTrue(working.exists());assertTrue(File(working.parentFile,"office-pending.json").exists())
        assertEquals(Format.XLSX,PackageDocument.open(s.base).format)
        val archived=repo.archiveOfficeRecovery(restored)
        assertNull(archived.recoveryWarning);assertTrue(working.exists())
        assertFalse(File(working.parentFile,"office-pending.json").exists())
        assertTrue(repo.prepareOffice(archived).isFile)
    }
    @Test fun unchangedCopyDoesNotCreateRevision() = runBlocking {
        val repo=DocumentRepository(context);val s=repo.create(Format.PPTX)
        repo.prepareOffice(s)
        assertEquals(s,repo.acceptOffice(s))
    }
    @Test fun reopeningCleanProviderUriPrioritizesCrashedOfficeWorkingCopy() = runBlocking {
        val repo=DocumentRepository(context)
        val original=File(context.cacheDir,"tests/provider-office-${java.util.UUID.randomUUID()}.docx").apply {parentFile!!.mkdirs()}
        Templates.create(Format.DOCX,original)
        val uri=androidx.core.content.FileProvider.getUriForFile(context,"kr.documentstudio.testfiles",original)
        val s=repo.open(uri);assertFalse(s.dirty)
        val providerHash=DocumentRepository.digest(original)
        val working=repo.prepareOffice(s)
        val field=s.sections.first().fields.first()
        val modified=File(working.parentFile,"native-provider-recovery.docx")
        PackageDocument.open(working).write(modified,Edits(values=mapOf(field.id to "Recovered native provider edit")))
        modified.copyTo(working,overwrite=true)
        assertTrue(repo.recents().first {it.id==s.id}.dirty)
        val reopened=DocumentRepository(context).open(uri)
        assertEquals("Recovered native provider edit",reopened.sections.first().fields.first().text)
        assertTrue(reopened.dirty);assertEquals(s.revision+1,reopened.revision)
        assertEquals(providerHash,DocumentRepository.digest(original))
    }
    @Test fun concurrentHostTextIsNeverReplacedByOfficeImport() = runBlocking {
        val repo=DocumentRepository(context);val s=repo.create(Format.DOCX)
        val working=repo.prepareOffice(s);val field=s.sections.first().fields.first()
        val concurrent=s.copy(edits=s.edits.copy(values=mapOf(field.id to "New host text")))
        repo.checkpoint(concurrent)
        try {repo.acceptOffice(concurrent);fail("Concurrent host edits must be preserved")} catch(expected: IllegalArgumentException) { }
        assertTrue(working.exists())
        assertEquals("New host text",DocumentRepository(context).restore(s.id).edits.values[field.id])
    }
}
