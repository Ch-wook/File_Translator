package kr.documentstudio

import android.net.Uri
import kotlinx.coroutines.runBlocking
import kr.documentstudio.core.*
import kr.documentstudio.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile

/** Exercises Android storage/recovery separately from the visual engine round-trip tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=StudioApplication::class)
class VisualWorkingCopyTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private fun change(file: File,format: Format,text: String) {
        val stage=File(file.parentFile,"test-changed.${format.extension}")
        if(format==Format.HWP) {
            val doc=LegacyDocument.open(file);doc.write(stage,Edits(values=mapOf(doc.sections.first().fields.first().id to text)))
        } else {
            val doc=PackageDocument.open(file)
            val key=if(format==Format.XLSX) doc.sections.first().id+"#A1" else doc.sections.first().fields.first().id
            doc.write(stage,Edits(values=mapOf(key to text)))
        }
        stage.copyTo(file,true);stage.delete()
    }
    @Test fun importedVisualFilesSaveByteForByteAndKeepTheirPreviousBaselines()=runBlocking {
        for(format in listOf(Format.HWP,Format.HWPX,Format.DOCX,Format.XLSX,Format.PPTX)) {
            val repo=DocumentRepository(context);val original=repo.create(format);val before=original.base.readBytes()
            val working=repo.prepareOffice(original);change(working,format,"DIRECT EDIT")
            File(working.path+".closed").writeText("closed");File(working.path+".edited").writeText("approved")
            val accepted=repo.acceptOffice(original)
            assertArrayEquals(before,original.base.readBytes());assertEquals(original.revision+1,accepted.revision)
            val destination=File.createTempFile("visual-save-",".${format.extension}",context.cacheDir)
            val saved=repo.save(accepted,Uri.fromFile(destination))
            assertArrayEquals(accepted.base.readBytes(),destination.readBytes())
            assertFalse(saved.dirty)
            assertEquals(accepted.base,repo.restore(saved.id).base)
            val text=accepted.sections.flatMap {it.fields}.joinToString {it.text}+accepted.sections.flatMap {it.cells.values}.joinToString()
            assertTrue("$format edit survived import",text.contains("DIRECT EDIT"))
            val sourceBytes=destination.readBytes();destination.appendText("external change")
            try {repo.save(saved.copy(revision=saved.revision+1),Uri.fromFile(destination));fail("External edits must block replacement")}
            catch(expected: IllegalArgumentException) {assertTrue(expected.message!!.contains("외부"))}
            assertArrayEquals(sourceBytes+"external change".toByteArray(),destination.readBytes())
        }
    }
    @Test fun activeWritersAndCorruptHangulOutputLeaveTheOriginalAndRecoveryFilesIntact()=runBlocking {
        for(format in listOf(Format.HWP,Format.HWPX)) {
            val repo=DocumentRepository(context);val s=repo.create(format);val before=s.base.readBytes();val working=repo.prepareOffice(s)
            RandomAccessFile(File(working.parentFile,"office.lock"),"rw").use {file->file.channel.lock().use {
                try {repo.acceptOffice(s);fail("Live editor import must be refused")} catch(expected: IllegalArgumentException) {}
            }}
            working.writeText("interrupted write");File(working.path+".edited").writeText("approved")
            val restored=repo.restore(s.id)
            assertNotNull(restored.recoveryWarning);assertArrayEquals(before,restored.base.readBytes())
            assertTrue(working.exists());assertTrue(File(working.parentFile,"office-pending.json").exists())
        }
    }
    @Test fun readOnlyOrUnapprovedEngineBytesNeverReplaceTheWorkingBaseline()=runBlocking {
        for(format in listOf(Format.HWP,Format.HWPX,Format.DOCX,Format.XLSX,Format.PPTX)) {
            val repo=DocumentRepository(context);val s=repo.create(format);val before=s.base.readBytes()
            val working=repo.prepareOffice(s);change(working,format,"UNAPPROVED")
            File(working.path+".closed").writeText("closed")
            val result=repo.acceptOffice(s)
            assertEquals(s.base,result.base);assertEquals(s.revision,result.revision);assertNotNull(result.recoveryWarning)
            assertArrayEquals(before,result.base.readBytes());assertTrue(working.isFile)
            assertFalse(File(working.parentFile,"office-pending.json").exists())
        }
    }
    @Test fun checkpointCacheInvalidatesAfterAnotherRepositoryReplacesTheState()=runBlocking {
        val first=DocumentRepository(context);val second=DocumentRepository(context)
        val s=first.create(Format.TXT)
        val alpha=s.copy(edits=s.edits.copy(text="alpha"))
        first.checkpoint(alpha)
        val bravo=s.copy(edits=s.edits.copy(text="bravo"))
        second.checkpoint(bravo)
        assertEquals("bravo",second.restore(s.id).edits.text)
        first.checkpoint(alpha)
        assertEquals("alpha",second.restore(s.id).edits.text)
        assertEquals("",s.base.readText())
    }
    @Test fun checkpointRecreatesMissingDurableStateAndLeavesEqualBytesUntouched()=runBlocking {
        val repo=DocumentRepository(context);val s=repo.create(Format.TXT).let {it.copy(edits=it.edits.copy(text="최신 한글"))}
        repo.checkpoint(s)
        val state=File(s.base.parentFile,"state.json")
        assertTrue(state.setLastModified(1000))
        repo.checkpoint(s);repo.checkpoint(s)
        assertEquals(1000L,state.lastModified())
        assertTrue(state.delete())
        repo.checkpoint(s)
        assertEquals("최신 한글",repo.restore(s.id).edits.text)
    }
    @Test fun untouchedSaveLeavesProviderBytesAndModificationTimeAlone()=runBlocking {
        val repo=DocumentRepository(context);val created=repo.create(Format.DOCX)
        val destination=File.createTempFile("view-save-",".docx",context.cacheDir)
        val uri=Uri.fromFile(destination);val saved=repo.save(created,uri);val bytes=destination.readBytes()
        assertTrue(destination.setLastModified(1000L))
        val result=repo.save(saved,uri)
        assertSame(saved,result);assertEquals(1000L,destination.lastModified());assertArrayEquals(bytes,destination.readBytes())
    }
    @Test fun concurrentHostChangesCannotBeReplacedByAHangulEngineResult()=runBlocking {
        val repo=DocumentRepository(context);val s=repo.create(Format.HWPX);val working=repo.prepareOffice(s)
        change(working,s.format,"ENGINE CHANGE");File(working.path+".edited").writeText("approved")
        val field=s.sections.first().fields.first()
        val concurrent=s.copy(edits=s.edits.copy(values=mapOf(field.id to "HOST CHANGE")))
        repo.checkpoint(concurrent)
        try {repo.acceptOffice(concurrent);fail("Concurrent changes must block import")} catch(expected: IllegalArgumentException) {}
        assertEquals("HOST CHANGE",repo.restore(s.id).edits.values[field.id]);assertTrue(working.exists())
    }
    private fun requestClose(working: File, saveOriginal: Boolean) {
        File(working.path+".close-request").writeText(org.json.JSONObject().put("version",1)
            .put("working",working.name).put("hash",DocumentRepository.digest(working))
            .put("saveOriginal",saveOriginal).toString())
    }
    @Test fun nativeCloseRequiresReleasedWriterLockAndStableAcknowledgedBytes()=runBlocking {
        val repo=DocumentRepository(context);val s=repo.create(Format.DOCX);val working=repo.prepareOffice(s)
        val closed=File(working.path+".closed")
        requestClose(working,false)
        RandomAccessFile(File(working.parentFile,"office.lock"),"rw").use {file->file.channel.lock().use {
            try {repo.completeOfficeClose(s);fail("A close request cannot confirm a live writer")}
            catch(expected: IllegalArgumentException) {}
            assertFalse(closed.exists())
        }}
        working.appendText("unexpected late write")
        try {repo.completeOfficeClose(s);fail("Changed bytes cannot match the native close request")}
        catch(expected: IllegalArgumentException) {}
        assertFalse(closed.exists())
        s.base.copyTo(working,overwrite=true);requestClose(working,false)
        assertFalse(repo.completeOfficeClose(s));assertTrue(closed.isFile)
        assertSame(s,repo.acceptOffice(s))
        assertFalse(File(working.path+".close-request").exists())
    }
    @Test fun durableSaveIntentAndApprovedOutputSurviveNativeProcessExit()=runBlocking {
        val repo=DocumentRepository(context);val s=repo.create(Format.DOCX);val working=repo.prepareOffice(s)
        val before=s.base.readBytes()
        change(working,s.format,"NATIVE ACKNOWLEDGED EDIT")
        requestClose(working,true)
        assertFalse("An intent alone cannot approve document output",repo.completeOfficeClose(s))
        File(working.path+".closed").delete()
        File(working.path+".edited").writeText("native Save acknowledged")
        assertTrue(repo.completeOfficeClose(s))
        val accepted=repo.acceptOffice(s)
        assertEquals(s.revision+1,accepted.revision)
        assertArrayEquals(before,s.base.readBytes())
        assertTrue(accepted.sections.flatMap {it.fields}.joinToString {it.text}.contains("NATIVE ACKNOWLEDGED EDIT"))
        assertFalse(File(working.path+".close-request").exists())
    }

}
