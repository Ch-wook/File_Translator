package kr.documentstudio.ui

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.EditText
import android.text.TextWatcher
import android.text.Editable
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.documentstudio.*
import kr.documentstudio.core.*
import kr.documentstudio.data.*

private val Green=Color(0xFF2858A5)
private val Cream=Color(0xFFF3F5FA)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioApp(vm: EditorViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context=LocalContext.current
    val focus=LocalFocusManager.current
    val view=LocalView.current
    val reader=remember(state.active) {ReaderActions()}
    var fullScreen by rememberSaveable(state.active) {mutableStateOf(false)}
    var info by remember {mutableStateOf(false)}
    val editing=state.active?.let {state.presentation(it).allowsMutation}==true
    LaunchedEffect(editing) {if(editing)fullScreen=false}
    var newDialog by remember { mutableStateOf(false) }
    var settingsDialog by remember { mutableStateOf(false) }
    var splitLeft by rememberSaveable {mutableStateOf<String?>(null)}
    var splitRight by rememberSaveable {mutableStateOf<String?>(null)}
    var pendingSaveId by rememberSaveable {mutableStateOf<String?>(null)}
    var pendingPdfId by rememberSaveable {mutableStateOf<String?>(null)}
    var encryptedDialogId by rememberSaveable {mutableStateOf<String?>(null)}
    var pendingBackupId by rememberSaveable {mutableStateOf<String?>(null)}
    var browserUri by rememberSaveable {mutableStateOf(context.getSharedPreferences("file-browser",0).getString("tree",null))}
    var showBrowser by rememberSaveable {mutableStateOf(false)}
    val snackbar=remember { SnackbarHostState() }
    val open=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            .recoverCatching { context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        vm.open(uri,visual=true)
    } }
    val treePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {uri -> if(uri!=null) {
        runCatching {context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)}
            .recoverCatching {context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}
        browserUri=uri.toString();showBrowser=true
        context.getSharedPreferences("file-browser",0).edit().putString("tree",uri.toString()).apply()
    }}
    // Separate contracts retain the requested file format through Activity recreation.
    val saveLaunchers=Format.entries.associateWith { format -> rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(format.mime)) { uri ->
        val target=pendingSaveId;pendingSaveId=null
        if(uri!=null && target!=null) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        vm.save(uri,target)
    } } }
    val pdf=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) {uri -> if(uri!=null) pendingPdfId?.let {vm.exportPdf(uri,it)};pendingPdfId=null}
    val backup=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {uri -> if(uri!=null) pendingBackupId?.let {vm.backup(uri,it)};pendingBackupId=null}
    val encryptedBackup=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {uri->vm.finishEncryptedBackup(uri)}
    val office=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {result -> vm.finishOffice(result.data?.getStringExtra("sessionId"),result.data?.getBooleanExtra("saveOriginal",false)==true,result.data?.getStringExtra("error"))}
    val launchOffice: (String,OpenIntent)->Unit = {id,intentMode -> vm.openOffice(id,intentMode) {s,file ->
        office.launch(Intent(context,if(s.format in setOf(Format.HWP,Format.HWPX)) HangulEditorActivity::class.java else org.libreoffice.androidlib.StudioOfficeActivity::class.java)
            .putExtra("sessionId",s.id).putExtra("workingPath",file.absolutePath).putExtra("displayName",s.name).putExtra("originalUri",s.uri).putExtra("openIntent",intentMode.name))
    }}
    val openOffice: (String)->Unit = {id -> launchOffice(id,OpenIntent.VIEW)}
    val documentLaunchLifecycle=LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state.officeLaunch,state.busy,documentLaunchLifecycle) {
        val request=state.officeLaunch
        if(request!=null && !state.busy) {
            // External cold-start imports can finish before MainActivity is resumed.
            // Keep the request until the host is foregrounded instead of launching
            // a native window underneath the Activity that is still starting.
            documentLaunchLifecycle.currentStateFlow.first {it.isAtLeast(Lifecycle.State.RESUMED)}
            if(vm.state.value.officeLaunch==request && !vm.state.value.busy) {
                vm.consumeOfficeLaunch(request);launchOffice(request.sessionId,request.openIntent)
            }
        }
    }
    val saveAs: ()->Unit = { state.session?.let { s -> pendingSaveId=s.id;saveLaunchers.getValue(s.format).launch(s.name.substringBeforeLast('.',s.name)+".${s.format.extension}") } }
    val save: ()->Unit = { state.session?.let { s -> if(s.uri==null || s.pdf?.archival==true) saveAs() else vm.save(Uri.parse(s.uri),s.id) } }
    LaunchedEffect(state.saveRequest,state.busy) {
        val id=state.saveRequest
        if(id!=null && !state.busy) {
            val target=state.tabs.firstOrNull {it.id==id}
            vm.consumeSaveRequest(id)
            if(target!=null) {pendingSaveId=id;saveLaunchers.getValue(target.format).launch(target.name)}
        }
    }
    val selectTab: (String)->Unit = {id ->
        if(splitLeft!=null && id!=splitLeft && id!=splitRight) {
            if(state.active==splitLeft) splitLeft=id else splitRight=id
        }
        vm.restore(id,visual=true)
    }
    LaunchedEffect(state.tabs.map {it.id}) {
        if(state.tabs.none {it.id==splitLeft} || state.tabs.none {it.id==splitRight}) {splitLeft=null;splitRight=null}
    }
    LaunchedEffect(state.active) {
        if(state.active==null) {
            // The TXT/MD editor is a native EditText with its own IME connection.
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(view.windowToken,0)
            view.rootView.findFocus()?.clearFocus()
            focus.clearFocus(force=true)
        }
    }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.message(null) } }
    BackHandler(state.session!=null && !state.busy) { if(state.active?.let {state.presentation(it).allowsMutation}==true) vm.readDocument() else vm.home() }
    val accent=state.session?.let {WorkspaceFamily.of(it.format).accent} ?: Green
    MaterialTheme(colorScheme=lightColorScheme(primary=accent,secondary=Color(0xFF596A82),background=Cream,surface=Color.White,surfaceContainer=Color(0xFFEEF1F7))) {
        Scaffold(topBar={
            if(state.session==null) TopAppBar(title={ Text("문서 스튜디오",fontWeight=FontWeight.Bold) },actions={
                IconButton(onClick={settingsDialog=true}) { Icon(Icons.Default.Settings,"설정") }
            }) else if(!fullScreen || editing) {
                val s=state.session!!
                TopAppBar(modifier=Modifier.statusBarsPadding().height(48.dp).testTag("mobile-editor-header"),windowInsets=WindowInsets(0,0,0,0),expandedHeight=48.dp,
                    title={Text(s.name,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall)},
                    navigationIcon={ IconButton(onClick={if(state.presentation(s.id).allowsMutation) vm.readDocument() else vm.home()},enabled=!state.busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack,if(state.presentation(s.id).allowsMutation) "보기로" else "홈") } },actions={
                        if(editing) {
                        IconButton(onClick={vm.undo()},enabled=!state.busy && vm.canUndo(s.id)) { Icon(Icons.AutoMirrored.Filled.Undo,"실행 취소") }
                        IconButton(onClick={vm.redo()},enabled=!state.busy && vm.canRedo(s.id)) { Icon(Icons.AutoMirrored.Filled.Redo,"다시 실행") }
                        IconButton(onClick=save,enabled=!state.busy && s.dirty) { Icon(Icons.Default.Save,"저장") }
                        } else {
                            IconButton(onClick={reader.search()},enabled=!state.busy) {Icon(Icons.Default.Search,"문서 검색")}
                            IconButton(onClick={vm.beginEditing()},enabled=!state.busy && state.presentation(s.id)!=PresentationState.READ_ONLY,modifier=Modifier.testTag("edit-document")) {Icon(Icons.Default.Edit,"편집하기")}
                        }
                        var menu by remember { mutableStateOf(false) }
                        IconButton(onClick={menu=true},enabled=!state.busy) { Icon(Icons.Default.MoreVert,"문서 메뉴") }
                        DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                            if(!editing) {
                                DropdownMenuItem(text={Text("전체 화면 보기")},onClick={menu=false;fullScreen=true})
                                DropdownMenuItem(text={Text("보기 설정")},onClick={menu=false;reader.settings()})
                            }
                            DropdownMenuItem(text={Text("파일 정보")},onClick={menu=false;info=true})
                            DropdownMenuItem(text={Text(if(state.presentation(s.id).allowsMutation) "읽기 모드로 전환" else "편집하기")},enabled=state.presentation(s.id)!=PresentationState.READ_ONLY,onClick={menu=false;if(state.presentation(s.id).allowsMutation) vm.readDocument() else vm.beginEditing()})
                            DropdownMenuItem(text={Text(if(s.dirty) "원본에 미저장 · 기기에 복구본 자동 보관" else "원본 파일에 저장됨")},enabled=false,onClick={})
                            state.tabs.filter {it.id!=s.id}.forEach {other->DropdownMenuItem(text={Text(other.name,maxLines=1,overflow=TextOverflow.Ellipsis)},onClick={menu=false;selectTab(other.id)})}
                            DropdownMenuItem(text={Text("새 문서")},onClick={menu=false;newDialog=true})
                            DropdownMenuItem(text={Text("파일 열기")},onClick={menu=false;open.launch(arrayOf("*/*"))})
                            if(s.format in DocumentRepository.visualFormats) DropdownMenuItem(text={Text("원본 문서 보기")},onClick={menu=false;openOffice(s.id)})
                            DropdownMenuItem(text={Text(if(splitLeft==null) "두 문서 나란히 편집" else "분할 편집 끝내기")},enabled=splitLeft!=null || (state.tabs.count {it.format !in DocumentRepository.visualFormats}>=2 && s.format !in DocumentRepository.visualFormats),onClick={
                                menu=false
                                if(splitLeft!=null) {splitLeft=null;splitRight=null}
                                else {splitLeft=s.id;splitRight=state.tabs.last {it.id!=s.id && it.format !in DocumentRepository.visualFormats}.id}
                            })
                            if(s.recoveryWarning!=null) DropdownMenuItem(text={Text("정밀 편집 복구본 보관 후 다시 시작")},onClick={menu=false;vm.archiveOfficeRecovery()})
                            DropdownMenuItem(text={Text("파일 변환")},onClick={menu=false;vm.prepareConversion {id->context.startActivity(Intent(context,ConversionActivity::class.java).putExtra("sessionId",id))}})
                            DropdownMenuItem(text={Text("다른 이름으로 저장")},onClick={menu=false;saveAs()})
                            if(s.format !in EditorPolicy.visualFormats) DropdownMenuItem(text={Text("필기 포함 PDF 내보내기")},onClick={menu=false;pendingPdfId=s.id;pdf.launch(s.name.substringBeforeLast('.')+"-필기.pdf")})
                            DropdownMenuItem(text={Text("문서·필기 암호화 백업")},onClick={menu=false;encryptedDialogId=s.id})
                            DropdownMenuItem(text={Text("문서·필기 복구 ZIP 백업")},onClick={menu=false;pendingBackupId=s.id;backup.launch(s.name.substringBeforeLast('.')+"-백업.zip")})
                            DropdownMenuItem(text={Text("탭 닫기 (복구본 보존)")},onClick={menu=false;vm.close(s.id)})
                            if(s.format !in EditorPolicy.visualFormats) DropdownMenuItem(text={Text("본문·필기 인쇄")},onClick={menu=false;vm.print(context)})
                            DropdownMenuItem(text={Text("설정")},onClick={menu=false;settingsDialog=true})
                        }
                    })
            }
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize().imePadding()) {
                if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if(state.session==null && state.tabs.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    state.tabs.forEach { s -> FilterChip(selected=state.active==s.id,onClick={if(!state.busy) selectTab(s.id)},label={Text((if(s.dirty) "• " else "")+s.name,maxLines=1)},enabled=!state.busy) }
                    AssistChip(onClick={newDialog=true},label={Text("새 문서")},enabled=!state.busy)
                }
                if(state.session==null) WorkspaceHome(state,vm,{newDialog=true},{open.launch(arrayOf("*/*"))},{
                    if(browserUri==null) treePicker.launch(null) else showBrowser=true
                },{vm.create(it,visual=true)})
                else {
                    val left=state.tabs.firstOrNull {it.id==splitLeft};val right=state.tabs.firstOrNull {it.id==splitRight}
                    if(left!=null && right!=null) BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        if(maxWidth>=840.dp) Row(Modifier.fillMaxSize()) {
                            EditorPane(left,state,vm,openOffice,true,Modifier.weight(1f).fillMaxHeight(),"왼쪽")
                            EditorPane(right,state,vm,openOffice,true,Modifier.weight(1f).fillMaxHeight(),"오른쪽")
                        } else if(maxHeight>=680.dp) Column(Modifier.fillMaxSize()) {
                            EditorPane(left,state,vm,openOffice,true,Modifier.weight(1f).fillMaxWidth(),"왼쪽")
                            EditorPane(right,state,vm,openOffice,true,Modifier.weight(1f).fillMaxWidth(),"오른쪽")
                        } else Column(Modifier.fillMaxSize()) {
                            Row {TextButton({vm.select(left.id)},enabled=!state.busy) {Text("왼쪽 문서")};TextButton({vm.select(right.id)},enabled=!state.busy) {Text("오른쪽 문서")};Text("좁은 창 · 선택한 문서 표시",style=MaterialTheme.typography.labelSmall)}
                            EditorPane(if(state.active==left.id) left else right,state,vm,openOffice,true,Modifier.weight(1f).fillMaxWidth(),if(state.active==left.id) "왼쪽" else "오른쪽")
                        }
                    } else EditorPane(state.session!!,state,vm,openOffice,false,Modifier.weight(1f).fillMaxWidth(),reader=reader)
                }
            }
            if(fullScreen && !editing && state.session!=null) IconButton({fullScreen=false},Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color.White.copy(alpha=.9f),RoundedCornerShape(8.dp))) {Icon(Icons.Default.MoreVert,"메뉴 표시")}
            SnackbarHost(snackbar,Modifier.align(Alignment.TopCenter).padding(horizontal=8.dp,vertical=6.dp))
            }
        }
        if(info && state.session!=null) AlertDialog(onDismissRequest={info=false},title={Text("파일 정보")},text={val s=state.session!!;Text(s.name+"\n"+s.format.extension.uppercase()+" · "+s.base.length()/1024+" KiB\n"+if(s.dirty)"원본에 저장되지 않은 변경이 있습니다." else "원본 파일에 저장됨")},confirmButton={TextButton({info=false}){Text("닫기")}})
        if(newDialog) NewDocumentDialog(state.busy,{newDialog=false;vm.create(it,visual=true)}) {newDialog=false}
        if(state.lockedBackupUri!=null) key(state.lockedBackupUri) {BackupPasswordDialog(false,state.busy,state.message,vm::unlockBackup,vm::cancelBackupUnlock)}
        encryptedDialogId?.let {id->BackupPasswordDialog(true,state.busy,onConfirm={password->
            val name=state.tabs.firstOrNull {it.id==id}?.name
            if(vm.prepareEncryptedBackup(id,password)) encryptedBackup.launch((name ?: "문서").substringBeforeLast('.')+"-백업.dsbackup")
            encryptedDialogId=null
        },onDismiss={encryptedDialogId=null})}
        if(settingsDialog) SettingsDialog(state.settings,vm::settings,vm::cleanupTemporary) {settingsDialog=false}
        if(showBrowser && browserUri!=null) FolderBrowser(Uri.parse(browserUri),state.busy,{vm.openExternal(it)},{treePicker.launch(Uri.parse(browserUri))}) {showBrowser=false}
    }
}

