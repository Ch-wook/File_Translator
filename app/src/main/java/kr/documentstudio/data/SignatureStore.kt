package kr.documentstudio.data

import android.content.Context
import android.graphics.BitmapFactory
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SignatureStoreUnavailable(val previousAvailable: Boolean): IOException("서명 보관함을 확인하지 못했습니다. 기존 자료는 보존했습니다. 저장 복구에서 확인하세요.")

/** Private, bounded signature library; damaged data is never interpreted as an empty library. */
class SignatureStore(private val context: Context) {
    private val current=File(context.filesDir,"signatures.json")
    private val previous=File(context.filesDir,"signatures-last-good.json")
    private val damaged=File(context.filesDir,"signature-recovery").apply {mkdirs()}
    private fun <T> locked(block: ()->T): T=synchronized(lock) {
        RandomAccessFile(File(context.filesDir,"signatures.lock"),"rw").use {f->f.channel.lock().use {block()}}
    }
    private fun exists(file: File)=file.isFile || File(file.path+".bak").isFile
    private fun readFile(file: File)=AtomicFile(file).openRead().use {require(it.channel.size() in 2L..LIMIT.toLong());it.bufferedReader().readText()}
    internal fun validate(value: String): String {
        require(value.toByteArray().size<=LIMIT)
        val all=JSONArray(value);require(all.length()<=20)
        val ids=mutableSetOf<String>();var points=0
        for(i in 0 until all.length()){
            val s=all.getJSONObject(i);require(s.getString("id").length in 1..80 && ids.add(s.getString("id")))
            require(s.getString("name").length in 1..80)
            val png=s.getString("png");require(png.startsWith(PREFIX))
            val bytes=Base64.decode(png.substring(PREFIX.length),Base64.DEFAULT)
            require(bytes.size>=8 && bytes.copyOfRange(0,8).contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10)))
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            require(bounds.outWidth in 1..1200 && bounds.outHeight in 1..700)
            require(bytes.takeLast(12).toByteArray().contentEquals(byteArrayOf(0,0,0,0,73,69,78,68,-82,66,96,-126)))
            val decoded=BitmapFactory.decodeByteArray(bytes,0,bytes.size) ?: throw IllegalArgumentException("서명 그림을 확인하지 못했습니다.")
            try {require(decoded.width==bounds.outWidth && decoded.height==bounds.outHeight)} finally {decoded.recycle()}
            require(s.getInt("w")==bounds.outWidth && s.getInt("h")==bounds.outHeight)
            val strokes=s.optJSONArray("strokes") ?: JSONArray();require(strokes.length()<=1000)
            for(j in 0 until strokes.length()){
                val stroke=strokes.getJSONObject(j);require(stroke.getString("color").matches(Regex("#[a-fA-F0-9]{6}")))
                val width=stroke.getDouble("width");require(width.isFinite() && width in 1.0..20.0)
                val samples=stroke.getJSONArray("points");points+=samples.length();require(points<=100000)
                for(k in 0 until samples.length()){
                    val p=samples.getJSONObject(k)
                    for(key in listOf("x","y")){val n=p.getDouble(key);require(n.isFinite() && kotlin.math.abs(n)<=12000)}
                    val pressure=p.getDouble("p");require(pressure.isFinite() && pressure in 0.0..4.0)
                }
            }
        }
        return value
    }
    private fun preserveDamaged() {
        if(current.isFile && current.length()<=LIMIT){
            val target=File(damaged,DocumentRepository.digest(current)+".json")
            if(!target.exists())DocumentRepository.atomicCopy(current,target)
        }
    }
    private fun previousValid()=runCatching {validate(readFile(previous));true}.getOrDefault(false)
    fun read(): String=locked {
        if(!exists(current))"[]" else try {validate(readFile(current))}
        catch(_: Exception){preserveDamaged();throw SignatureStoreUnavailable(previousValid())}
    }
    fun write(value: String)=locked {
        validate(value)
        if(exists(current)){
            val old=try {validate(readFile(current))}catch(_: Exception){preserveDamaged();throw SignatureStoreUnavailable(previousValid())}
            DocumentRepository.atomic(previous,old.toByteArray())
        }
        DocumentRepository.atomic(current,value.toByteArray())
        require(validate(readFile(current))==value)
    }
    fun canRestorePrevious(): Boolean=locked {previousValid()}
    fun restorePrevious()=locked {
        val old=validate(readFile(previous));preserveDamaged()
        DocumentRepository.atomic(current,old.toByteArray());require(readFile(current)==old)
    }
    fun recoveryArchive(): File=locked {
        val parts=mutableListOf<Pair<String,File>>()
        if(current.isFile)parts.add("current.json" to current)
        if(previous.isFile)parts.add("previous.json" to previous)
        damaged.listFiles().orEmpty().filter {it.isFile && it.name.matches(Regex("[a-f0-9]{64}\\.json"))}.forEach {parts.add(("damaged/"+it.name) to it)}
        require(parts.isNotEmpty() && parts.size<=128 && parts.sumOf {it.second.length()}<=128L*1024*1024)
        val output=File(context.cacheDir,"conversions/signature-recovery-"+UUID.randomUUID()+".zip").apply {parentFile!!.mkdirs()}
        try {
            ZipOutputStream(output.outputStream()).use {zip->parts.forEach {(name,file)->zip.putNextEntry(ZipEntry(name));file.inputStream().use {it.copyTo(zip,65536)};zip.closeEntry()}}
            java.util.zip.ZipFile(output).use {require(it.size()==parts.size)}
            output
        } catch(e: Exception){output.delete();throw e}
    }
    companion object {private val lock=Any();private const val LIMIT=4*1024*1024;private const val PREFIX="data:image/png;base64,"}
}
