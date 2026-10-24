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
    val office=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {result -> vm.finishOffice(result.data?.getStringExtra("sessionId"))}
    val openOffice: (String)->Unit = {id -> vm.openOffice(id) {s,file ->
        office.launch(Intent(context,org.libreoffice.androidlib.StudioOfficeActivity::class.java)
            .putExtra("sessionId",s.id).putExtra("workingPath",file.absolutePath).putExtra("displayName",s.name))
    }}
    LaunchedEffect(state.officeLaunch,state.busy) {
        val request=state.officeLaunch
        if(request!=null && !state.busy) {vm.consumeOfficeLaunch(request);openOffice(request.sessionId)}
    }
    val saveAs: ()->Unit = { state.session?.let { s -> pendingSaveId=s.id;saveLaunchers.getValue(s.format).launch(s.name.substringBeforeLast('.',s.name)+".${s.format.extension}") } }
    val save: ()->Unit = { state.session?.let { s -> if(s.uri==null || s.pdf?.archival==true) saveAs() else vm.save(Uri.parse(s.uri),s.id) } }
    val selectTab: (String)->Unit = {id ->
        if(splitLeft!=null && id!=splitLeft && id!=splitRight) {
            if(state.active==splitLeft) splitLeft=id else splitRight=id
        }
        vm.select(id)
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
    BackHandler(state.session!=null && !state.busy) { vm.home() }
    val accent=state.session?.let {WorkspaceFamily.of(it.format).accent} ?: Green
    MaterialTheme(colorScheme=lightColorScheme(primary=accent,secondary=Color(0xFF596A82),background=Cream,surface=Color.White,surfaceContainer=Color(0xFFEEF1F7))) {
        Scaffold(snackbarHost={ SnackbarHost(snackbar) },topBar={
            if(state.session==null) TopAppBar(title={ Text("문서 스튜디오",fontWeight=FontWeight.Bold) },actions={
                IconButton(onClick={settingsDialog=true}) { Icon(Icons.Default.Settings,"설정") }
            }) else {
                val s=state.session!!
                TopAppBar(title={ Column { Text(s.name,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleMedium)
                    Text(if(s.dirty) "수정됨 · 복구본 자동 저장" else "저장됨",style=MaterialTheme.typography.labelSmall,color=accent) } },
                    navigationIcon={ IconButton(onClick=vm::home,enabled=!state.busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack,"홈") } },actions={
                        IconButton(onClick={vm.undo()},enabled=!state.busy && vm.canUndo(s.id)) { Icon(Icons.AutoMirrored.Filled.Undo,"실행 취소") }
                        IconButton(onClick={vm.redo()},enabled=!state.busy && vm.canRedo(s.id)) { Icon(Icons.AutoMirrored.Filled.Redo,"다시 실행") }
                        IconButton(onClick=save,enabled=!state.busy) { Icon(Icons.Default.Save,"저장") }
                        var menu by remember { mutableStateOf(false) }
                        IconButton(onClick={menu=true},enabled=!state.busy) { Icon(Icons.Default.MoreVert,"문서 메뉴") }
                        DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                            DropdownMenuItem(text={Text("새 문서")},onClick={menu=false;newDialog=true})
                            DropdownMenuItem(text={Text("파일 열기")},onClick={menu=false;open.launch(arrayOf("*/*"))})
                            if(s.format in DocumentRepository.officeFormats) DropdownMenuItem(text={Text("정밀 오피스 편집")},onClick={menu=false;openOffice(s.id)})
                            DropdownMenuItem(text={Text(if(splitLeft==null) "두 문서 나란히 편집" else "분할 편집 끝내기")},enabled=splitLeft!=null || state.tabs.size>=2,onClick={
                                menu=false
                                if(splitLeft!=null) {splitLeft=null;splitRight=null}
                                else {splitLeft=s.id;splitRight=state.tabs.last {it.id!=s.id}.id}
                            })
                            if(s.recoveryWarning!=null) DropdownMenuItem(text={Text("정밀 편집 복구본 보관 후 다시 시작")},onClick={menu=false;vm.archiveOfficeRecovery()})
                            DropdownMenuItem(text={Text("다른 이름으로 저장")},onClick={menu=false;saveAs()})
                            DropdownMenuItem(text={Text("필기 포함 PDF 내보내기")},onClick={menu=false;pendingPdfId=s.id;pdf.launch(s.name.substringBeforeLast('.')+"-필기.pdf")})
                            DropdownMenuItem(text={Text("문서·필기 암호화 백업")},onClick={menu=false;encryptedDialogId=s.id})
                            DropdownMenuItem(text={Text("문서·필기 복구 ZIP 백업")},onClick={menu=false;pendingBackupId=s.id;backup.launch(s.name.substringBeforeLast('.')+"-백업.zip")})
                            DropdownMenuItem(text={Text("탭 닫기 (복구본 보존)")},onClick={menu=false;vm.close(s.id)})
                            DropdownMenuItem(text={Text("본문·필기 인쇄")},onClick={menu=false;vm.print(context)})
                            DropdownMenuItem(text={Text("설정")},onClick={menu=false;settingsDialog=true})
                        }
                    })
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if(state.tabs.size>1 || (state.session==null && state.tabs.isNotEmpty())) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
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
                    } else EditorPane(state.session!!,state,vm,openOffice,false,Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
        if(newDialog) NewDocumentDialog(state.busy,{newDialog=false;vm.create(it,visual=true)}) {newDialog=false}
        if(state.lockedBackupUri!=null) key(state.lockedBackupUri) {BackupPasswordDialog(false,state.busy,state.message,vm::unlockBackup,vm::cancelBackupUnlock)}
        encryptedDialogId?.let {id->BackupPasswordDialog(true,state.busy,onConfirm={password->
            val name=state.tabs.firstOrNull {it.id==id}?.name
            if(vm.prepareEncryptedBackup(id,password)) encryptedBackup.launch((name ?: "문서").substringBeforeLast('.')+"-백업.dsbackup")
            encryptedDialogId=null
        },onDismiss={encryptedDialogId=null})}
        if(settingsDialog) SettingsDialog(state.settings,vm::settings,vm::cleanupTemporary) {settingsDialog=false}
        if(showBrowser && browserUri!=null) FolderBrowser(Uri.parse(browserUri),state.busy,vm::openExternal,{treePicker.launch(Uri.parse(browserUri))}) {showBrowser=false}
    }
}

@Composable
private fun EditorPane(s: Session,state: UiState,model: EditorViewModel,openOffice: (String)->Unit,split: Boolean,modifier: Modifier,label: String="") {
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
        key(s.id) {FormatWorkspace(s,state.settings,actions,state.busy) {openOffice(s.id)}}
    }
}