@Composable
private fun EditorPane(s: Session,state: UiState,model: EditorViewModel,openOffice: (String)->Unit,split: Boolean,modifier: Modifier,label: String="",reader: ReaderActions?=null) {
    val actions=remember(model,s.id) {model.documentActions(s.id)}
    Column(modifier.then(if(split) Modifier.border(if(state.active==s.id) 2.dp else 1.dp,if(state.active==s.id) Green else Color.LightGray) else Modifier)
        .onFocusChanged {if(it.hasFocus && state.active!=s.id) actions.focus()}.focusGroup()
        .pointerInput(s.id) {awaitEachGesture {awaitFirstDown(requireUnconsumed=false,pass=PointerEventPass.Initial);actions.focus()}}
        .testTag("document-pane-${s.id}").semantics {contentDescription="$label ${s.name} 편집창"}) {
        if(split) Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
            TextButton(actions::focus,enabled=!state.busy,modifier=Modifier.weight(1f)) {Text("$label · ${s.name}",maxLines=1,overflow=TextOverflow.Ellipsis)}
            IconButton({model.undo(s.id)},enabled=!state.busy && model.canUndo(s.id)) {Icon(Icons.AutoMirrored.Filled.Undo,"$label 실행 취소")}
            IconButton({model.redo(s.id)},enabled=!state.busy && model.canRedo(s.id)) {Icon(Icons.AutoMirrored.Filled.Redo,"$label 다시 실행")}
        }
        key(s.id) {FormatWorkspace(s,state.settings,actions,state.busy,presentation=state.presentation(s.id),reader=reader) {openOffice(s.id)}}
    }
}

