package kr.documentstudio

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.*
import kr.documentstudio.core.*
import kr.documentstudio.data.DocumentRepository
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.zip.*

class BackupSafetyTest {
    private lateinit var context: Context
    private lateinit var repo: DocumentRepository
    @Before fun setup() {context=ApplicationProvider.getApplicationContext();PDFBoxResourceLoader.init(context);repo=DocumentRepository(context)}
    private fun file(ext: String)=File(context.cacheDir,"tests/${UUID.randomUUID()}.$ext").apply {parentFile!!.mkdirs();writeBytes(byteArrayOf())}
    private fun uri(f: File)=FileProvider.getUriForFile(context,"kr.documentstudio.testfiles",f)
    @Test fun importedOfficeRecoveryAndProviderOriginalSurviveRepeatedBackups() = runBlocking {
        val s=repo.create(Format.DOCX)
        val working=repo.prepareOffice(s).apply {writeText("interrupted native write")}
        val provider=File(s.base.parentFile,"provider-backup.bin").apply {writeText("independent original bytes")}
        val zip=file("zip");repo.exportBackup(s,uri(zip))
        val restored=repo.open(uri(zip));assertNull(restored.recoveryWarning)
        assertFalse(File(restored.base.parentFile,"office-pending.json").exists())
        assertEquals(s.edits,restored.edits)
        val next=file("zip");repo.exportBackup(restored,uri(next))
        for(backup in listOf(zip,next)) ZipFile(backup).use {z->
            assertEquals(working.readText(),z.getInputStream(z.getEntry("office-recovery/${working.name}")).bufferedReader().readText())
            assertEquals(provider.readText(),z.getInputStream(z.getEntry("provider-backup.bin")).bufferedReader().readText())
            assertNotNull(z.getEntry("office-recovery/office-pending.json"))
        }
        // A new failed attempt must not replace the imported attempt's marker.
        repo.prepareOffice(restored).writeText("another failure")
        val combined=file("zip");repo.exportBackup(restored,uri(combined))
        ZipFile(combined).use {z->assertEquals(2,z.entries().toList().count {it.name.endsWith("office-pending.json")})}
        val again=repo.open(uri(combined));val third=file("zip");repo.exportBackup(again,uri(third))
        ZipFile(third).use {z->assertEquals(2,z.entries().toList().count {it.name.endsWith("office-pending.json")})}
    }
    @Test fun unsafeBackupPathsAreRejectedBeforeIndexMutation() = runBlocking {
        val s=repo.create(Format.TXT);val good=file("zip");repo.exportBackup(s,uri(good))
        val malicious=file("zip")
        ZipFile(good).use {original->ZipOutputStream(malicious.outputStream()).use {out->
            original.entries().toList().forEach {e->out.putNextEntry(ZipEntry(e.name));original.getInputStream(e).use {it.copyTo(out)};out.closeEntry()}
            out.putNextEntry(ZipEntry("office-recovery/../../outside.bin"));out.write(byteArrayOf(1));out.closeEntry()
        }}
        val before=repo.recents().map {it.id}.toSet()
        try {repo.open(uri(malicious));fail("Unsafe path accepted")} catch(e: Exception) {assertTrue(e is ZipException || e is IllegalArgumentException && e.message!!.contains("경로"))}
        // API 34+ rejects traversal before our validator. A colon reaches the
        // app's own whitelist and exercises protection on older Android too.
        val invalid=file("zip")
        ZipFile(good).use {original->ZipOutputStream(invalid.outputStream()).use {out->
            original.entries().toList().forEach {e->out.putNextEntry(ZipEntry(e.name));original.getInputStream(e).use {it.copyTo(out)};out.closeEntry()}
            out.putNextEntry(ZipEntry("office-recovery/invalid:name"));out.write(byteArrayOf(1));out.closeEntry()
        }}
        try {repo.open(uri(invalid));fail("Invalid asset path accepted")} catch(e: IllegalArgumentException) {assertTrue(e.message!!.contains("경로"))}
        assertEquals(before,repo.recents().map {it.id}.toSet())
        assertFalse(File(context.filesDir,"documents/outside.bin").exists())
    }
    @Test fun repositoriesSerializeConcurrentIndexUpdates() = runBlocking {
        val created=(1..12).map {async(Dispatchers.IO) {DocumentRepository(context).create(Format.TXT)}}.awaitAll()
        val ids=repo.recents().map {it.id}.toSet()
        assertTrue(created.all {it.id in ids})
        created.map {async(Dispatchers.IO) {DocumentRepository(context).favorite(it.id)}}.awaitAll()
        assertTrue(repo.recents().filter {it.id in created.map {s->s.id}}.all {it.favorite})
    }
}
