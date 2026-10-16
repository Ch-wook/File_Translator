package kr.documentstudio
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** Debug-only fault injector; never included in release. */
class FailingProvider: ContentProvider() {
    companion object {var remainingFailures=0}
    private fun file()=File(context!!.cacheDir,"tests/failing-provider.txt").apply {parentFile!!.mkdirs()}
    override fun onCreate()=true
    override fun getType(uri: Uri)="text/plain"
    override fun query(uri: Uri,projection: Array<out String>?,selection: String?,selectionArgs: Array<out String>?,sortOrder: String?): Cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)).apply {addRow(arrayOf("failing-provider.txt",file().length()))}
    override fun openFile(uri: Uri,mode: String): ParcelFileDescriptor {
        if(mode.contains('w') && remainingFailures>0) {remainingFailures--;file().writeText("partial provider write");throw FileNotFoundException("Injected provider failure after truncation")}
        return ParcelFileDescriptor.open(file(),ParcelFileDescriptor.parseMode(mode))
    }
    override fun insert(uri: Uri,values: ContentValues?): Uri?=null
    override fun delete(uri: Uri,selection: String?,selectionArgs: Array<out String>?)=0
    override fun update(uri: Uri,values: ContentValues?,selection: String?,selectionArgs: Array<out String>?)=0
}
