package kr.documentstudio.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.documentstudio.data.*

/** Explicit recovery never writes back to the affected original. */
@Composable
fun SaveRecoveryDialog(repository: DocumentRepository,message: (String)->Unit,close: ()->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var generation by remember {mutableIntStateOf(0)}
    var rows by remember {mutableStateOf<List<SaveRecovery>>(emptyList())}
    var damaged by remember {mutableIntStateOf(0)}
    var busy by remember {mutableStateOf(false)}
    var loading by remember {mutableStateOf(true)}
    var signatureLoading by remember {mutableStateOf(true)}
    var signatureError by remember {mutableStateOf<String?>(null)}
    var status by remember {mutableStateOf("")}
    var signatures by remember {mutableStateOf(false)}
    var signatureBad by remember {mutableStateOf(false)}
    var previous by remember {mutableStateOf(false)}
    var pendingId by rememberSaveable {mutableStateOf<String?>(null)}
    var original by rememberSaveable {mutableStateOf(false)}
    var discard by remember {mutableStateOf<SaveRecovery?>(null)}
    var restore by remember {mutableStateOf(false)}
    fun perform(block: suspend ()->Unit) {
        if(busy)return
        busy=true
        scope.launch {
            try {block();generation++}
            catch(e: kotlinx.coroutines.CancellationException){throw e}
            catch(e: Exception){val p=UserProblems.explain(e);status=p.title+" · "+p.advice}
            finally {busy=false}
        }
    }
    LaunchedEffect(generation) {
        loading=true
        try {rows=repository.saveRecoveries();damaged=repository.damagedSaveRecords()}
        catch(e: kotlinx.coroutines.CancellationException){throw e}
        catch(e: Exception){status=UserProblems.explain(e).advice}
        finally {loading=false}
    }
    // Signature recovery must remain usable while the document transaction/index
    // is busy. These stores have independent locks and no shared write operation.
    LaunchedEffect(generation) {
        signatureLoading=true;signatureError=null
        try {
            val state=withContext(Dispatchers.IO) {
                val store=SignatureStore(context)
                Triple(java.io.File(context.filesDir,"signatures.json").isFile || java.io.File(context.filesDir,"signatures.json.bak").isFile,
                    runCatching {store.read()}.isFailure,store.canRestorePrevious())
            }
            signatures=state.first;signatureBad=state.second;previous=state.third
        } catch(e: kotlinx.coroutines.CancellationException){throw e}
        catch(e: Exception){signatureError=UserProblems.explain(e).advice;status=signatureError.orEmpty()}
        finally {signatureLoading=false}
    }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {uri->
        val id=pendingId;pendingId=null;val before=original
        if(uri!=null && id!=null)perform {repository.exportSaveRecovery(id,before,uri);status="검증한 복구 사본을 새 파일에 저장했습니다. 원본은 자동으로 복원하지 않았습니다."}
    }
    val signatureExport=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {uri->
        if(uri!=null)perform {withContext(Dispatchers.IO){
            val archive=SignatureStore(context).recoveryArchive()
            try {ConversionFiles.publish(context,archive,uri)} finally {archive.delete()}
        };status="자필 서명 복구 자료를 저장했습니다. 선택한 저장 위치의 보안을 확인하세요."}
    }
    AlertDialog(onDismissRequest={if(!busy)close()},title={Text("저장 복구")},text={
        Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("자동 복구본은 원본 파일 저장과 별개입니다. 중단된 저장의 이전 원본과 최신 편집본을 새 파일로 보관한 뒤 비교하세요.",style=MaterialTheme.typography.bodySmall)
            if(loading || signatureLoading || busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(!loading && rows.isEmpty())Text("확인이 필요한 저장 기록이 없습니다.")
            if(damaged>0)Text("해석하지 못한 저장 기록 "+damaged+"개를 기기에 보존했습니다. 자동으로 삭제하지 않습니다.")
            rows.forEach {r->
                HorizontalDivider()
                Text(r.name,style=MaterialTheme.typography.titleSmall)
                Text(when(r.phase){"ROLLED_BACK"->"저장 실패 · 이전 파일로 복원 확인";"VERIFIED"->"파일 기록 확인 · 앱 저장 상태 확인 필요";"PREPARATION_FAILED","PREPARING"->"저장 준비 중 중단";else->"저장 중단 · 원본 상태 확인 필요"},style=MaterialTheme.typography.bodySmall)
                if(r.after.isNotEmpty())TextButton({pendingId=r.id;original=false;export.launch("최신편집-"+r.name.replace('/','_'))},enabled=!busy) {Text("최신 편집본 새 파일로 보관")}
                if(r.before.isNotEmpty())TextButton({pendingId=r.id;original=true;export.launch("이전원본-"+r.name.replace('/','_'))},enabled=!busy) {Text("이전 원본 새 파일로 보관")}
                TextButton({discard=r},enabled=!busy) {Text("이 복구 기록 삭제…")}
            }
            HorizontalDivider();Text("내 서명 보관함",style=MaterialTheme.typography.titleSmall)
            Text(if(signatureLoading)"서명 자료를 확인하고 있습니다." else if(signatureError!=null)signatureError!! else if(signatureBad)"보관함을 읽지 못했습니다. 손상 자료를 보존하고 새 덮어쓰기를 차단했습니다." else if(signatures)"서명 자료는 앱 내부에 보관됩니다." else "보관한 서명이 없습니다.",style=MaterialTheme.typography.bodySmall)
            if(signatures && !signatureLoading)TextButton({signatureExport.launch("자필서명-복구자료.zip")},enabled=!busy) {Text("서명 자료 내보내기")}
            if(previous && !signatureLoading)TextButton({restore=true},enabled=!busy) {Text("이전 정상 서명 보관함 복원…")}
            if(status.isNotEmpty())Text(status,style=MaterialTheme.typography.bodySmall)
        }
    },confirmButton={TextButton({message(status.takeIf {it.isNotBlank()} ?: "복구 사본은 자동으로 원본에 덮어쓰지 않습니다.");close()},enabled=!busy){Text("닫기")}})
    discard?.let {r->AlertDialog(onDismissRequest={discard=null},title={Text("복구 사본을 삭제할까요?")},
        text={Text("이전 원본과 최신 편집 사본이 앱에서 삭제됩니다. 먼저 필요한 사본을 새 파일로 보관하세요. 외부 원본 문서는 삭제하지 않습니다.")},
        confirmButton={TextButton({discard=null;perform {repository.discardSaveRecovery(r.id);status="선택한 복구 기록만 삭제했습니다."}}){Text("복구 사본 삭제")}},
        dismissButton={TextButton({discard=null}){Text("취소")}})}
    if(restore)AlertDialog(onDismissRequest={restore=false},title={Text("이전 서명 보관함으로 바꿀까요?")},
        text={Text("현재 보관함 자료를 별도로 보존한 뒤 이전 정상 보관함으로 되돌립니다. 이후 추가한 서명은 복구 자료 내보내기에서 확인할 수 있습니다.")},
        confirmButton={TextButton({restore=false;perform {withContext(Dispatchers.IO){SignatureStore(context).restorePrevious()};status="이전 서명 보관함을 복원했습니다. 기존 자료도 보존했습니다."}}){Text("복원")}},
        dismissButton={TextButton({restore=false}){Text("취소")}})
}
