package kr.documentstudio

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/** Same-UID debug fixture: readable bytes with deliberately unavailable metadata. */
class DocumentOpenFixtureProvider: ContentProvider() {
    override fun onCreate()=true
    override fun query(uri: Uri,projection: Array<out String>?,selection: String?,selectionArgs: Array<out String>?,sortOrder: String?): Cursor?=
        throw UnsupportedOperationException("This provider does not expose file metadata")
    override fun getType(uri: Uri): String?=throw IllegalArgumentException("No MIME metadata")
    override fun openFile(uri: Uri,mode: String): ParcelFileDescriptor {
        require(mode=="r")
        val directory=File(requireNotNull(context).cacheDir,"open-fixtures").canonicalFile
        val file=File(directory,requireNotNull(uri.lastPathSegment)).canonicalFile
        require(file.parentFile==directory && file.isFile)
        return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun insert(uri: Uri,values: ContentValues?): Uri?=throw UnsupportedOperationException()
    override fun update(uri: Uri,values: ContentValues?,selection: String?,selectionArgs: Array<out String>?)=throw UnsupportedOperationException()
    override fun delete(uri: Uri,selection: String?,selectionArgs: Array<out String>?)=throw UnsupportedOperationException()
}
