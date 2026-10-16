package kr.documentstudio

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kr.documentstudio.core.*
import kr.documentstudio.data.*

data class Settings(val stylusOnly: Boolean=false,val fontSize: Float=16f,val encoding: String="UTF-8",val penTool: String="PEN",val penColor: Int=0xFF294E46.toInt(),val penWidth: Float=0.004f,val penOpacity: Float=1f,val wrapLines: Boolean=true,val partialEraser: Boolean=false,val eraserRadius: Float=0.025f)
data class UiState(val tabs: List<Session> = emptyList(),val active: String?=null,val recents: List<Recent> = emptyList(),
    val busy: Boolean=false,val message: String?=null,val settings: Settings=Settings()) {
    val session get() = tabs.firstOrNull { it.id==active }
}
class EditorViewModel(application: Application): AndroidViewModel(application) {
    val repository=DocumentRepository(application)
    private val mutable=MutableStateFlow(UiState())
    val state=mutable.asStateFlow()
    private val histories=mutableMapOf<String,History<Session>>()
    private val checkpointed=mutableMapOf<String,Session>()
    private val settingsMutex=Mutex()
    private var settingsChanged=false
    private var checkpointJob: Job?=null
    private var lastTextEdit=0L
    private var lastTextTarget: String?=null
    private var officeActive: String?=null
    private val externalOpens=ArrayDeque<Uri>()
    private var externalOpenJob: Job?=null
    init { viewModelScope.launch {
        try {
            val preferences=withContext(Dispatchers.IO) { application.getSharedPreferences("settings",0).let { p -> Settings(p.getBoolean("stylusOnly",false),p.getFloat("fontSize",16f),p.getString("encoding","UTF-8")!!,p.getString("penTool","PEN")!!,p.getInt("penColor",0xFF294E46.toInt()),p.getFloat("penWidth",0.004f),p.getFloat("penOpacity",1f),p.getBoolean("wrapLines",true),p.getBoolean("partialEraser",false),p.getFloat("eraserRadius",0.025f).coerceIn(0.005f,0.08f)) } }
            val recents=repository.recents()
            mutable.value=mutable.value.copy(recents=recents,settings=if(settingsChanged) mutable.value.settings else preferences)
        } catch(e: CancellationException) {throw e}
        catch(e: Exception) { message("작업 목록을 읽을 수 없습니다: ${e.message}") }
    } }
    fun message(text: String?) { mutable.value=mutable.value.copy(message=text) }
    private fun action(block: suspend () -> Unit) {
        if(mutable.value.busy) return
        checkpointJob?.cancel()
        mutable.value=mutable.value.copy(busy=true)
        viewModelScope.launch {
            try {
                // Preserve pending input even when the requested operation fails.
                flushInternal()
                block()
                refreshRecents()
            }
            catch(e: CancellationException) {throw e}
            catch(e: Exception) { message(e.message ?: "작업에 실패했습니다.") }
            finally { mutable.value=mutable.value.copy(busy=officeActive!=null) }
        }
    }
    private fun attach(s: Session) {
        histories[s.id]=History(30)
        checkpointed[s.id]=s
        lastTextEdit=0
        mutable.value=mutable.value.copy(tabs=mutable.value.tabs.filter { it.id!=s.id }+s,active=s.id)
        s.recoveryWarning?.let {message(it)}
    }
    fun create(format: Format) = action { attach(repository.create(format)) }
    fun open(uri: Uri) = action {
        mutable.value.tabs.firstOrNull {it.uri==uri.toString()}?.let {
            lastTextEdit=0;mutable.value=mutable.value.copy(active=it.id);return@action
        }
        val s=repository.open(uri,mutable.value.settings.encoding); attach(s)
        val ext=s.name.substringAfterLast('.',"").lowercase()
        if(ext.isNotEmpty() && ext != s.format.extension && ext != "markdown") message("파일 내용은 ${s.format.extension.uppercase()} 형식입니다. 저장할 때 올바른 확장자를 사용하세요.")
        else if(s.dirty && s.uri!=null) message("저장되지 않은 작업 복구본을 열었습니다.")
    }
    /** External intents cannot be disabled like UI buttons: retain them while busy. */
    fun openExternal(uri: Uri) {
        if(uri in externalOpens) return
        if(externalOpens.size>=32) {message("열기 요청이 너무 많습니다. 현재 작업이 끝난 뒤 다시 선택해 주세요.");return}
        externalOpens.addLast(uri)
        if(externalOpenJob?.isActive==true) return
        externalOpenJob=viewModelScope.launch {
            while(externalOpens.isNotEmpty()) {
                state.first {!it.busy}
                open(externalOpens.removeFirst())
                state.first {!it.busy}
            }
        }
    }
    fun restore(id: String) = action {
        if(mutable.value.tabs.any {it.id==id}) {lastTextEdit=0;mutable.value=mutable.value.copy(active=id)}
        else attach(repository.restore(id))
    }
    fun home() { if(mutable.value.busy) return; flush(); lastTextEdit=0; mutable.value=mutable.value.copy(active=null) }
    fun select(id: String) { if(mutable.value.busy || mutable.value.tabs.none {it.id==id}) return; flush(); lastTextEdit=0; mutable.value=mutable.value.copy(active=id) }
    fun close(id: String) = action {
        mutable.value.tabs.firstOrNull { it.id==id }?.let { repository.checkpoint(it) }
        histories.remove(id)
        checkpointed.remove(id)
        val tabs=mutable.value.tabs.filter { it.id!=id }
        mutable.value=mutable.value.copy(tabs=tabs,active=if(mutable.value.active==id) tabs.lastOrNull()?.id else mutable.value.active)
        message("작업 복구본을 보존하고 탭을 닫았습니다.")
    }
    private fun replace(s: Session) { mutable.value=mutable.value.copy(tabs=mutable.value.tabs.map { if(it.id==s.id) s else it }) }
    fun edit(edits: Edits,typing: Boolean=false,target: String?=null) {
        if(mutable.value.busy) return
        val s=mutable.value.session ?: return
        if(s.edits==edits) return
        val now=android.os.SystemClock.uptimeMillis()
        val textTarget="${s.id}:$target"
        if(!typing || lastTextEdit==0L || textTarget!=lastTextTarget || now-lastTextEdit>750) histories.getValue(s.id).record(s)
        lastTextEdit=if(typing) now else 0
        lastTextTarget=if(typing) textTarget else null
        replace(s.copy(edits=edits)); scheduleCheckpoint()
    }
    fun text(value: String) { mutable.value.session?.let { edit(it.edits.copy(text=value),true,"text") } }
    fun field(id: String,value: String) { mutable.value.session?.let { edit(it.edits.copy(values=it.edits.values+(id to value)),true,id) } }
    fun bold(id: String,value: Boolean) { mutable.value.session?.let { edit(it.edits.copy(bold=it.edits.bold+(id to value))) } }
    fun style(id: String,value: Map<String,String>) {mutable.value.session?.let {edit(it.edits.copy(styles=it.edits.styles+(id to ((it.edits.styles[id] ?: emptyMap())+value))))}}
    fun change(change: DocumentChange) = action {
        val s=mutable.value.session ?: return@action
        val changed=repository.change(s,change);histories.getValue(s.id).record(s);replace(changed);lastTextEdit=0
    }
    fun openOffice(launch: (Session,java.io.File)->Unit) = action {
        require(android.os.Build.VERSION.SDK_INT>=30) {"정밀 오피스 편집은 Android 11 이상에서 사용할 수 있습니다. 이 기기에서는 기본 편집 도구를 사용하세요."}
        val s=mutable.value.session ?: return@action
        val file=repository.prepareOffice(s)
        officeActive=s.id
        try { launch(s,file) } catch(e: Exception) {
            officeActive=null
            replace(s.copy(recoveryWarning="정밀 편집을 시작하지 못했습니다. 복구 파일을 보관한 뒤 다시 시작할 수 있습니다."))
            throw e
        }
    }
    fun finishOffice(id: String?) {
        val target=id ?: officeActive
        officeActive=null;mutable.value=mutable.value.copy(busy=false)
        if(target==null) return
        action {
            val s=mutable.value.tabs.firstOrNull {it.id==target}
            if(s==null) attach(repository.restore(target)) else {
                val result=try {repository.acceptOffice(s)}
                catch(e: CancellationException) {throw e}
                catch(e: Exception) {
                    replace(s.copy(recoveryWarning="정밀 편집 결과를 가져오지 못했습니다. 이전 작업과 복구 파일을 보존했습니다: ${e.message}"))
                    throw e
                }
                if(result.base!=s.base) {histories.getValue(s.id).record(s);replace(result)}
            }
            message("정밀 편집 결과를 작업 복구본에 반영했습니다. 저장 버튼으로 파일에 저장하세요.")
        }
    }
    fun archiveOfficeRecovery() = action {
        mutable.value.session?.let {replace(repository.archiveOfficeRecovery(it))}
        message("정밀 편집 복구 파일을 보관했습니다. 문서·필기 ZIP 백업에도 포함됩니다. 다시 정밀 편집을 시작할 수 있습니다.")
    }
    fun addStroke(stroke: Stroke) { mutable.value.session?.let {
        if(it.edits.strokes.size>=4000 || it.edits.strokes.sumOf {s->s.points.size}+stroke.points.size>200000) message("문서 필기 용량 제한에 도달했습니다. PDF·ZIP으로 보존하고 새 문서에서 계속해 주세요.")
        else edit(it.edits.copy(strokes=it.edits.strokes+stroke))
    } }
    fun reencode(charset: String,bom: Boolean) = action {val s=mutable.value.session ?: return@action;val changed=repository.reencode(s,charset,bom);histories.getValue(s.id).record(s);replace(changed)}
    fun erase(ids: Set<String>) { mutable.value.session?.let { edit(it.edits.copy(strokes=it.edits.strokes.filterNot { s -> s.id in ids })) } }
    fun eraseInk(anchor: String,gesture: List<InkPoint>,radius: Float,aspect: Float,partial: Boolean) = action {
        val s=mutable.value.session ?: return@action
        val strokes=withContext(Dispatchers.Default) {InkErasure.erase(s.edits.strokes,anchor,gesture,radius,aspect,partial)}
        if(strokes!=s.edits.strokes) {
            histories.getValue(s.id).record(s)
            val changed=s.copy(edits=s.edits.copy(strokes=strokes))
            replace(changed);lastTextEdit=0;repository.checkpoint(changed)
        }
    }
    fun transformInk(ids: Set<String>,dx: Float=0f,dy: Float=0f,scale: Float=1f,degrees: Float=0f,duplicate: Boolean=false) {
        mutable.value.session?.let {s->try {edit(s.edits.copy(strokes=InkGeometry.transform(s.edits.strokes,ids,dx,dy,scale,degrees,duplicate)))} catch(e: IllegalArgumentException) {message(e.message)}}
    }
    fun canUndo(id: String)=histories[id]?.canUndo==true
    fun canRedo(id: String)=histories[id]?.canRedo==true
    private fun restoreSnapshot(snapshot: Session,current: Session)=snapshot.copy(uri=current.uri,name=current.name,sourceHash=current.sourceHash,saved=current.saved,savedRevision=current.savedRevision,recoveryWarning=current.recoveryWarning)
    fun undo() { if(mutable.value.busy) return; val s=mutable.value.session ?: return; replace(restoreSnapshot(histories.getValue(s.id).undo(s),s)); lastTextEdit=0; scheduleCheckpoint() }
    fun redo() { if(mutable.value.busy) return; val s=mutable.value.session ?: return; replace(restoreSnapshot(histories.getValue(s.id).redo(s),s)); lastTextEdit=0; scheduleCheckpoint() }
    fun save(uri: Uri) = action {
        val s=mutable.value.session ?: return@action
        replace(repository.save(s,uri))
        message(if(s.format==Format.PDF) "PDF 표준 Ink 주석을 포함하여 저장했습니다." else "원본 형식으로 저장했습니다. 필기는 앱 내부 별도 데이터에 보존됩니다.")
    }
    fun exportPdf(uri: Uri) = action { mutable.value.session?.let { repository.exportPdf(it,uri); message(if(it.format==Format.PPTX) "기본 슬라이드 개체와 필기 PDF를 내보냈습니다. 마스터·테마·SmartArt·그룹은 재현하지 않습니다." else if(it.format==Format.PDF) "PDF와 필기를 내보냈습니다." else "본문·필기 PDF를 내보냈습니다. 오피스 원본 배치와 다를 수 있습니다.") } }
    fun backup(uri: Uri) = action { mutable.value.session?.let { repository.exportBackup(it,uri); message("문서 원본과 편집·필기 복구 데이터를 ZIP으로 백업했습니다.") } }
    fun favorite(id: String) = action { repository.favorite(id) }
    fun print(context: android.content.Context) = action {mutable.value.session?.let {s->val file=repository.printable(s);val manager=context.getSystemService(android.content.Context.PRINT_SERVICE) as android.print.PrintManager;manager.print(s.name,kr.documentstudio.ui.DocumentPrintAdapter(file,s.name),null)}}
    fun settings(value: Settings) {
        settingsChanged=true
        mutable.value=mutable.value.copy(settings=value)
        viewModelScope.launch(Dispatchers.IO) {settingsMutex.withLock {
            val latest=mutable.value.settings
            getApplication<Application>().getSharedPreferences("settings",0).edit()
                .putBoolean("stylusOnly",latest.stylusOnly).putFloat("fontSize",latest.fontSize).putString("encoding",latest.encoding)
                .putString("penTool",latest.penTool).putInt("penColor",latest.penColor).putFloat("penWidth",latest.penWidth).putFloat("penOpacity",latest.penOpacity).putBoolean("wrapLines",latest.wrapLines)
                .putBoolean("partialEraser",latest.partialEraser).putFloat("eraserRadius",latest.eraserRadius).apply()
        }}
    }
    private fun scheduleCheckpoint() {
        checkpointJob?.cancel()
        checkpointJob=viewModelScope.launch { delay(700); recoverySafely() }
    }
    private suspend fun flushInternal() {
        mutable.value.tabs.forEach { session ->
            if(checkpointed[session.id]!=session) {
                repository.checkpoint(session)
                checkpointed[session.id]=session
            }
        }
        refreshRecents()
    }
    private suspend fun refreshRecents() {
        // Read the flow after IO: copy(recents=repository.recents()) captures its
        // receiver before suspension and can restore an obsolete tab or edit.
        val recents=repository.recents()
        mutable.value=mutable.value.copy(recents=recents)
    }
    private suspend fun recoverySafely() {
        try {flushInternal()}
        catch(e: CancellationException) {throw e}
        catch(e: Exception) {message("자동 복구 저장 실패: ${e.message}")}
    }
    fun flush() { checkpointJob?.cancel(); checkpointJob=viewModelScope.launch {recoverySafely()} }
}
