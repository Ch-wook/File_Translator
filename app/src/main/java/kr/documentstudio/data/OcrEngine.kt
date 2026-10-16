package kr.documentstudio.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OcrLine(val text: String,val left: Float,val top: Float,val right: Float,val bottom: Float)
data class OcrPage(val index: Int,val lines: List<OcrLine>) {val text get()=lines.joinToString("\n") {it.text}}
object OcrEngine {
    private val gate=Semaphore(1)
    /** Owns the bitmap until ML Kit completes, including when the caller cancels. */
    suspend fun recognizeOwned(bitmap: Bitmap,korean: Boolean): List<OcrLine> {
        try {require(bitmap.width.toLong()*bitmap.height<=8_000_000);gate.acquire()} catch(e: Exception) {bitmap.recycle();throw e}
        val recognizer=try {if(korean) TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) else TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)} catch(e: Exception) {bitmap.recycle();gate.release();throw e}
        return suspendCancellableCoroutine {continuation ->
            try {recognizer.process(InputImage.fromBitmap(bitmap,0)).addOnCompleteListener(Executor {it.run()}) {task ->
                val outcome=runCatching {
                    if(!task.isSuccessful) throw task.exception ?: IllegalStateException("문자 인식에 실패했습니다.")
                    val lines=task.result.textBlocks.flatMap {it.lines};require(lines.size<=1000 && lines.sumOf {it.text.length}<=50000) {"인식 결과 용량 제한을 초과했습니다."}
                    lines.mapNotNull {line->line.boundingBox?.let {box->
                        val l=(box.left.toFloat()/bitmap.width).coerceIn(0f,1f);val t=(box.top.toFloat()/bitmap.height).coerceIn(0f,1f);val r=(box.right.toFloat()/bitmap.width).coerceIn(0f,1f);val b=(box.bottom.toFloat()/bitmap.height).coerceIn(0f,1f)
                        if(r>l && b>t && line.text.isNotBlank()) OcrLine(line.text,l,t,r,b) else null
                    }}
                }
                try {recognizer.close()} finally {bitmap.recycle();gate.release()}
                if(continuation.isActive) continuation.resumeWith(outcome)
            }} catch(e: Exception) {recognizer.close();bitmap.recycle();gate.release();if(continuation.isActive) continuation.resumeWithException(e)}
        }
    }
    fun image(context: Context,uri: Uri): Bitmap {
        val data=context.contentResolver.openInputStream(uri)?.use {input->
            val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(65536);var size=0
            while(true) {val n=input.read(buffer);if(n<0) break;size+=n;require(size<=16*1024*1024) {"이미지는 16 MiB 이하로 선택하세요."};out.write(buffer,0,n)};out.toByteArray()
        } ?: error("이미지 읽기 권한이 없습니다.")
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(data))) {decoder,info,_->
            require(info.size.width.toLong()*info.size.height<=100_000_000) {"이미지 해상도 제한을 초과했습니다."}
            decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize(kotlin.math.ceil(maxOf(info.size.width,info.size.height)/2400.0).toInt().coerceAtLeast(1))
        }
    }
    fun json(pages: List<OcrPage>)=JSONArray().apply {pages.forEach {page->put(JSONObject().apply {put("page",page.index);put("lines",JSONArray().apply {page.lines.forEach {line->put(JSONArray(listOf(line.text,line.left,line.top,line.right,line.bottom)))}})})}}.toString()
}