@Composable
private fun SettingsDialog(settings: Settings,change: (Settings)->Unit,cleanup: ()->Unit,close: ()->Unit) {
    var licenses by remember {mutableStateOf(false)}
    var officeLicenses by remember {mutableStateOf(false)}
    AlertDialog(onDismissRequest=close,title={Text("편집 설정")},text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState())) {
        Text("한글은 전용 한글 엔진, Word·Excel·PowerPoint는 각 형식의 오피스 엔진에서 원본을 직접 편집합니다. 오피스 엔진은 Android 11 이상이 필요합니다.",style=MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment=Alignment.CenterVertically) {Text("펜 전용 필기",modifier=Modifier.weight(1f));Switch(settings.stylusOnly,{change(settings.copy(stylusOnly=it))})}
        Text("펜 전용 모드는 손가락 입력을 무시합니다. 정전식 펜은 손가락으로 인식될 수 있습니다.",style=MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp));Text("편집 글자 크기 · ${settings.fontSize.toInt()} sp")
        Slider(settings.fontSize,{change(settings.copy(fontSize=it))},valueRange=12f..28f,steps=15)
        Row(verticalAlignment=Alignment.CenterVertically) {Text("텍스트 자동 줄바꿈",modifier=Modifier.weight(1f));Switch(settings.wrapLines,{change(settings.copy(wrapLines=it))})}
        Text("BOM 없는 텍스트 열기 인코딩")
        Row {listOf("UTF-8","MS949").forEach {e -> FilterChip(settings.encoding==e,{change(settings.copy(encoding=e))},label={Text(e)},modifier=Modifier.padding(end=8.dp))}}
        Text("BOM 인코딩을 우선하고 저장 시 인코딩과 원래 줄바꿈을 유지합니다. 자동 저장은 원본을 덮어쓰지 않습니다.",style=MaterialTheme.typography.bodySmall)
        TextButton(onClick={licenses=true}) {Text("오픈소스·SDK 라이선스")}
        TextButton(cleanup) {Text("임시 변환 파일 정리")}
        TextButton(onClick={officeLicenses=true}) {Text("정밀 오피스 엔진 고지")}
    }},confirmButton={TextButton(onClick=close){Text("완료")}})
    if(licenses) {
        val context=LocalContext.current
        val notice by produceState("읽는 중…") {
            value=withContext(Dispatchers.IO) {context.assets.list("licenses")!!.sorted().joinToString("\n\n") {name ->
                name+"\n\n"+context.assets.open("licenses/$name").bufferedReader().use {it.readText()}
            }}
        }
        AlertDialog(onDismissRequest={licenses=false},title={Text("오픈소스·SDK 라이선스")},text={Text(notice,style=MaterialTheme.typography.bodySmall,modifier=Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState()))},confirmButton={TextButton(onClick={licenses=false}){Text("닫기")}})
    }
    if(officeLicenses) AlertDialog(onDismissRequest={officeLicenses=false},title={Text("Collabora·LibreOffice 고지")},text={Column {
        Text("Collabora Office 26.04.6.0 · MPL 2.0 및 구성요소별 라이선스. 수정 Java 소스와 출처는 전달 소스의 officeruntime에 포함됩니다.",style=MaterialTheme.typography.bodySmall)
        AndroidView(factory={ctx -> android.webkit.WebView(ctx).apply {
            this.settings.javaScriptEnabled=false;this.settings.blockNetworkLoads=true
            webViewClient=object: android.webkit.WebViewClient() {
                override fun shouldOverrideUrlLoading(view: android.webkit.WebView,request: android.webkit.WebResourceRequest)=true
            }
            loadUrl("file:///android_asset/license.html")
        }},modifier=Modifier.fillMaxWidth().height(360.dp),onRelease={it.destroy()})
    }},confirmButton={TextButton(onClick={officeLicenses=false}){Text("닫기")}})
}

