package kr.documentstudio.core
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File
import java.util.UUID

class TemporaryFilesTest {
    @Test fun oldTransientFilesAreRemovedWhileRecentPrintAndRecoveryStay() {
        val root=Files.createTempDirectory("studio-cleanup-").toFile();val cache=File(root,"cache").apply {mkdir()};val docs=File(root,"documents").apply {mkdir()};val doc=File(docs,UUID.randomUUID().toString()).apply {mkdir()}
        val now=System.currentTimeMillis()
        fun file(parent: File,name: String,old: Boolean=true)=File(parent,name).apply {writeText("KEEP_OR_CLEAN");if(old) setLastModified(now-172800000)}
        val old=file(doc,"materialized-old.bin");val print=file(cache,"print-old.pdf");val active=file(cache,"print-active.pdf")
        val recent=file(doc,"attachment-new.bin",false);val base=file(doc,"base.bin");val state=file(doc,"state.json");val office=file(doc,"office-recovery.docx");val backup=file(doc,"provider-backup.bin");val foreign=file(cache,"other-app.tmp")
        val result=TemporaryFiles.cleanup(cache,docs,setOf(active),now)
        assertEquals(2,result.files);assertFalse(old.exists());assertFalse(print.exists());assertTrue(result.bytes>0)
        assertTrue(listOf(active,recent,base,state,office,backup,foreign).all {it.isFile})
        val remaining=doc.listFiles().orEmpty().toList()+cache.listFiles().orEmpty().toList()
        remaining.forEach {it.delete()};doc.delete();cache.delete();docs.delete();root.delete()
    }
}
