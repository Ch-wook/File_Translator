package kr.documentstudio

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import kotlinx.coroutines.runBlocking
import kr.documentstudio.core.Format
import kr.documentstudio.data.DocumentRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

/** Real stream access is independent from optional provider metadata queries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=StudioApplication::class)
class DocumentOpenMetadataTest {
    class MetadataProvider: ContentProvider() {
        lateinit var source: File
        var queryUnavailable=false
        var mimeUnavailable=false
        var readDenied=false
        var displayName="실제 문서.hwpx"
        override fun onCreate()=true
        override fun query(uri: Uri,projection: Array<out String>?,selection: String?,selectionArgs: Array<out String>?,sortOrder: String?): Cursor {
            if(queryUnavailable) throw UnsupportedOperationException("Metadata queries not supported")
            // Providers may reorder columns or return additional metadata.
            return MatrixCursor(arrayOf("provider_extra",OpenableColumns.DISPLAY_NAME)).apply {addRow(arrayOf("opaque-id",displayName))}
        }
        override fun getType(uri: Uri): String {
            if(mimeUnavailable) throw IllegalArgumentException("MIME unavailable")
            return "application/octet-stream"
        }
        override fun openFile(uri: Uri,mode: String): ParcelFileDescriptor {
            if(readDenied) throw FileNotFoundException("Read access denied")
            require(mode=="r")
            return ParcelFileDescriptor.open(source,ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun insert(uri: Uri,values: ContentValues?): Uri?=throw UnsupportedOperationException()
        override fun update(uri: Uri,values: ContentValues?,selection: String?,selectionArgs: Array<out String>?)=throw UnsupportedOperationException()
        override fun delete(uri: Uri,selection: String?,selectionArgs: Array<out String>?)=throw UnsupportedOperationException()
    }
    private fun provider(source: File): Pair<MetadataProvider,Uri> {
        val authority="document-open-"+UUID.randomUUID()
        val p=MetadataProvider().apply {
            this.source=source
            attachInfo(RuntimeEnvironment.getApplication(),ProviderInfo().apply {this.authority=authority})
        }
        ShadowContentResolver.registerProviderInternal(authority,p)
        return p to Uri.parse("content://$authority/download/원본.hwpx")
    }
    @Test fun unavailableNameAndMimeStillOpenSupportedBinaryBytes()=runBlocking {
        val repo=DocumentRepository(RuntimeEnvironment.getApplication())
        for(format in listOf(Format.HWP,Format.HWPX,Format.DOCX,Format.XLSX,Format.PPTX)) {
            val created=repo.create(format);val hash=DocumentRepository.digest(created.base)
            val (p,uri)=provider(created.base);p.queryUnavailable=true;p.mimeUnavailable=true
            val opened=repo.open(uri)
            assertEquals(format,opened.format)
            assertEquals(hash,DocumentRepository.digest(opened.base))
            assertEquals(hash,DocumentRepository.digest(created.base))
            assertEquals(opened.id,repo.restore(opened.id).id)
            val working=repo.prepareOffice(opened)
            assertEquals(hash,DocumentRepository.digest(working))
            assertEquals(hash,DocumentRepository.digest(repo.acceptOffice(opened).base))
        }
    }
    @Test fun nameUsesItsColumnAndMimeFailureDoesNotOverrideDetectedFormat()=runBlocking {
        val repo=DocumentRepository(RuntimeEnvironment.getApplication());val created=repo.create(Format.HWPX)
        val (p,uri)=provider(created.base);p.mimeUnavailable=true
        val opened=repo.open(uri)
        assertEquals(p.displayName,opened.name);assertEquals(Format.HWPX,opened.format)
    }
    @Test fun metadataFallbackNeverTreatsArbitraryBytesAsADocumentOrGrantsReadAccess()=runBlocking {
        val context=RuntimeEnvironment.getApplication();val repo=DocumentRepository(context)
        val bad=File.createTempFile("invalid-document-",".hwpx",context.cacheDir).apply {writeText("NOT A HWPX CONTAINER")}
        val (p,uri)=provider(bad);p.queryUnavailable=true;p.mimeUnavailable=true
        try {repo.open(uri);fail("Invalid container must still fail")} catch(expected: IllegalArgumentException) {}
        val valid=repo.create(Format.HWPX);p.source=valid.base;p.readDenied=true
        try {repo.open(uri);fail("Missing read access must still fail")} catch(expected: FileNotFoundException) {}
        assertEquals(Format.HWPX,repo.restore(valid.id).format)
    }
}
