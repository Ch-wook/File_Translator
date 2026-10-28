package kr.documentstudio.data

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

class OriginalChanged: IllegalArgumentException("외부에서 원본 파일이 변경되었습니다. 다른 이름으로 저장하세요.")
class SaveRecoveryRequired: IOException("이전 저장이 끝났는지 확인해야 합니다. 저장 복구에서 사본을 보관하거나 다른 이름으로 저장하세요.")
class ProviderWriteFailure(val originalRestored: Boolean,cause: Throwable): IOException(
    if(originalRestored)"저장하지 못해 이전 파일을 복원했습니다. 편집 내용과 저장 복구 사본은 보존했습니다."
    else "저장을 완료하지 못했습니다. 이전 파일과 편집 결과를 저장 복구에 보존했습니다.",cause)

data class SaveRecovery(val id: String,val owner: String,val name: String,val mime: String,val uri: String,
    val before: String,val after: String,val phase: String,val created: Long)
data class WriteReceipt(val recovery: SaveRecovery,val directory: File) {
    val original get()=File(directory,"original.bin")
    val payload get()=File(directory,"payload.bin")
}

/**
 * A durable, per-write journal before any external truncate. SAF offers no general
 * cross-application compare-and-swap; readback confirms the provider's current bytes,
 * not a remote cloud commit. Incomplete records are never cache-cleaned or overwritten.
 * Callers must finish only after their own saved model/checkpoint was committed.
 */
