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
            File(working.path+".closed").writeText("closed")
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
            try {repo.save(saved,Uri.fromFile(destination));fail("External edits must block replacement")}
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
            working.writeText("interrupted write")
            val restored=repo.restore(s.id)
            assertNotNull(restored.recoveryWarning);assertArrayEquals(before,restored.base.readBytes())
            assertTrue(working.exists());assertTrue(File(working.parentFile,"office-pending.json").exists())
        }
    }
    @Test fun concurrentHostChangesCannotBeReplacedByAHangulEngineResult()=runBlocking {
        val repo=DocumentRepository(context);val s=repo.create(Format.HWPX);val working=repo.prepareOffice(s)
        change(working,s.format,"ENGINE CHANGE")
        val field=s.sections.first().fields.first()
        val concurrent=s.copy(edits=s.edits.copy(values=mapOf(field.id to "HOST CHANGE")))
        repo.checkpoint(concurrent)
        try {repo.acceptOffice(concurrent);fail("Concurrent changes must block import")} catch(expected: IllegalArgumentException) {}
        assertEquals("HOST CHANGE",repo.restore(s.id).edits.values[field.id]);assertTrue(working.exists())
    }
}
