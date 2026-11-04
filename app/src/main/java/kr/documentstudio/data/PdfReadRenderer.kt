package kr.documentstudio.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File

/** A single native PDF parse, bounded per-page rasters, and an immutable edit snapshot.
 * The reader owns this off the UI thread; synchronized render/close prevent FD races.
 * Bitmap ownership belongs to each lazy page and is never transferred to this object.
 */
class PdfReadRenderer private constructor(
    private val file: File,
    private val snapshot: File?,
    private val annotatedPages: Set<Int>?
): Closeable {
    private val lock=Any()
    private val descriptor=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer=try{PdfRenderer(descriptor)}catch(failure: Throwable){descriptor.close();throw failure}
    private var closed=false

    fun render(index: Int,requestedWidth: Int): Bitmap=synchronized(lock) {
        check(!closed) {"PDF viewer has closed"}
        require(index in 0 until renderer.pageCount)
        renderer.openPage(index).use {page->
            val (w,h)=PdfRasterBudget.dimensions(page.width,page.height,requestedWidth,2_000_000)
            val image=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
            try {
                image.eraseColor(Color.WHITE);page.render(image,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                if(annotatedPages==null||index in annotatedPages)PdfAnnotations.overlay(file,index,image)
                image
            }catch(failure: Throwable){image.recycle();throw failure}
        }
    }
    override fun close()=synchronized(lock) {
        if(!closed){
            closed=true
            try{renderer.close()}finally{try{descriptor.close()}finally{snapshot?.delete()}}
        }
    }
    companion object {
        fun open(session: Session): PdfReadRenderer {
            var snapshot: File?=null
            try {
                if(session.edits.values.isNotEmpty()){
                    snapshot=File.createTempFile("pdf-reader-",".pdf",session.base.parentFile)
                    PdfEngine.write(session.base,snapshot,emptyList(),false,session.edits.values)
                }
                return PdfReadRenderer(snapshot?:session.base,snapshot,if(snapshot==null)session.pdf?.annotatedPages else null)
            }catch(failure: Throwable){snapshot?.delete();throw failure}
        }
    }
}