class ProviderWrites(private val context: Context) {
    private val root=File(context.filesDir,"provider-recovery").apply {mkdirs()}
    private fun validId(id: String): Boolean=runCatching {UUID.fromString(id).toString()==id}.getOrDefault(false)
    private fun directory(id: String): File {require(validId(id));return File(root,id)}
    private fun manifest(r: SaveRecovery)=JSONObject().apply {
        put("version",1);put("id",r.id);put("owner",r.owner);put("name",r.name);put("mime",r.mime)
        put("uri",r.uri);put("before",r.before);put("after",r.after);put("phase",r.phase);put("created",r.created)
    }
    private fun record(r: SaveRecovery) {DocumentRepository.atomic(File(directory(r.id),"journal.json"),manifest(r).toString().toByteArray())}
    private fun read(dir: File): SaveRecovery?=runCatching {
        require(validId(dir.name))
        val f=File(dir,"journal.json")
        val j=JSONObject(AtomicFile(f).openRead().use {require(it.channel.size() in 1L..32768L);it.bufferedReader().readText()});require(j.getInt("version")==1 && j.getString("id")==dir.name)
        val r=SaveRecovery(dir.name,j.getString("owner"),j.getString("name"),j.getString("mime"),j.getString("uri"),j.getString("before"),j.getString("after"),j.getString("phase"),j.getLong("created"))
        require(r.owner.length<=256 && r.name.length<=512 && r.mime.length<=256 && r.uri.length<=8192)
        require(Uri.parse(r.uri).scheme in setOf("content","file"))
        require(r.before.isEmpty() || r.before.matches(Regex("[0-9a-f]{64}")));require(r.after.isEmpty() || r.after.matches(Regex("[0-9a-f]{64}")))
        r
    }.getOrNull()
    private fun listLocked(owner: String?=null): List<SaveRecovery> =
        root.listFiles().orEmpty().filter {it.isDirectory}.mapNotNull(::read)
            .filter {it.phase!="COMPLETE" && (owner==null || it.owner==owner)}.sortedByDescending {it.created}
    fun list(owner: String?=null): List<SaveRecovery> = locked {listLocked(owner)}
    fun damagedCount(): Int = locked {root.listFiles().orEmpty().count {it.isDirectory && read(it)==null}}
    private fun <T> locked(block: ()->T): T = synchronized(lock) {
        java.io.RandomAccessFile(File(root,"writes.lock"),"rw").use {file->file.channel.lock().use {block()}}
    }
    private fun syncDirectory(dir: File) {
        val fd=android.system.Os.open(dir.path,android.system.OsConstants.O_RDONLY,0)
        try {android.system.Os.fsync(fd)} finally {android.system.Os.close(fd)}
    }
    private fun active(r: SaveRecovery)=r.phase in setOf("PREPARED","WRITING","VERIFIED","RECOVERY_REQUIRED")
    private fun digest(uri: Uri): String=context.contentResolver.openInputStream(uri)?.use {DocumentRepository.digestStreamForRecovery(it)}
        ?: throw IOException("저장 대상 파일을 확인하지 못했습니다.")
    private fun copy(uri: Uri,target: File) {
        val atomic=AtomicFile(target);val out=atomic.startWrite()
        try {
            context.contentResolver.openInputStream(uri)?.use {input->
                val buffer=ByteArray(65536);var total=0L
                while(true){val n=input.read(buffer);if(n<0)break;total+=n;require(total<=LIMIT){"저장 대상이 너무 큽니다."};out.write(buffer,0,n)}
            } ?: throw IOException("저장 대상 파일을 읽지 못했습니다.")
            out.fd.sync();atomic.finishWrite(out)
        } catch(e: Exception){atomic.failWrite(out);throw e}
    }
    /** Internal boundary also allows tests to terminate a writer after PREPARED. */
    internal fun prepare(owner: String,payload: File,uri: Uri,expectedBefore: String?,name: String,mime: String): WriteReceipt = locked {prepareLocked(owner,payload,uri,expectedBefore,name,mime)}
    private fun prepareLocked(owner: String,payload: File,uri: Uri,expectedBefore: String?,name: String,mime: String): WriteReceipt {
        require(owner.length<=256 && uri.toString().length<=8192 && uri.scheme in setOf("content","file"))
        require(payload.isFile && payload.length() in 0..LIMIT)
        val after=DocumentRepository.digest(payload)
        val previous=listLocked().filter {it.uri==uri.toString() && active(it)}
        if(previous.isNotEmpty()) {
            if(previous.size!=1)throw SaveRecoveryRequired()
            val r=previous.single();val receipt=WriteReceipt(r,directory(r.id))
            val current=runCatching {digest(uri)}.getOrNull()
            if(r.owner==owner && r.after==after && current==r.after &&
                receipt.payload.isFile && DocumentRepository.digest(receipt.payload)==r.after) {
                val verified=r.copy(phase="VERIFIED");record(verified);return WriteReceipt(verified,receipt.directory)
            }
            if(r.owner==owner && r.after==after && current==r.before &&
                (expectedBefore==null || expectedBefore==r.before) &&
                receipt.payload.isFile && receipt.original.isFile &&
                DocumentRepository.digest(receipt.payload)==r.after && DocumentRepository.digest(receipt.original)==r.before) return receipt
            throw SaveRecoveryRequired()
        }
        require(root.listFiles().orEmpty().count {it.isDirectory}<128) {"저장 복구 사본이 많습니다. 보관한 사본을 확인한 뒤 정리하세요."}
        require(root.usableSpace>payload.length()*2+1048576) {"저장 공간이 부족합니다. 공간을 확보한 뒤 다시 저장하세요."}
        val id=UUID.randomUUID().toString();val dir=directory(id);require(dir.mkdirs())
        var r=SaveRecovery(id,owner,name.take(512),mime.take(256),uri.toString(),"","","PREPARING",System.currentTimeMillis())
        record(r)
        try {
            DocumentRepository.atomicCopy(payload,File(dir,"payload.bin"))
            require(DocumentRepository.digest(File(dir,"payload.bin"))==after) {"저장 사본이 변경되었습니다. 다시 시도하세요."}
            r=r.copy(after=after);record(r)
            copy(uri,File(dir,"original.bin"))
            val before=DocumentRepository.digest(File(dir,"original.bin"))
            if(expectedBefore!=null && before!=expectedBefore){dir.deleteRecursively();throw OriginalChanged()}
            r=r.copy(before=before,phase="PREPARED");record(r)
            syncDirectory(dir);syncDirectory(root)
            return WriteReceipt(r,dir)
        } catch(e: OriginalChanged){throw e}
        catch(e: Exception) {
            // Target was not touched. Keep any complete payload for recovery.
            record(r.copy(phase="PREPARATION_FAILED"));throw e
        }
    }
    private fun write(file: File,uri: Uri) {
        context.contentResolver.openOutputStream(uri,"rwt")?.use {out->file.inputStream().use {it.copyTo(out,65536)}}
            ?: throw IOException("파일을 기록할 수 없습니다.")
    }
    fun publish(owner: String,payload: File,uri: Uri,expectedBefore: String?=null,name: String="저장 복구본",mime: String="application/octet-stream",legacyBackup: File?=null): WriteReceipt = locked {
        val receipt=prepareLocked(owner,payload,uri,expectedBefore,name,mime)
        if(receipt.recovery.phase=="VERIFIED")return@locked receipt
        if(legacyBackup!=null)DocumentRepository.atomicCopy(receipt.original,legacyBackup)
        var r=receipt.recovery.copy(phase="WRITING");record(r)
        syncDirectory(receipt.directory);syncDirectory(root)
        try {
            if(digest(uri)!=r.before)throw OriginalChanged()
            write(receipt.payload,uri)
            if(digest(uri)!=r.after)throw ReadbackMismatch()
            r=r.copy(phase="VERIFIED");record(r);WriteReceipt(r,receipt.directory)
        } catch(e: Exception) {
            // A mismatching successful readback may be another application's new
            // content. Never roll that content back without an explicit user action.
            val restored=if(e is OriginalChanged || e is ReadbackMismatch)false else runCatching {
                if(digest(uri)!=r.before)write(receipt.original,uri)
                require(digest(uri)==r.before)
            }.isSuccess
            record(r.copy(phase=if(restored)"ROLLED_BACK" else "RECOVERY_REQUIRED"))
            if(e is OriginalChanged)throw e
            throw ProviderWriteFailure(restored,e)
        }
    }
    fun finish(receipt: WriteReceipt,checkpointDirectories: List<File> = emptyList()) = locked {
        require(receipt.directory.canonicalFile==directory(receipt.recovery.id).canonicalFile)
        val actual=read(receipt.directory) ?: return@locked
        checkpointDirectories.distinct().forEach(::syncDirectory)
        require(actual.phase=="VERIFIED" && actual.after==receipt.recovery.after)
        record(actual.copy(phase="COMPLETE"));syncDirectory(receipt.directory)
        // Failure to delete completed housekeeping must not turn a verified save
        // into a false failure. Incomplete records are never deleted here.
        runCatching {receipt.directory.deleteRecursively();syncDirectory(root)};Unit
    }
    fun snapshot(id: String,original: Boolean): File = locked {
        val dir=directory(id);val r=read(dir) ?: throw IOException("복구 기록을 확인하지 못했습니다.")
        val expected=if(original)r.before else r.after
        val file=File(dir,if(original)"original.bin" else "payload.bin")
        require(expected.isNotEmpty() && file.isFile && DocumentRepository.digest(file)==expected) {"복구 사본의 검증에 실패했습니다."}
        file
    }
    /** Only called after a visible user confirmation; never deletes the external URI. */
    fun discard(id: String)=locked{require(directory(id).deleteRecursively());Unit}
    companion object {private val lock=Any();private const val LIMIT=128L*1024*1024}
    private class ReadbackMismatch: IOException("저장 결과 검증에 실패했습니다.")
}