@Composable
internal fun NativeTextEditor(text: String,fontSize: Float,busy: Boolean,wrap: Boolean,focused: ()->Unit,change: (String)->Unit,markdown: Boolean=false,readOnly: Boolean=true,controller: NativeEditorController?=null,showToolbar: Boolean=true,fontFamily: String="고정폭",showLineNumbers: Boolean=true,viewportKey: String?=null) {
    val fontContext=LocalContext.current
    val scrollPreferences=remember {fontContext.getSharedPreferences("reading-position",0)}
    val desiredFace=remember(fontFamily,fontContext) {EditorFonts.face(fontContext,fontFamily)}
    val onChange by rememberUpdatedState(change)
    val onFocused by rememberUpdatedState(focused)
    var editor by remember {mutableStateOf<LineNumberEditText?>(null)}
    var inputKeyListener by remember {mutableStateOf<android.text.method.KeyListener?>(null)}
    val acceptsInput by rememberUpdatedState(!busy && !readOnly)
    Column(Modifier.fillMaxSize()) {
    if(markdown && !readOnly && showToolbar) MarkdownToolbar({editor},!busy)
    AndroidView(factory={context -> LineNumberEditText(context).apply {
        gravity=android.view.Gravity.TOP or android.view.Gravity.START
        setBackgroundColor(AndroidColor.WHITE);setPadding(92,24,28,24)
        inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        typeface=android.graphics.Typeface.MONOSPACE; setHorizontallyScrolling(false)
        addTextChangedListener(object: TextWatcher {
            override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int) {}
            override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) { if(tag!=true && acceptsInput) onChange(s.toString()) }
            override fun afterTextChanged(s: Editable?) {}
        })
        contentDescription="문서 본문"
        setOnFocusChangeListener {_,hasFocus -> if(hasFocus) onFocused()}
    }.also {editor=it;it.onSelectionUpdate={a,b->if(controller?.editor===it)controller?.selectionChanged(a,b)};inputKeyListener=it.keyListener} },update={view ->
        if(view.showLineNumbers!=showLineNumbers){val x=view.scrollX;val y=view.scrollY;view.showLineNumbers=showLineNumbers;view.setPadding(if(showLineNumbers)92 else 24,24,28,24);view.scrollTo(x,y);view.invalidate()}
        if(view.typeface!=desiredFace) {val a=view.selectionStart.coerceIn(0,view.length());val b=view.selectionEnd.coerceIn(0,view.length());val x=view.scrollX;val y=view.scrollY;view.typeface=desiredFace;view.setSelection(a,b);view.scrollTo(x,y)}
        if(kotlin.math.abs(view.textSize/fontContext.resources.displayMetrics.scaledDensity-fontSize)>.01f)view.textSize=fontSize
        if(view.isEnabled==busy)view.isEnabled=!busy
        if(view.wrapLines!=wrap){view.wrapLines=wrap;view.setHorizontallyScrolling(!wrap)}
        if(view.isTextSelectable!=readOnly){
            val a=view.selectionStart.coerceIn(0,view.length());val b=view.selectionEnd.coerceIn(0,view.length());val x=view.scrollX;val y=view.scrollY
            view.tag=true;view.setTextIsSelectable(readOnly);view.tag=false;view.setSelection(a,b);view.scrollTo(x,y)
        }
        val desiredKeyListener=if(readOnly) null else inputKeyListener
        if(view.keyListener !== desiredKeyListener) {
            val start=view.selectionStart.coerceIn(0,view.length());val end=view.selectionEnd.coerceIn(0,view.length())
            val x=view.scrollX;val y=view.scrollY
            view.keyListener=desiredKeyListener
            view.setSelection(start,end);view.scrollTo(x,y)
        }
        view.showSoftInputOnFocus=!readOnly;view.isCursorVisible=!readOnly
        
        view.isFocusable=true;view.isFocusableInTouchMode=true
        if(view.text.toString()!=text) {
            val start=view.selectionStart.coerceIn(0,text.length);val end=view.selectionEnd.coerceIn(0,text.length)
            val x=view.scrollX;val y=view.scrollY
            view.tag=true;view.setText(text);view.setSelection(start,end);view.tag=false;view.scrollTo(x,y)
        }
        if(controller?.editor!==view && viewportKey!=null) {
            val y=scrollPreferences.getInt(viewportKey+":textY",0);val x=scrollPreferences.getInt(viewportKey+":textX",0)
            view.post {view.scrollTo(x,y)}
        }
        controller?.attach(view)
    },onRelease={view->if(viewportKey!=null)scrollPreferences.edit().putInt(viewportKey+":textY",view.scrollY).putInt(viewportKey+":textX",view.scrollX).apply();controller?.detach(view)},modifier=Modifier.fillMaxWidth().weight(1f))
}
}

