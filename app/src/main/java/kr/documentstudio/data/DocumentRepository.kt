package kr.documentstudio.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kr.documentstudio.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class Recent(val id: String, val name: String, val uri: String?, val modified: Long, val dirty: Boolean, val favorite: Boolean,val hidden: Boolean=false)
data class Session(val id: String, val name: String, val format: Format, val uri: String?, val sourceHash: String?,
    val base: File, val textContent: TextContent?, val pack: PackageDocument?, val pdf: PdfInfo?, val edits: Edits, val saved: Edits,
    val legacy: LegacyDocument? = null, val revision: Long=0, val savedRevision: Long=0,val recoveryWarning: String?=null,val readOnlyReason: String?=null) {
    val dirty get() = edits != saved || revision!=savedRevision || uri == null
    val sections get() = pack?.sections ?: legacy?.sections ?: emptyList()
}

class BackupPasswordRequired(val uri: Uri): IllegalArgumentException("암호화 백업의 암호를 입력하세요.")

class DocumentRepository(private val context: Context) {
    private val root = File(context.filesDir, "documents").apply { mkdirs() }
    private val index = File(root, "index.json")
    private val providerWrites=ProviderWrites(context)
    private data class CheckpointStamp(val key: Any,val modified: java.nio.file.attribute.FileTime,val size: Long)
    private data class CheckpointSnapshot(val session: java.lang.ref.WeakReference<Session>,val stamp: CheckpointStamp)
    private val checkpointSnapshots=java.util.LinkedHashMap<String,CheckpointSnapshot>(4,.75f,true)
    private fun checkpointStamp(file: File): CheckpointStamp? = try {
        val attributes=java.nio.file.Files.readAttributes(file.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java,java.nio.file.LinkOption.NOFOLLOW_LINKS)
        if(!attributes.isRegularFile) null else attributes.fileKey()?.let {CheckpointStamp(it,attributes.lastModifiedTime(),attributes.size())}
    } catch(_: Exception) {null}
    private fun rememberCheckpoint(s: Session,file: File) {
        checkpointStamp(file)?.let {checkpointSnapshots[file.path]=CheckpointSnapshot(java.lang.ref.WeakReference(s),it)}
        while(checkpointSnapshots.size>4)checkpointSnapshots.remove(checkpointSnapshots.keys.first())
    }
    // Activities and recovery tests may own different repository instances.
    // They still share one index and must serialize the complete transaction.
    private val mutex = repositoryMutex
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }
    suspend fun recents(includeHidden: Boolean=false): List<Recent> = io { readIndex().filter {includeHidden || !it.hidden} }
    suspend fun hideRecent(id: String,hidden: Boolean) = io {writeIndex(readIndex().map {if(it.id==id)it.copy(hidden=hidden) else it})}
    suspend fun cleanupTemporary(active: Set<File>): CleanupResult = io {TemporaryFiles.cleanup(context.cacheDir,root,active)}
    private fun readIndex(): List<Recent> {
        if(!index.exists()) return emptyList()
        val list = JSONArray(AtomicFile(index).openRead().use { it.bufferedReader().readText() })
        return (0 until list.length()).map { i -> list.getJSONObject(i).let { j ->
            Recent(j.getString("id"), j.getString("name"), j.optString("uri").takeIf { it.isNotEmpty() }, j.getLong("modified"), j.getBoolean("dirty"), j.optBoolean("favorite"),j.optBoolean("hidden"))
        } }
    }
    private fun writeIndex(rows: List<Recent>) = atomic(index, JSONArray().apply { rows.forEach { r -> put(JSONObject().apply {
        put("id",r.id); put("name",r.name); put("uri",r.uri ?: ""); put("modified",r.modified); put("dirty",r.dirty); put("favorite",r.favorite);put("hidden",r.hidden)
    }) } }.toString().toByteArray())
    suspend fun favorite(id: String) = io { writeIndex(readIndex().map { if(it.id==id) it.copy(favorite=!it.favorite) else it }) }
    private fun remember(session: Session) {
        val old = readIndex()
        val previous=old.firstOrNull {it.id==session.id}
        val favorite=previous?.favorite ?: false
        val hidden=previous?.hidden ?: false
        val pending=File(session.base.parentFile,"office-pending.json").exists()
        writeIndex((listOf(Recent(session.id,session.name,session.uri,System.currentTimeMillis(),session.dirty || pending,favorite,hidden)) + old.filter { it.id != session.id }))
    }
    suspend fun create(format: Format): Session = io {
        require(format != Format.DOC) { "구형 DOC 새 문서는 지원하지 않습니다. DOCX를 생성하거나 기존 DOC를 열어 주세요." }
        val id = UUID.randomUUID().toString()
        val dir = File(root,id).apply { mkdirs() }; val base = File(dir,"base.bin")
        when(format) {
            Format.TXT, Format.MD -> atomic(base, byteArrayOf())
            Format.PDF -> ExportEngine.blank(base)
            Format.XLS,Format.PPT,Format.HWP -> LegacyDocument.create(format,base)
            else -> Templates.create(format,base)
        }
        val s = build(id,"새 문서.${format.extension}",format,null,null,base)
        checkpointInternal(s); s
    }
    private fun showRecent(id: String) {val all=readIndex();if(all.any {it.id==id && it.hidden})writeIndex(all.map {if(it.id==id)it.copy(hidden=false) else it})}
    suspend fun open(uri: Uri, encoding: String = "UTF-8",password: CharArray?=null): Session = io {openInternal(uri,encoding,password).also {showRecent(it.id)}}
    private fun openInternal(uri: Uri,encoding: String,password: CharArray?): Session {
        val existing = readIndex().firstOrNull {DocumentUris.same(it.uri,uri.toString())}
        if(existing!=null && (existing.dirty || File(File(root,existing.id),"office-pending.json").exists())) return restoreInternal(existing.id)
        val name = displayName(uri)
        val id = existing?.id ?: UUID.randomUUID().toString()
        val dir = File(root,id).apply { mkdirs() }
        val incoming = File(dir,"incoming.bin")
        val unlocked=File(dir,"backup-unlock-${UUID.randomUUID()}.zip")
        return try {
            copyUri(uri,incoming)
            if(EncryptedBackup.isEncrypted(incoming)) {
                if(password==null) throw BackupPasswordRequired(uri)
                EncryptedBackup.decrypt(incoming,unlocked,password)
                return java.util.zip.ZipFile(unlocked).use {importBackup(it)}
            }
            if(name.endsWith(".zip",ignoreCase=true)) {
                java.util.zip.ZipFile(incoming).use {zip ->
                    if(zip.getEntry("base.bin")!=null && zip.getEntry("state.json")!=null) return importBackup(zip)
                }
            }
            val format = FormatDetector.detect(incoming,name,runCatching { context.contentResolver.getType(uri) }.getOrNull(),encoding)
            val old = existing?.let { restoreInternal(it.id) }
            val hash = digest(incoming)
            if(old!=null && old.sourceHash==hash && old.format==format) {
                // We verified the provider bytes. Reuse the already restored model
                // rather than rewriting the baseline and parsing the same file twice.
                remember(old);return old
            }
            val strokes = if(format==Format.PDF) PdfEngine.importOwned(incoming) else old?.takeIf {it.format==format}?.edits?.strokes ?: emptyList()
            val layers = if(format==Format.PDF) PdfEngine.importLayers(incoming) else old?.takeIf {it.format==format}?.edits?.layers ?: listOf(InkLayer())
            if(format==Format.PDF) { PdfEngine.inspect(incoming); PdfEngine.cleanOwned(incoming) }
            val base = File(dir,"${UUID.randomUUID()}.bin"); atomicCopy(incoming,base)
            val s = build(id,name,format,uri.toString(),hash,base,encoding).let {
                val edits = it.edits.copy(strokes=strokes,layers=layers,paper=old?.edits?.paper ?: Paper.BLANK); it.copy(edits=edits,saved=edits)
            }
            checkpointInternal(s); s
        } finally { incoming.delete();unlocked.delete() }
    }
    suspend fun restore(id: String): Session = io { restoreInternal(id).also { remember(it) } }
    /** Clean recents must check the provider; unsaved and native working copies win. */
    suspend fun reopenRecent(id: String,encoding: String="UTF-8"): Session = io {
        val cached=restoreInternal(id);showRecent(id)
        val pending=File(cached.base.parentFile,"office-pending.json").exists()
        if(cached.dirty || pending || providerWrites.list(id).isNotEmpty() || cached.uri==null)
            return@io cached.also {remember(it)}
        try {openInternal(Uri.parse(cached.uri),encoding,null)}
        catch(_: SecurityException){cached.copy(recoveryWarning="원본 접근 권한이 없어 기기의 마지막 사본을 열었습니다. 파일을 다시 선택하거나 다른 이름으로 저장하세요.").also {remember(it)}}
        catch(_: java.io.FileNotFoundException){cached.copy(recoveryWarning="원본 파일을 찾지 못해 기기의 마지막 사본을 열었습니다. 파일 위치를 확인하거나 다른 이름으로 저장하세요.").also {remember(it)}}
    }
    suspend fun saveRecoveries(): List<SaveRecovery> = io {providerWrites.list()}
    suspend fun damagedSaveRecords(): Int = io {providerWrites.damagedCount()}
    suspend fun discardSaveRecovery(id: String) = io {providerWrites.discard(id)}
    suspend fun exportSaveRecovery(id: String,original: Boolean,destination: Uri) = io {
        val row=providerWrites.list().firstOrNull {it.id==id} ?: error("복구 기록을 찾지 못했습니다.")
        require(!DocumentUris.same(row.uri,destination.toString())) {"복구 사본은 원본과 다른 새 파일에 보관하세요."}
        val receipt=providerWrites.publish("recovery-export:$id:$original",providerWrites.snapshot(id,original),destination,
            name=row.name,mime=row.mime)
        providerWrites.finish(receipt)
    }
    suspend fun convertHangul(source: Session): Session = io {
        require(source.format==Format.HWP) {"HWP 문서에서 변환해 주세요."}
        require(source.readOnlyReason==null) {source.readOnlyReason ?: "읽기 전용 문서는 변환하지 않습니다."}
        checkpointInternal(source)
        val id=UUID.randomUUID().toString();val dir=File(root,id).apply {mkdirs()}
        try {
            val provenance=File(dir,"preserved-backup/conversion").apply {mkdirs()}
            val working=File(provenance,"working.hwp")
            if(source.edits.values.isEmpty() && source.edits.bold.isEmpty() && source.edits.styles.isEmpty()) atomicCopy(source.base,working)
            else source.legacy!!.write(working,source.edits)
            val base=File(dir,"base.bin");val report=HangulConversion.convert(working,base)
            atomicCopy(source.base,File(provenance,"original.hwp"))
            atomic(File(provenance,"report.json"),conversionJson(report).toString().toByteArray())
            val target=build(id,source.name.substringBeforeLast('.',source.name)+"-변환.hwpx",Format.HWPX,null,null,base)
            val anchors=source.sections.mapIndexed {i,s->s.id to target.sections[i].id}.toMap()
            val strokes=source.edits.strokes.map {stroke->stroke.copy(anchor=anchors[stroke.anchor] ?: error("필기 구역을 변환 결과에 연결하지 못했습니다."))}
            val result=target.copy(edits=target.edits.copy(strokes=strokes,layers=source.edits.layers,paper=source.edits.paper))
            checkpointInternal(result);result
        } catch(e: Exception) {dir.deleteRecursively();throw e}
    }
    /** A historical report: later edits do not re-certify the conversion's layout or content. */
    suspend fun conversionReport(s: Session): HangulConversionReport? = io {
        val dir=File(s.base.parentFile,"preserved-backup/conversion");val file=File(dir,"report.json")
        if(!file.isFile) return@io null
        require(file.length() in 1..16384) {"변환 보고서 크기가 올바르지 않습니다."}
        val j=JSONObject(file.readText());require(j.getInt("version")==1)
        fun count(name: String)=j.getInt(name).also {require(it in 0..10000)}
        fun hash(name: String)=j.getString(name).also {require(it.matches(Regex("[0-9a-f]{64}")))}
        val report=HangulConversionReport(count("sections"),count("paragraphs"),count("tables"),count("cells"),count("pictures"),count("binaries"),
            count("headers"),count("footers"),count("footnotes"),count("endnotes"),count("equations"),hash("sourceSha256"),hash("resultSha256"))
        val working=File(dir,"working.hwp")
        require(working.isFile && working.length()<=16L*1024*1024 && digest(working)==report.sourceSha256) {"변환 원본 보존 자료가 보고서와 일치하지 않습니다."}
        report
    }
    private fun conversionJson(r: HangulConversionReport)=JSONObject().apply {
        put("version",1);put("converter","hwp2hwpx-edc0527/hwpxlib-1.0.9");put("sections",r.sections);put("paragraphs",r.paragraphs)
        put("tables",r.tables);put("cells",r.cells);put("pictures",r.pictures);put("binaries",r.binaries);put("headers",r.headers);put("footers",r.footers)
        put("footnotes",r.footnotes);put("endnotes",r.endnotes);put("equations",r.equations);put("sourceSha256",r.sourceSha256);put("resultSha256",r.resultSha256)
    }
    private fun importBackup(zip: java.util.zip.ZipFile): Session {
        require(zip.size()<=256) {"백업 파트 수 제한(256개)을 초과했습니다."}
        require(zip.entries().toList().map {it.name}.distinct().size==zip.size()) {"중복 백업 파트가 있습니다."}
        val entries=zip.entries().toList()
        require(entries.all {entry -> !entry.isDirectory && (entry.name in setOf("base.bin","state.json","provider-backup.bin") ||
            entry.name in setOf("conversion/original.hwp","conversion/working.hwp","conversion/report.json") ||
            entry.name.startsWith("office-recovery/") && entry.name.removePrefix("office-recovery/").split('/').all {it.matches(Regex("[a-zA-Z0-9._-]+")) && it!="." && it!=".."})}) {"지원하지 않거나 안전하지 않은 백업 경로가 있습니다."}
        require(entries.all {it.size in 0..128L*1024*1024} && entries.sumOf {it.size}<=256L*1024*1024) {"백업의 압축 해제 용량 제한(256 MiB)을 초과했습니다."}
        fun read(name: String,limit: Long): ByteArray {
            val entry=zip.getEntry(name) ?: error("백업 파트 누락")
            require(entry.size in 0..limit) {"백업 크기 제한 초과"}
            return zip.getInputStream(entry).use {input ->
                val out=java.io.ByteArrayOutputStream();val buf=ByteArray(65536);var total=0L
                while(true) {val n=input.read(buf);if(n<0) break;total+=n;require(total<=limit);out.write(buf,0,n)}
                out.toByteArray()
            }
        }
        val j=JSONObject(read("state.json",16L*1024*1024).toString(Charsets.UTF_8))
        require(j.getInt("version")==1) {"지원하지 않는 백업 버전"}
        val id=UUID.randomUUID().toString();val dir=File(root,id).apply {mkdirs()};val base=File(dir,"base.bin")
        try {
            atomic(base,read("base.bin",128L*1024*1024))
            val name=j.getString("name");val encoding=j.optString("encoding","UTF-8")
            val format=FormatDetector.detect(base,name,null,encoding)
            require(format.name==j.getString("format")) {"백업의 문서 형식이 일치하지 않습니다."}
            val s=build(id,"복구-$name",format,null,null,base,encoding).copy(edits=StateCodec.decode(j.getJSONObject("edits")),saved=StateCodec.decode(j.getJSONObject("saved")),revision=j.optLong("revision"),savedRevision=j.optLong("savedRevision"))
            // Quarantine imported recovery files. Pending markers contain paths
            // from another session and must never run automatically here.
            entries.filter {it.name !in setOf("base.bin","state.json")}.forEach {entry ->
                val target=File(File(dir,"preserved-backup"),entry.name)
                target.parentFile!!.mkdirs()
                zip.getInputStream(entry).use {input -> target.outputStream().use {out ->
                    val buffer=ByteArray(65536);var count=0L
                    while(true) {val n=input.read(buffer);if(n<0) break;count+=n;require(count<=entry.size);out.write(buffer,0,n)}
                    require(count==entry.size) {"백업 복구 자료가 잘렸습니다."}
                } }
            }
            checkpointInternal(s);return s
        } catch(e: Exception) {dir.deleteRecursively();throw e}
    }
    private fun restoreInternal(id: String): Session {
        require(id.matches(Regex("[a-zA-Z0-9-]+")))
        val dir = File(root,id)
        val j = JSONObject(AtomicFile(File(dir,"state.json")).openRead().use { it.bufferedReader().readText() })
        require(j.getInt("version")==1)
        val s = build(id,j.getString("name"),Format.valueOf(j.getString("format")),j.optString("uri").takeIf { it.isNotEmpty() },
            j.optString("sourceHash").takeIf { it.isNotEmpty() },File(dir,j.optString("baseFile","base.bin").also {require(it.matches(Regex("[a-zA-Z0-9.-]+")) && !it.contains(".."))}),j.optString("encoding","UTF-8"))
        val restored=s.copy(edits=StateCodec.decode(j.getJSONObject("edits")),saved=StateCodec.decode(j.getJSONObject("saved")),revision=j.optLong("revision"),savedRevision=j.optLong("savedRevision"))
        val accepted=if(File(dir,"office-pending.json").exists()) try {acceptOfficeInternal(restored)} catch(e: Exception) {
            restored.copy(recoveryWarning="정밀 편집이 끝났는지 확인하지 못해 이전 작업과 복구 파일을 보존했습니다.")
        } else restored
        return if(providerWrites.list(id).any {it.phase in setOf("PREPARED","WRITING","VERIFIED","RECOVERY_REQUIRED")})
            accepted.copy(recoveryWarning="중단된 저장 기록이 있습니다. 저장 복구에서 이전 파일과 편집 결과를 확인하세요.") else accepted
    }
    private fun build(id: String,name: String,format: Format,uri: String?,hash: String?,base: File,encoding: String="UTF-8"): Session {
        val text = if(format in listOf(Format.TXT,Format.MD)) TextContent.decode(base.readBytes(),encoding) else null
        val pack = if(format in listOf(Format.DOCX,Format.XLSX,Format.PPTX,Format.HWPX)) PackageDocument.open(base) else null
        val pdf = if(format==Format.PDF) PdfEngine.inspect(base) else null
        val readOnlyReason=if(format==Format.HWP) LegacyDocument.hwpReadOnlyReason(base) else null
        val legacy = if(format in LegacyDocument.formats && readOnlyReason==null) LegacyDocument.open(base) else null
        val edits = Edits(text=text?.text ?: "")
        return Session(id,name,format,uri,hash,base,text,pack,pdf,edits,edits,legacy,readOnlyReason=readOnlyReason)
    }
    suspend fun checkpoint(s: Session) = io { checkpointInternal(s) }
    private fun checkpointInternal(s: Session) {
        val stateFile=File(s.base.parentFile,"state.json")
        val cached=checkpointSnapshots[stateFile.path]
        // Sessions are immutable snapshots, as required by Undo and the ViewModel.
        // Check the actual inode, nanosecond modification time and length so a
        // different repository instance or process cannot make this cache stale.
        if(index.isFile && cached!=null && cached.session.get()==s && cached.stamp==checkpointStamp(stateFile)) return
        val json = JSONObject().apply {
            put("version",1); put("id",s.id); put("name",s.name); put("format",s.format.name); put("uri",s.uri ?: "")
            put("sourceHash",s.sourceHash ?: ""); put("encoding",s.textContent?.charset ?: "UTF-8")
            put("baseFile",s.base.name);put("revision",s.revision);put("savedRevision",s.savedRevision)
            put("edits",StateCodec.encode(s.edits)); put("saved",StateCodec.encode(s.saved))
        }
        val bytes=json.toString().toByteArray()
        // All repository instances share this transaction lock. Compare the actual
        // durable bytes rather than caching a stale Session from another Activity.
        if(index.isFile && runCatching {AtomicFile(stateFile).openRead().use {it.readBytes()}.contentEquals(bytes)}.getOrDefault(false)) {
            rememberCheckpoint(s,stateFile);return
        }
        atomic(stateFile,bytes);remember(s);rememberCheckpoint(s,stateFile)
    }
    suspend fun save(s: Session, destination: Uri): Session = io {
        require(s.readOnlyReason==null || (s.edits.values.isEmpty() && s.edits.bold.isEmpty() && s.edits.styles.isEmpty())) {s.readOnlyReason ?: "읽기 전용 문서입니다."}
        val replacing = DocumentUris.same(s.uri,destination.toString())
        // Reading or pressing Save without changes never rewrites the provider file.
        if(replacing && !s.dirty) return@io s
        require(!replacing || s.pdf?.archival != true) { "PDF/A 검증기를 제공하지 않습니다. 일반 PDF로 다른 이름으로 저장하세요." }
        val dir = s.base.parentFile!!; val stage = File(dir,"validated.bin"); val backup = File(dir,"provider-backup.bin")
        checkpointInternal(s)
        if(s.format in visualFormats && s.edits.values.isEmpty() && s.edits.bold.isEmpty() && s.edits.styles.isEmpty()) atomicCopy(s.base,stage)
        else when(s.format) {
            Format.TXT,Format.MD -> atomic(stage,s.textContent!!.encode(s.edits.text)).also { TextContent.decode(stage.readBytes(),s.textContent.charset) }
            Format.PDF -> PdfEngine.write(s.base,stage,s.edits.strokes,s.pdf!!.archival,s.edits.values,s.edits.layers)
            Format.DOC,Format.XLS,Format.PPT,Format.HWP -> if(s.edits.values.isEmpty() && s.edits.bold.isEmpty() && s.edits.styles.isEmpty()) atomicCopy(s.base,stage) else s.legacy!!.write(stage,s.edits)
            else -> s.pack!!.write(stage,s.edits)
        }
        val expected = digest(stage)
        val receipt=providerWrites.publish(s.id,stage,destination,if(replacing)s.sourceHash else null,s.name,s.format.mime,backup)
        // Keep the imported baseline and edit commands so Undo also works across a save.
        val newBase=if(s.format==Format.PDF) File(dir,"${UUID.randomUUID()}.bin").also {atomicCopy(stage,it);PdfEngine.cleanOwned(it)} else s.base
        val result = s.copy(name=displayName(destination),uri=destination.toString(),sourceHash=expected,saved=s.edits,
            pdf=s.pdf?.copy(archival=false),base=newBase,savedRevision=s.revision)
        checkpointInternal(result);java.io.RandomAccessFile(result.base,"rw").use {it.fd.sync()};providerWrites.finish(receipt,listOf(dir,root));stage.delete(); result
    }
    suspend fun exportPdf(s: Session, destination: Uri) = io {
        require(!DocumentUris.same(s.uri,destination.toString())) { "PDF 내보내기는 원본과 다른 파일을 선택하세요." }
        checkpointInternal(s)
        val stage = File(s.base.parentFile,"export.pdf")
        try {
            if(s.format==Format.PDF) PdfEngine.write(s.base,stage,s.edits.strokes,s.pdf!!.archival,s.edits.values,s.edits.layers) else ExportEngine.document(s,stage)
            PdfEngine.inspect(stage)
            val receipt=providerWrites.publish("pdf-export:"+s.id,stage,destination,name=s.name.substringBeforeLast('.')+".pdf",mime="application/pdf")
            providerWrites.finish(receipt)
        } finally {stage.delete()}
    }
    suspend fun exportBackup(s: Session, destination: Uri,password: CharArray?=null) = io {
        require(!DocumentUris.same(s.uri,destination.toString())) {"백업은 원본과 다른 파일을 선택하세요."}
        checkpointInternal(s)
        val stage=File(s.base.parentFile,"backup-export-${UUID.randomUUID()}.zip")
        val encrypted=File(s.base.parentFile,"backup-encrypted-${UUID.randomUUID()}.dsbackup")
        try {
        val files=linkedMapOf("base.bin" to s.base,"state.json" to File(s.base.parentFile,"state.json"))
        val preserved=File(s.base.parentFile,"preserved-backup")
        if(preserved.isDirectory) preserved.walkTopDown().filter {it.isFile}.forEach {files[it.relativeTo(preserved).invariantSeparatorsPath]=it}
        fun include(name: String,file: File) {
            if(!file.isFile) return
            files[name]?.let {old -> if(digest(old)!=digest(file)) files["office-recovery/retained-${digest(old)}-${old.name}"]=old}
            files[name]=file
        }
        include("provider-backup.bin",File(s.base.parentFile,"provider-backup.bin"))
        s.base.parentFile!!.listFiles().orEmpty().filter {it.isFile && it.name.startsWith("office-")}.forEach {include("office-recovery/${it.name}",it)}
        require(files.size<=256 && files.values.all {it.length()<=128L*1024*1024} && files.values.sumOf {it.length()}<=256L*1024*1024) {"백업 용량 또는 파트 수 제한을 초과했습니다. 복구 자료를 별도로 보존해 주세요."}
        java.util.zip.ZipOutputStream(stage.outputStream()).use { zip ->
            files.forEach { (name,file) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
        require(stage.length()<=128L*1024*1024) {"다시 가져올 수 있는 백업은 최대 128 MiB입니다."}
        java.util.zip.ZipFile(stage).use {zip->require(zip.getEntry("base.bin")!=null && zip.getEntry("state.json")!=null)}
        val payload=if(password!=null) encrypted.also {EncryptedBackup.encrypt(stage,it,password)} else stage
        val receipt=providerWrites.publish("backup-export:"+s.id,payload,destination,name=s.name.substringBeforeLast('.')+if(password!=null) ".dsbackup" else ".zip",mime="application/octet-stream")
        providerWrites.finish(receipt)
        } finally {stage.delete();encrypted.delete()}
    }
    suspend fun printable(s: Session): File = io {
        File(context.cacheDir,"print-${UUID.randomUUID()}.pdf").also {file->if(s.format==Format.PDF) PdfEngine.write(s.base,file,s.edits.strokes,s.pdf!!.archival,s.edits.values,s.edits.layers) else ExportEngine.document(s,file)}
    }
    suspend fun render(s: Session,page: Int) = withContext(Dispatchers.IO) { PdfEngine.render(s.base,page,annotationPages=s.pdf?.annotatedPages) }
    suspend fun prepareOffice(s: Session): File = io {
        require(s.format in visualFormats)
        require(s.readOnlyReason==null || (s.edits.values.isEmpty() && s.edits.bold.isEmpty() && s.edits.styles.isEmpty())) {s.readOnlyReason ?: "읽기 전용 문서입니다."}
        val dir=s.base.parentFile!!
        require(!File(dir,"office-pending.json").exists()) {"이 문서의 정밀 편집 복구본을 먼저 다시 열어 주세요."}
        checkpointInternal(s)
        val working=File(dir,"office-${UUID.randomUUID()}.${s.format.extension}")
        if(s.edits.values.isEmpty() && s.edits.bold.isEmpty() && s.edits.styles.isEmpty()) atomicCopy(s.base,working)
        else if(s.pack!=null) s.pack.write(working,s.edits) else s.legacy!!.write(working,s.edits)
        require(FormatDetector.detect(working,s.name)==s.format)
        atomic(File(dir,"office-pending.json"),JSONObject().apply {
            put("approvalVersion",1);put("working",working.name);put("hash",digest(working));put("base",s.base.name);put("revision",s.revision)
            put("edits",StateCodec.encode(s.edits.copy(strokes=emptyList())))
        }.toString().toByteArray())
        remember(s)
        working
    }
    /** Confirms a native process exit without trusting an Activity result lost during BYE. */
    suspend fun completeOfficeClose(s: Session): Boolean = io { completeOfficeCloseInternal(s) }
    private fun completeOfficeCloseInternal(s: Session): Boolean {
        val dir=s.base.parentFile!!
        val marker=File(dir,"office-pending.json")
        if(!marker.isFile) return false
        val pending=JSONObject(AtomicFile(marker).openRead().use {it.bufferedReader().readText()})
        val name=pending.getString("working")
        require(name.matches(Regex("office-[a-zA-Z0-9-]+\\.(docx|doc|xlsx|xls|pptx|ppt|hwp|hwpx)")))
        val working=File(dir,name)
        val request=File(working.path+".close-request")
        if(!request.isFile) return false
        require(request.length() in 1..4096) {"정밀 편집 종료 요청이 올바르지 않습니다."}
        val close=JSONObject(AtomicFile(request).openRead().use {it.bufferedReader().readText()})
        require(pending.optInt("approvalVersion")==1 && close.getInt("version")==1 && close.getString("working")==name)
        require(s.base.name==pending.getString("base") && s.revision==pending.getLong("revision")) {"다른 작업의 종료 요청입니다."}
        require(working.isFile && working.length() in 1..128L*1024*1024)
        java.io.RandomAccessFile(File(dir,"office.lock"),"rw").use {file ->
            var lock: java.nio.channels.FileLock?=null
            val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while(lock==null) {
                lock=try {file.channel.tryLock()} catch(e: java.nio.channels.OverlappingFileLockException) {null}
                if(lock!=null || System.nanoTime()>=deadline) break
                Thread.sleep(25)
            }
            require(lock!=null) {"정밀 편집 엔진의 종료를 기다려 주세요."}
            requireNotNull(lock).use {
                require(close.getString("hash").matches(Regex("[0-9a-f]{64}")) && digest(working)==close.getString("hash")) {
                    "종료 요청 뒤 작업 파일이 변경되었습니다. 복구본을 보존했습니다."
                }
                atomic(File(working.path+".closed"),byteArrayOf(1))
            }
        }
        return close.optBoolean("saveOriginal",false) && File(working.path+".edited").isFile
    }
    suspend fun acceptOffice(s: Session): Session = io { acceptOfficeInternal(s) }
    suspend fun archiveOfficeRecovery(s: Session): Session = io {
        val dir=s.base.parentFile!!
        java.io.RandomAccessFile(File(dir,"office.lock"),"rw").use {file ->
            val lock=try {file.channel.tryLock()} catch(e: java.nio.channels.OverlappingFileLockException) {null}
            require(lock!=null) {"정밀 편집 화면을 먼저 닫아 주세요."}
            lock.use {
                val pending=File(dir,"office-pending.json")
                if(pending.exists()) require(pending.renameTo(File(dir,"office-pending-${UUID.randomUUID()}.json")))
            }
        }
        s.copy(recoveryWarning=null).also {remember(it)}
    }
    private fun acceptOfficeInternal(s: Session): Session {
        val dir=s.base.parentFile!!;val marker=File(dir,"office-pending.json")
        if(!marker.exists()) return s
        val pending=JSONObject(AtomicFile(marker).openRead().use {it.bufferedReader().readText()})
        val name=pending.getString("working")
        require(name.matches(Regex("office-[a-zA-Z0-9-]+\\.(docx|doc|xlsx|xls|pptx|ppt|hwp|hwpx)")))
        val working=File(dir,name)
        val closed=File(working.path+".closed")
        val edited=File(working.path+".edited")
        // The pinned Office BYE ends its process. Confirm its durable close request
        // only after the process-held lock is released and the output hash is stable.
        if(!closed.exists() && File(working.path+".close-request").isFile) completeOfficeCloseInternal(s)
        // Hangul can write .closed itself; a process lock also protects crash recovery.
        if(!closed.exists()) java.io.RandomAccessFile(File(dir,"office.lock"),"rw").use {file ->
            val lock=try {file.channel.tryLock()} catch(e: java.nio.channels.OverlappingFileLockException) {null}
            require(lock!=null) {"정밀 편집 화면을 먼저 닫아 주세요."}; lock.release()
        }
        require(s.base.name==pending.getString("base") && s.revision==pending.getLong("revision")) {"동시 편집이 감지되었습니다. 정밀 편집 파일을 복구 폴더에 보존했습니다."}
        pending.optJSONObject("edits")?.let {expected -> require(s.edits.copy(strokes=emptyList())==StateCodec.decode(expected)) {"다른 화면에서 본문을 편집했습니다. 정밀 편집 파일을 보관한 뒤 병합해 주세요."}}
        require(working.isFile && working.length() in 1..128L*1024*1024) {"정밀 편집 복구본이 없거나 크기 제한을 초과했습니다."}
        if(digest(working)==pending.getString("hash")) {marker.delete();closed.delete();edited.delete();File(working.path+".close-request").delete();remember(s);return s}
        require(s.readOnlyReason==null) {s.readOnlyReason ?: "읽기 전용 문서의 변경을 반영할 수 없습니다."}
        if(pending.optInt("approvalVersion")!=1 || !edited.isFile) {
            // Preserve unapproved or legacy engine output for recovery; never adopt it.
            require(marker.renameTo(File(dir,"office-pending-unapproved-${UUID.randomUUID()}.json"))) {"승인되지 않은 작업 파일 보관에 실패했습니다."}
            return s.copy(recoveryWarning="승인된 편집 표시가 없는 작업 파일을 보관했습니다. 원본과 이전 작업은 변경하지 않았습니다.").also {remember(it)}
        }
        require(FormatDetector.detect(working,s.name)==s.format) {"정밀 편집 결과 형식이 일치하지 않습니다. 이전 복구본과 작업 파일을 보존했습니다."}
        val next=File(dir,"${UUID.randomUUID()}.bin"); atomicCopy(working,next)
        val parsed=build(s.id,s.name,s.format,s.uri,s.sourceHash,next)
        // Stable sheet names survive engine part renumbering. Other anchors stay
        // available as separate ink pages even if their original section was removed.
        val mapping=if(s.format in listOf(Format.XLSX,Format.XLS)) s.sections.associate {old ->
            old.id to (parsed.sections.firstOrNull {it.sourceName==old.sourceName}?.id ?: old.id)
        } else emptyMap()
        val result=parsed.copy(edits=Edits(strokes=s.edits.strokes.map {it.copy(anchor=mapping[it.anchor] ?: it.anchor)},layers=s.edits.layers,paper=s.edits.paper),
            saved=s.saved,revision=s.revision+1,savedRevision=s.savedRevision)
        checkpointInternal(result)
        marker.delete();closed.delete();edited.delete();File(working.path+".close-request").delete()
        return result
    }
    suspend fun reencode(s: Session,charset: String,bom: Boolean): Session = io {
        require(s.textContent!=null && charset in listOf("UTF-8","UTF-16LE","UTF-16BE","MS949"))
        val file=File(s.base.parentFile,"${UUID.randomUUID()}.bin")
        atomic(file,TextContent(s.edits.text,charset,bom).encode())
        val result=build(s.id,s.name,s.format,s.uri,s.sourceHash,file,charset).copy(edits=s.edits,saved=s.saved,revision=s.revision+1,savedRevision=s.savedRevision)
        checkpointInternal(result);result
    }
    suspend fun change(s: Session,change: DocumentChange): Session = io {
        if(change.type=="pdf.ocrPages") require(change.arguments["base"]==s.base.name) {"인식 중 PDF가 변경되었습니다. 현재 문서를 다시 인식하세요."}
        require(s.pack!=null || s.pdf!=null) {"이 형식의 구조 편집은 지원하지 않습니다."}
        val materialized=File(s.base.parentFile,"materialized-${UUID.randomUUID()}.bin")
        val next=File(s.base.parentFile,"${UUID.randomUUID()}.bin")
        try {
            var strokes=s.edits.strokes
            if(s.pdf!=null) {
                val widthRatio=if(change.type=="pdf.rotate" && (change.arguments["angle"] ?: "90") in listOf("90","270")) PdfEngine.displaySize(s.base,change.target.toInt()).let {it.first/it.second} else 1f
                PdfEngine.write(s.base,materialized,emptyList(),false,s.edits.values)
                val attachment=if(change.type=="pdf.ocrPages") File(s.base.parentFile,"ocr-font-${UUID.randomUUID()}.ttf").also {font->context.assets.open("fonts/NotoSansKR.ttf").use {input->font.outputStream().use {input.copyTo(it)}}} else if(change.type in listOf("pdf.merge","pdf.image")) File(s.base.parentFile,"attachment-${UUID.randomUUID()}.bin").also {copyUri(Uri.parse(change.arguments.getValue("uri")),it)} else null
                val mapping=try {PdfEngine.change(materialized,next,change,attachment)} finally {attachment?.delete()}
                strokes=strokes.mapNotNull {stroke->
                    val old=stroke.anchor.removePrefix("pdf:").toIntOrNull() ?: return@mapNotNull stroke
                    val new=mapping.indexOf(old);if(new<0) null else {
                        val points=if(change.type=="pdf.rotate" && old==change.target.toInt()) stroke.points.map {p->when(change.arguments["angle"] ?: "90") {"90"->p.copy(x=1-p.y,y=p.x);"180"->p.copy(x=1-p.x,y=1-p.y);else->p.copy(x=p.y,y=1-p.x)}} else stroke.points
                        stroke.copy(anchor="pdf:$new",points=points,width=if(change.type=="pdf.rotate" && old==change.target.toInt()) (stroke.width*widthRatio).coerceIn(0.0001f,0.1f) else stroke.width)
                    }
                }
            } else {
                s.pack!!.write(materialized,s.edits)
                val attachment=if(change.type in listOf("word.image","slide.image")) normalizeImage(Uri.parse(change.arguments.getValue("uri")),s.base.parentFile!!) else null
                try {PackageFeatures.apply(materialized,next,change=change,attachment=attachment)} finally {attachment?.delete()}
                val sections=PackageDocument.open(next).sections
                strokes=InkAnchors.change(strokes,change)
                if(change.type.endsWith(".delete")) strokes=strokes.filter {it.anchor!=change.target}
                if(change.type.endsWith(".duplicate")) {val new=sections.first {sec->s.sections.none {it.id==sec.id}}.id;strokes=strokes+strokes.filter {it.anchor==change.target}.map {it.copy(id=UUID.randomUUID().toString(),anchor=new)}}
            }
            val extract=change.type=="pdf.extract"
            val result=build(s.id,if(extract) s.name.substringBeforeLast('.')+"-추출.pdf" else s.name,s.format,if(extract) null else s.uri,if(extract) null else s.sourceHash,next).copy(edits=Edits(strokes=strokes,layers=s.edits.layers,paper=s.edits.paper),saved=s.saved,revision=s.revision+1,savedRevision=s.savedRevision)
            checkpointInternal(result);result
        } finally {materialized.delete()}
    }
    private fun displayName(uri: Uri): String {
        // A readable content URI need not implement metadata query/getType.
        // Determine binary format from bytes; optional provider metadata cannot veto it.
        val queried=runCatching {
            context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { c ->
                val column=c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if(column>=0 && c.moveToFirst() && !c.isNull(column)) c.getString(column) else null
            }
        }.getOrNull()?.takeIf {it.isNotBlank()}
        return queried ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf {it.isNotBlank()} ?: "문서"
    }
    private fun normalizeImage(uri: Uri,directory: File): File {
        val raw=File(directory,"image-source-${UUID.randomUUID()}.bin");val png=File(directory,"image-${UUID.randomUUID()}.png")
        try {
            copyUri(uri,raw);require(raw.length()<=16L*1024*1024) {"그림 파일은 최대 16 MiB까지 지원합니다."}
            val bounds=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true};android.graphics.BitmapFactory.decodeFile(raw.path,bounds)
            require(bounds.outWidth>0 && bounds.outHeight>0 && bounds.outWidth.toLong()*bounds.outHeight<=8_000_000) {"그림은 최대 800만 화소까지 지원합니다."}
            val bitmap=android.graphics.BitmapFactory.decodeFile(raw.path) ?: error("그림을 읽을 수 없습니다.")
            try {png.outputStream().use {require(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))};require(png.length()<=16L*1024*1024) {"변환된 그림의 용량 제한을 초과했습니다."}} finally {bitmap.recycle()}
            return png
        } catch(e: Exception) {png.delete();throw e} finally {raw.delete()}
    }
    private fun copyUri(uri: Uri,target: File) {
        val atomic = AtomicFile(target); val out = atomic.startWrite()
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buf=ByteArray(64*1024); var count=0L
                while(true) { val n=input.read(buf); if(n<0) break; count+=n; require(count<=128L*1024*1024) { "파일 크기 제한(128 MiB) 초과" }; out.write(buf,0,n) }
            } ?: error("파일 읽기 권한이 없습니다.")
            atomic.finishWrite(out)
        } catch(e: Exception) { atomic.failWrite(out); throw e }
    }
    private fun digestUri(uri: Uri): String = context.contentResolver.openInputStream(uri)?.use { digestStream(it) } ?: error("저장 검증을 위한 읽기 권한이 없습니다.")
    companion object {
        private val repositoryMutex=Mutex()
        val visualFormats=EditorPolicy.visualFormats
        val officeFormats=setOf(Format.DOCX,Format.DOC,Format.XLSX,Format.XLS,Format.PPTX,Format.PPT)
        fun digest(file: File): String = file.inputStream().use { digestStream(it) }
        internal fun digestStreamForRecovery(input: java.io.InputStream): String = digestStream(input)
        private fun digestStream(input: java.io.InputStream): String {
            val md=MessageDigest.getInstance("SHA-256"); val bytes=ByteArray(64*1024)
            while(true) { val n=input.read(bytes); if(n<0) break; md.update(bytes,0,n) }; return md.digest().joinToString("") { "%02x".format(it) }
        }
        fun atomicCopy(source: File,target: File) {
            target.parentFile?.mkdirs();val af=AtomicFile(target);val out=af.startWrite()
            try {source.inputStream().use {it.copyTo(out,64*1024)};out.fd.sync();af.finishWrite(out)}
            catch(e: Exception) {af.failWrite(out);throw e}
        }
        fun atomic(file: File,bytes: ByteArray) {
            file.parentFile?.mkdirs(); val af=AtomicFile(file); val out=af.startWrite()
            try { out.write(bytes);out.fd.sync(); af.finishWrite(out) } catch(e: Exception) { af.failWrite(out); throw e }
        }
    }
}