@Composable
private fun SettingsDialog(settings: Settings,change: (Settings)->Unit,cleanup: ()->Unit,close: ()->Unit) {
    var licenses by remember {mutableStateOf(false)}
    var officeLicenses by remember {mutableStateOf(false)}
    AlertDialog(onDismissRequest=close,title={Text("편집 설정")},text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment=Alignment.CenterVertically) {Text("오피스 파일을 실제 편집기로 바로 열기",modifier=Modifier.weight(1f));Switch(settings.visualOffice,{change(settings.copy(visualOffice=it))})}
        Text("Word·Excel·PowerPoint · Android 11 이상. 기본 편집과 필기는 뒤로 돌아와 사용할 수 있습니다.",style=MaterialTheme.typography.bodySmall)
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
internal fun NativeTextEditor(text: String,fontSize: Float,busy: Boolean,wrap: Boolean,focused: ()->Unit,change: (String)->Unit,markdown: Boolean=false) {
    val onChange by rememberUpdatedState(change)
    val onFocused by rememberUpdatedState(focused)
    var editor by remember {mutableStateOf<LineNumberEditText?>(null)}
    Column(Modifier.fillMaxSize()) {
    if(markdown) MarkdownToolbar({editor},!busy)
    AndroidView(factory={context -> LineNumberEditText(context).apply {
        gravity=android.view.Gravity.TOP or android.view.Gravity.START
        setBackgroundColor(AndroidColor.WHITE);setPadding(92,24,28,24)
        inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        typeface=android.graphics.Typeface.MONOSPACE; setHorizontallyScrolling(false)
        addTextChangedListener(object: TextWatcher {
            override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int) {}
            override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) { if(tag!=true) onChange(s.toString()) }
            override fun afterTextChanged(s: Editable?) {}
        })
        contentDescription="문서 본문"
        setOnFocusChangeListener {_,hasFocus -> if(hasFocus) onFocused()}
    }.also {editor=it} },update={view ->
        view.textSize=fontSize;view.isEnabled=!busy;view.setHorizontallyScrolling(!wrap)
        if(view.text.toString()!=text) {view.tag=true;val position=view.selectionStart.coerceAtLeast(0).coerceAtMost(text.length);view.setText(text);view.setSelection(position);view.tag=false}
    },modifier=Modifier.fillMaxWidth().weight(1f))
}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SheetEditor(s: Session,sec: Section,vm: DocumentActions,busy: Boolean,tab: String="홈") {
    var style by remember {mutableStateOf(false)}
    var editingCell by remember {mutableStateOf(false)}
    var address by rememberSaveable(sec.id) {mutableStateOf("A1")}
    var rowStart by rememberSaveable(sec.id) {mutableIntStateOf(1)}
    var colStart by rememberSaveable(sec.id) {mutableIntStateOf(1)}
    var windowRequest by remember {mutableIntStateOf(0)}
    val maxRow=if(s.format==Format.XLS) 65536 else 1048576
    val maxColumn=if(s.format==Format.XLS) 256 else 16384
    val lastRow=minOf(rowStart+24,maxRow)
    val lastColumn=minOf(colStart+19,maxColumn)
    val addressValid=validAddress(address) && column(address)<=maxColumn && address.dropWhile {it.isLetter()}.toInt()<=maxRow
    val input=s.edits.values[sec.id+"#"+address] ?: sec.cells[address] ?: ""
    val focus=LocalFocusManager.current
    val goToCell: ()->Unit = {
        if(addressValid) {
            rowStart=address.dropWhile {it.isLetter()}.toInt()
            colStart=column(address)
            windowRequest++
            focus.clearFocus()
        } else vm.message(if(s.format==Format.XLS) "XLS 셀 범위는 A1–IV65536입니다." else "올바른 셀 주소를 입력하세요.")
    }
    val cells=remember(sec,s.edits.values) {sec.cells+s.edits.values.filterKeys {it.startsWith(sec.id+"#")}.mapKeys {it.key.substringAfter('#')}}
    val calculation by produceState(SheetCalculation(),sec,s.sections,s.edits.values,s.edits.styles,rowStart,colStart,address) {
        value=SheetCalculation()
        value=withContext(Dispatchers.Default) {
            val formula=s.calculator(sec)
            val addresses=(rowStart..lastRow).flatMap {r -> (colStart..lastColumn).map {c -> columnName(c)+r}}+listOfNotNull(address.takeIf {addressValid})
            val raw=addresses.distinct().associateWith {formula.display(it)}
            val formatted=raw.mapValues {(a,v) -> val styles=(sec.cellStyles[a] ?: emptyMap())+(s.edits.styles[sec.id+"#"+a] ?: emptyMap());CellFormatting.format(v,styles["numberFormat"],sec.date1904)}
            SheetCalculation(raw,formatted)
        }
    }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(Color.White)) {
        Row(Modifier.padding(horizontal=8.dp,vertical=4.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
            OutlinedTextField(address,{address=it.uppercase()},label={Text("셀 주소")},singleLine=true,modifier=Modifier.width(80.dp),enabled=!busy,isError=!addressValid,
                keyboardOptions=KeyboardOptions(imeAction=ImeAction.Go),keyboardActions=KeyboardActions(onGo={goToCell()}))
            OutlinedTextField(input,{vm.field(sec.id+"#"+address,it)},label={Text("값 또는 =수식")},singleLine=true,modifier=Modifier.weight(1f),enabled=!busy && addressValid,
                keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={focus.clearFocus()}))
            FilledIconButton(onClick=goToCell,enabled=!busy){Icon(Icons.Default.Check,"셀로 이동")}
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text("계산: ${if(addressValid) calculation.raw[address] ?: "계산 중…" else "주소 오류"}",style=MaterialTheme.typography.labelMedium,modifier=Modifier.weight(1f).padding(vertical=6.dp),maxLines=1,overflow=TextOverflow.Ellipsis)
              if(tab=="수식" || tab=="홈") FormulaTools(input,!busy && addressValid) {vm.field(sec.id+"#"+address,it)}
            if(s.format==Format.XLSX) TextButton(onClick={style=true},enabled=!busy && addressValid) {Text("선택 셀 서식")}
        }
        if(s.format==Format.XLSX && tab=="데이터") SheetRangeTools(s,sec,address,cells,vm,busy)
        key(sec.id,rowStart,colStart,windowRequest) {
        Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            LazyColumn {
                stickyHeader { Row {CellBox("",false,{},mapOf("background" to "F4F6FA"),Modifier.width(44.dp));for(c in colStart..lastColumn) CellBox(columnName(c),false,{},mapOf("background" to "F4F6FA","bold" to "true"))} }
                items((rowStart..lastRow).toList()) {r -> Row {
                    CellBox(r.toString(),false,{},mapOf("background" to "F4F6FA"),Modifier.width(44.dp))
                    for(c in colStart..lastColumn) {
                        val a=columnName(c)+r;val styles=(sec.cellStyles[a] ?: emptyMap())+(s.edits.styles[sec.id+"#"+a] ?: emptyMap())
                        if(a==address && editingCell) {
                            val requester=remember {androidx.compose.ui.focus.FocusRequester()}
                            androidx.compose.foundation.text.BasicTextField(cells[a] ?: "",{vm.field(sec.id+"#"+a,it)},enabled=!busy,singleLine=true,
                                modifier=Modifier.width(112.dp).height(44.dp).border(2.dp,MaterialTheme.colorScheme.primary).padding(8.dp).focusRequester(requester).semantics {contentDescription="선택 셀 입력"},
                                keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={editingCell=false;focus.clearFocus()}))
                            LaunchedEffect(a) {requester.requestFocus()}
                        } else CellBox(calculation.formatted[a] ?: "…",a==address,{if(!busy) {editingCell=a==address;address=a}},styles,Modifier.testTag("cell-"+a))
                    }
                } }
            }
        }
        }
    }
    }
    if(style) StyleDialog(sec.id+"#"+address,s.format,(sec.cellStyles[address] ?: emptyMap())+(s.edits.styles[sec.id+"#"+address] ?: emptyMap()),vm) {style=false}
}
private data class SheetCalculation(val raw: Map<String,String> = emptyMap(),val formatted: Map<String,String> = emptyMap())
@Composable private fun CellBox(text: String,selected: Boolean,click: ()->Unit,styles: Map<String,String> = emptyMap(),modifier: Modifier=Modifier) {
    val background=styles["background"]?.let {runCatching {Color(AndroidColor.parseColor("#$it"))}.getOrNull()} ?: Color.White
    val color=styles["color"]?.let {runCatching {Color(AndroidColor.parseColor("#$it"))}.getOrNull()} ?: Color.Unspecified
    val formatted=text
    Box(modifier.width(112.dp).height(44.dp).border(if(selected) 2.dp else 0.5.dp,if(selected) Color(0xFF217346) else Color(0xFFE0E4EA)).background(if(selected) Color(0xFFEAF4EF) else background).clickable(onClick=click).padding(8.dp),contentAlignment=when(styles["align"]) {"center"->Alignment.Center;"right"->Alignment.CenterEnd;else->Alignment.CenterStart}) {
        Text(formatted,maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=(styles["size"]?.toFloatOrNull() ?: 13f).coerceIn(8f,28f).sp,color=color,fontWeight=if(styles["bold"]=="true") FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
internal fun SearchDialog(s: Session,vm: DocumentActions,close: ()->Unit) {
    var find by remember {mutableStateOf("")};var replacement by remember {mutableStateOf("")}
    val count=remember(s.edits.text,find) {var count=0;var from=0;if(find.isNotEmpty()) while(true) {val next=s.edits.text.indexOf(find,from);if(next<0) break;count++;from=next+find.length};count}
    AlertDialog(onDismissRequest=close,title={Text("검색·바꾸기")},text={Column {
        OutlinedTextField(find,{find=it},label={Text("찾을 문자열")},singleLine=true)
        Text("일치 ${count}개",modifier=Modifier.padding(vertical=8.dp))
        OutlinedTextField(replacement,{replacement=it},label={Text("바꿀 문자열")},singleLine=true)
    }},confirmButton={TextButton(onClick={vm.text(s.edits.text.replace(find,replacement));close()},enabled=find.isNotEmpty() && count>0){Text("모두 바꾸기")}},dismissButton={TextButton(onClick=close){Text("닫기")}})
}
