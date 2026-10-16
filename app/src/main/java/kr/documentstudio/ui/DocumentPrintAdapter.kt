package kr.documentstudio.ui
import android.os.*
import android.print.*
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import android.graphics.pdf.PdfRenderer
import com.tom_roush.pdfbox.pdmodel.PDDocument

class DocumentPrintAdapter(private val source: File,private val title: String): PrintDocumentAdapter() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    override fun onLayout(oldAttributes: PrintAttributes?,newAttributes: PrintAttributes?,signal: CancellationSignal,callback: LayoutResultCallback,extras: Bundle?) {
        scope.launch {try {
            val count=withContext(Dispatchers.IO) {ParcelFileDescriptor.open(source,ParcelFileDescriptor.MODE_READ_ONLY).use {fd->PdfRenderer(fd).use {it.pageCount}}}
            if(signal.isCanceled) callback.onLayoutCancelled() else callback.onLayoutFinished(PrintDocumentInfo.Builder(title).setPageCount(count).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(),oldAttributes!=newAttributes)
        } catch(_: CancellationException) {callback.onLayoutCancelled()} catch(e: Exception) {callback.onLayoutFailed(e.message)}}
    }
    override fun onWrite(pages: Array<out PageRange>,destination: ParcelFileDescriptor,signal: CancellationSignal,callback: WriteResultCallback) {
        scope.launch {try {
            val selected=withContext(Dispatchers.IO) {FileOutputStream(destination.fileDescriptor).use {out->writeSelected(source,pages,out) {signal.isCanceled}}}
            callback.onWriteFinished(selected.map {PageRange(it,it)}.toTypedArray())
        } catch(_: CancellationException) {callback.onWriteCancelled()} catch(e: Exception) {callback.onWriteFailed(e.message)}}
    }
    override fun onFinish() {scope.cancel();source.delete()}
    companion object {
        fun writeSelected(source: File,pages: Array<out PageRange>,output: OutputStream,cancelled: ()->Boolean = {false}): List<Int> = PDDocument.load(source).use {input ->
            val selected=(0 until input.numberOfPages).filter {index->pages.any {index in it.start..it.end}}
            require(selected.isNotEmpty()) {"인쇄할 페이지가 없습니다."}
            if(cancelled()) throw CancellationException()
            if(selected.size==input.numberOfPages) source.inputStream().use {stream->val buffer=ByteArray(65536);while(true) {if(cancelled()) throw CancellationException();val n=stream.read(buffer);if(n<0) break;output.write(buffer,0,n)}}
            else PDDocument().use {result ->selected.forEach {index->if(cancelled()) throw CancellationException();val page=input.getPage(index);result.importPage(page).also {it.resources=page.resources}};result.save(output)}
            selected
        }
    }
}
