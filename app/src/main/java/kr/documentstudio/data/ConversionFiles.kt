package kr.documentstudio.data

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Validate before touching a selected output; verify provider readback and restore on failure. */
object ConversionFiles {
    fun digest(file: File): String {val digest=MessageDigest.getInstance("SHA-256");file.inputStream().use {input->val buffer=ByteArray(65536);while(true){val count=input.read(buffer);if(count<0)break;digest.update(buffer,0,count)}};return digest.digest().joinToString("") {"%02x".format(it)}}
    fun publish(context: Context,stage: File,uri: Uri,original: String?=null) {
        require(uri.toString()!=original) {"원본과 다른 파일을 선택하세요."}
        require(stage.length() in 1..128L*1024*1024) {"결과 파일 크기가 올바르지 않습니다."}
        val backup=File.createTempFile("conversion-backup-",".bin",context.cacheDir)
        val readback=File.createTempFile("conversion-check-",".bin",context.cacheDir)
        var backed=false
        try {
            try {context.contentResolver.openInputStream(uri)?.use {input->backup.outputStream().use {input.copyTo(it)}};backed=true} catch(_:java.io.FileNotFoundException) {}
            context.contentResolver.openOutputStream(uri,"rwt")?.use {out->stage.inputStream().use {it.copyTo(out)}} ?: error("결과 파일을 기록할 수 없습니다.")
            context.contentResolver.openInputStream(uri)?.use {input->readback.outputStream().use {input.copyTo(it)}} ?: error("저장 결과를 다시 읽을 수 없습니다.")
            require(digest(stage)==digest(readback)) {"저장 결과 검증에 실패했습니다."}
        } catch(e:Exception) {
            if(backed)runCatching {context.contentResolver.openOutputStream(uri,"rwt")?.use {out->backup.inputStream().use {it.copyTo(out)}}}
            throw e
        } finally {backup.delete();readback.delete()}
    }
}

/** Complete page images from the layout engine, not screenshots of its visible viewport. */
class RasterPdfSession(private val file: File,private val count: Int): java.io.Closeable {
    private val pdf=PdfDocument()
    private var pages=0
    init {require(count in 1..300)}
    fun page(json: String) {
        require(pages<count && json.length<=24*1024*1024)
        val data=JSONObject(json);val width=data.getDouble("width");val height=data.getDouble("height")
        require(width.isFinite() && height.isFinite() && width in 1.0..3000.0 && height in 1.0..3000.0)
        val bytes=Base64.decode(data.getString("png"),Base64.DEFAULT)
        val opts=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,opts)
        require(opts.outWidth>0 && opts.outHeight>0 && opts.outWidth.toLong()*opts.outHeight<=12000000)
        val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size) ?: error("페이지 이미지를 읽지 못했습니다.")
        try {val page=pdf.startPage(PdfDocument.PageInfo.Builder(kotlin.math.ceil(width).toInt(),kotlin.math.ceil(height).toInt(),++pages).create())
            page.canvas.drawColor(Color.WHITE);page.canvas.drawBitmap(bitmap,null,RectF(0f,0f,width.toFloat(),height.toFloat()),android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG));pdf.finishPage(page)
        } finally {bitmap.recycle()}
    }
    fun finish(): Int {
        require(pages==count);file.outputStream().use {pdf.writeTo(it)}
        val info=PdfEngine.inspect(file);require(info.pages==count) {"PDF 페이지 수가 일치하지 않습니다."}
        return count
    }
    override fun close(){pdf.close()}
}