@Composable
internal fun SearchDialog(s: Session,vm: DocumentActions,controller: NativeEditorController?=null,readOnly: Boolean=false,close: ()->Unit) {
    var find by remember {mutableStateOf("")};var replacement by remember {mutableStateOf("")}
    var current by remember {mutableIntStateOf(-1)}
    val matches=remember(s.edits.text,find) {buildList {var from=0;if(find.isNotEmpty())while(true) {val next=s.edits.text.indexOf(find,from);if(next<0)break;add(next);from=next+find.length}}}
    fun move(backwards: Boolean) {if(matches.isNotEmpty()) {current=if(backwards)(if(current<=0)matches.lastIndex else current-1) else (current+1)%matches.size;controller?.select(matches[current],matches[current]+find.length)}}
    AlertDialog(onDismissRequest=close,title={Text("검색·바꾸기")},text={Column {
        OutlinedTextField(find,{find=it;current=-1},label={Text("찾을 문자열")},singleLine=true)
        Text("일치 ${matches.size}개"+if(current in matches.indices)" · ${current+1} / ${matches.size}" else "",modifier=Modifier.padding(vertical=8.dp))
        Row {TextButton({move(true)},enabled=matches.isNotEmpty()){Text("이전 결과")};TextButton({move(false)},enabled=matches.isNotEmpty()){Text("다음 결과")}}
        if(!readOnly) OutlinedTextField(replacement,{replacement=it},label={Text("바꿀 문자열")},singleLine=true)
        if(!readOnly) TextButton({
            if(current !in matches.indices)move(false)
            if(current in matches.indices){val a=matches[current];val value=s.edits.text.replaceRange(a,a+find.length,replacement);if(controller!=null)controller.replace(value,a,a+replacement.length) else vm.text(value);current=-1}
        },enabled=find.isNotEmpty() && matches.isNotEmpty()){Text("바꾸기")}
    }},confirmButton={if(!readOnly)TextButton(onClick={val value=s.edits.text.replace(find,replacement);if(controller!=null)controller.replace(value,0,0) else vm.text(value);current=-1},enabled=find.isNotEmpty() && matches.isNotEmpty()){Text("모두 바꾸기")}},dismissButton={TextButton(onClick=close){Text("닫기")}})
}
