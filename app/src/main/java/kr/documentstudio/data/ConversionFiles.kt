package kr.documentstudio.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Validate before touching a selected output; verify provider readback and restore on failure. */
object ConversionFiles {
    fun digest(file: File): String {val digest=MessageDigest.getInstance("SHA-256");file.inputStream().use {input->val buffer=ByteArray(65536);while(true){val count=input.read(buffer);if(count<0)break;digest.update(buffer,0,count)}};return digest.digest().joinToString("") {"%02x".format(it)}}
    fun publish(context: Context,stage: File,uri: Uri,original: String?=null) {
        require(!DocumentUris.same(uri.toString(),original)) {"원본과 다른 파일을 선택하세요."}
        require(stage.length() in 1..128L*1024*1024) {"결과 파일 크기가 올바르지 않습니다."}
        val writes=ProviderWrites(context)
        val receipt=writes.publish("conversion:"+stage.name,stage,uri,name=stage.name,mime="application/octet-stream")
        writes.finish(receipt)
    }
}

/** Complete page images from the layout engine, not screenshots of its visible viewport. */
class RasterPdfSession(private val file: File,private val count: Int): java.io.Closeable {
    private val pdf=com.tom_roush.pdfbox.pdmodel.PDDocument(com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly().setTempDir(file.parentFile))
    private var pages=0
    private var inputBytes=0L
    init {require(count in 1..300)}
    fun page(json: String) {
        require(pages<count && json.length<=24*1024*1024)
        val data=JSONObject(json);val width=data.getDouble("width");val height=data.getDouble("height")
        require(width.isFinite() && height.isFinite() && width in 1.0..3000.0 && height in 1.0..3000.0)
        val bytes=Base64.decode(data.getString("png"),Base64.DEFAULT)
        inputBytes+=bytes.size;require(inputBytes<=100L*1024*1024) {"페이지 이미지가 너무 큽니다. 문서를 나누어 변환하세요."}
        val opts=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,opts)
        require(opts.outWidth>0 && opts.outHeight>0 && opts.outWidth.toLong()*opts.outHeight<=12000000)
        val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size) ?: error("페이지 이미지를 읽지 못했습니다.")
        try {
            val page=com.tom_roush.pdfbox.pdmodel.PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle(width.toFloat(),height.toFloat()))
            pdf.addPage(page)
            val image=com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(pdf,bitmap)
            com.tom_roush.pdfbox.pdmodel.PDPageContentStream(pdf,page).use {out->
                out.setNonStrokingColor(255,255,255);out.addRect(0f,0f,width.toFloat(),height.toFloat());out.fill()
                out.drawImage(image,0f,0f,width.toFloat(),height.toFloat())
            }
            pages++
        } finally {bitmap.recycle()}
    }
    fun finish(): Int {
        require(pages==count);pdf.save(file)
        val info=PdfEngine.inspect(file);require(info.pages==count) {"PDF 페이지 수가 일치하지 않습니다."}
        return count
    }
    override fun close(){pdf.close()}
}
