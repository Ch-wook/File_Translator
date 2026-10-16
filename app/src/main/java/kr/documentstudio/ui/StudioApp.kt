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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
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

private val Green=Color(0xFF315D54)
private val Cream=Color(0xFFF6F4EF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioApp(vm: EditorViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context=LocalContext.current
    val focus=LocalFocusManager.current
    val view=LocalView.current
    var newDialog by remember { mutableStateOf(false) }
    var settingsDialog by remember { mutableStateOf(false) }
    val snackbar=remember { SnackbarHostState() }
    val open=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            .recoverCatching { context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        vm.open(uri)
    } }
    // Separate contracts retain the requested file format through Activity recreation.
    val saveLaunchers=Format.entries.associateWith { format -> rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(format.mime)) { uri -> if(uri!=null) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        vm.save(uri)
    } } }
    val pdf=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { if(it!=null) vm.exportPdf(it) }
    val backup=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { if(it!=null) vm.backup(it) }
    val office=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {result -> vm.finishOffice(result.data?.getStringExtra("sessionId"))}
    val openOffice: ()->Unit = {vm.openOffice {s,file ->
        office.launch(Intent(context,org.libreoffice.androidlib.StudioOfficeActivity::class.java)
            .putExtra("sessionId",s.id).putExtra("workingPath",file.absolutePath))
    }}
    val saveAs: ()->Unit = { state.session?.let { s -> saveLaunchers.getValue(s.format).launch(s.name.substringBeforeLast('.',s.name)+".${s.format.extension}") } }
    val save: ()->Unit = { state.session?.let { s -> if(s.uri==null || s.pdf?.archival==true) saveAs() else vm.save(Uri.parse(s.uri)) } }
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
    MaterialTheme(colorScheme=lightColorScheme(primary=Green,secondary=Color(0xFF7B6751),background=Cream,surface=Color.White,surfaceContainer=Color(0xFFEEEDE6))) {
        Scaffold(snackbarHost={ SnackbarHost(snackbar) },topBar={
            if(state.session==null) TopAppBar(title={ Text("문서 스튜디오",fontWeight=FontWeight.Bold) },actions={
                IconButton(onClick={settingsDialog=true}) { Icon(Icons.Default.Settings,"설정") }
            }) else {
                val s=state.session!!
                TopAppBar(title={ Column { Text(s.name,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleMedium)
                    Text(if(s.dirty) "수정됨 · 복구본 자동 저장" else "저장됨",style=MaterialTheme.typography.labelSmall,color=Green) } },
                    navigationIcon={ IconButton(onClick=vm::home,enabled=!state.busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack,"홈") } },actions={
                        IconButton(onClick=vm::undo,enabled=!state.busy && vm.canUndo(s.id)) { Icon(Icons.AutoMirrored.Filled.Undo,"실행 취소") }
                        IconButton(onClick=vm::redo,enabled=!state.busy && vm.canRedo(s.id)) { Icon(Icons.AutoMirrored.Filled.Redo,"다시 실행") }
                        IconButton(onClick=save,enabled=!state.busy) { Icon(Icons.Default.Save,"저장") }
                        var menu by remember { mutableStateOf(false) }
                        IconButton(onClick={menu=true},enabled=!state.busy) { Icon(Icons.Default.MoreVert,"문서 메뉴") }
                        DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                            if(s.format in DocumentRepository.officeFormats) DropdownMenuItem(text={Text("정밀 오피스 편집")},onClick={menu=false;openOffice()})
                            if(s.recoveryWarning!=null) DropdownMenuItem(text={Text("정밀 편집 복구본 보관 후 다시 시작")},onClick={menu=false;vm.archiveOfficeRecovery()})
                            DropdownMenuItem(text={Text("다른 이름으로 저장")},onClick={menu=false;saveAs()})
                            DropdownMenuItem(text={Text("필기 포함 PDF 내보내기")},onClick={menu=false;pdf.launch(s.name.substringBeforeLast('.')+"-필기.pdf")})
                            DropdownMenuItem(text={Text("문서·필기 복구 ZIP 백업")},onClick={menu=false;backup.launch(s.name.substringBeforeLast('.')+"-백업.zip")})
                            DropdownMenuItem(text={Text("탭 닫기 (복구본 보존)")},onClick={menu=false;vm.close(s.id)})
                            DropdownMenuItem(text={Text("본문·필기 인쇄")},onClick={menu=false;vm.print(context)})
                            DropdownMenuItem(text={Text("설정")},onClick={menu=false;settingsDialog=true})
                        }
                    })
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if(state.tabs.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    state.tabs.forEach { s -> FilterChip(selected=state.active==s.id,onClick={if(!state.busy) vm.select(s.id)},label={Text((if(s.dirty) "• " else "")+s.name,maxLines=1)},enabled=!state.busy) }
                    AssistChip(onClick={newDialog=true},label={Text("새 문서")},enabled=!state.busy)
                }
                if(state.session==null) Home(state,vm,{newDialog=true},{open.launch(arrayOf("*/*"))})
                else key(state.session!!.id) { Editor(state.session!!,state.settings,vm,state.busy,openOffice) }
            }
        }
        if(newDialog) AlertDialog(onDismissRequest={newDialog=false},title={Text("새 문서")},text={ Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState())) {
            Format.entries.forEach { f -> TextButton(onClick={newDialog=false;vm.create(f)},enabled=f!=Format.DOC && !state.busy,modifier=Modifier.fillMaxWidth()) {
                Text(if(f==Format.DOC) "${f.label} · 기존 파일 편집" else "${f.label}  .${f.extension}")
            } }
        } },confirmButton={TextButton(onClick={newDialog=false}) {Text("닫기")}})
        if(settingsDialog) SettingsDialog(state.settings,vm::settings) {settingsDialog=false}
    }
}

@Composable
private fun Home(state: UiState,vm: EditorViewModel,new: ()->Unit,open: ()->Unit) {
    var query by rememberSaveable {mutableStateOf("")}
    var favorites by rememberSaveable {mutableStateOf(false)}
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=24.dp)) {
        item { Spacer(Modifier.height(18.dp)); Text("문서를 편집하고,\n생각을 기록하세요.",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.SemiBold); Spacer(Modifier.height(10.dp)); Text("기기 안에서 작업하는 문서와 필기 공간",color=Color(0xFF626861)) }
        item { Row(Modifier.fillMaxWidth().padding(vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Button(onClick=new,enabled=!state.busy,modifier=Modifier.weight(1f).height(54.dp)) {Icon(Icons.Default.Add,null); Spacer(Modifier.width(6.dp));Text("새 문서")}
            OutlinedButton(onClick=open,enabled=!state.busy,modifier=Modifier.weight(1f).height(54.dp)) {Icon(Icons.Default.FolderOpen,null);Spacer(Modifier.width(6.dp));Text("파일 열기")}
        } }
        item { Surface(shape=RoundedCornerShape(16.dp),color=Color(0xFFE4ECE5)) { Column(Modifier.padding(16.dp)) {
            Text("현재 지원 범위",fontWeight=FontWeight.Bold,color=Green)
            Text("TXT·MD / PDF / DOCX·DOC / XLSX·XLS / PPTX·PPT / HWPX·HWP",style=MaterialTheme.typography.bodyMedium,modifier=Modifier.padding(top=6.dp))
            Text("Word·Excel·PowerPoint는 정밀 편집으로 페이지·시트·슬라이드를 볼 수 있습니다. 한글은 구조 기반 편집을 제공합니다. 필기는 별도 보존됩니다. 복구 ZIP은 파일 열기로 가져오세요.",style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(top=6.dp))
        } } }
        item { Row(verticalAlignment=Alignment.CenterVertically) {
            Text("최근 작업 · 복구",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
            FilterChip(selected=favorites,onClick={favorites=!favorites},label={Text("즐겨찾기")})
        }; OutlinedTextField(value=query,onValueChange={query=it},singleLine=true,placeholder={Text("최근 파일 이름 검색")},leadingIcon={Icon(Icons.Default.Search,null)},modifier=Modifier.fillMaxWidth()) }
        val rows=state.recents.filter { (!favorites || it.favorite) && it.name.contains(query,ignoreCase=true) }
        if(rows.isEmpty()) item { Text("아직 문서가 없습니다. 새 문서를 만들거나 파일을 열어주세요.",color=Color.Gray,modifier=Modifier.padding(vertical=20.dp)) }
        items(rows,key={it.id}) { r -> Surface(onClick={if(!state.busy) vm.restore(r.id)},shape=RoundedCornerShape(12.dp),color=Color.White) {
            Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically) {
                Icon(Icons.Default.Description,null,tint=Green,modifier=Modifier.padding(end=12.dp))
                Column(Modifier.weight(1f)) {Text(r.name,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis);Text(if(r.dirty) "저장되지 않은 작업 복구" else "최근 문서",style=MaterialTheme.typography.bodySmall,color=Color.Gray)}
                IconButton(onClick={vm.favorite(r.id)},enabled=!state.busy) {Icon(if(r.favorite) Icons.Default.Star else Icons.Default.StarBorder,"즐겨찾기",tint=Green)}
            }
        } }
    }
}

@Composable
private fun SettingsDialog(settings: Settings,change: (Settings)->Unit,close: ()->Unit) {
    var licenses by remember {mutableStateOf(false)}
    var officeLicenses by remember {mutableStateOf(false)}
    AlertDialog(onDismissRequest=close,title={Text("편집 설정")},text={Column {
        Row(verticalAlignment=Alignment.CenterVertically) {Text("펜 전용 필기",modifier=Modifier.weight(1f));Switch(settings.stylusOnly,{change(settings.copy(stylusOnly=it))})}
        Text("펜 전용 모드는 손가락 입력을 무시합니다. 정전식 펜은 손가락으로 인식될 수 있습니다.",style=MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp));Text("편집 글자 크기 · ${settings.fontSize.toInt()} sp")
        Slider(settings.fontSize,{change(settings.copy(fontSize=it))},valueRange=12f..28f,steps=15)
        Row(verticalAlignment=Alignment.CenterVertically) {Text("텍스트 자동 줄바꿈",modifier=Modifier.weight(1f));Switch(settings.wrapLines,{change(settings.copy(wrapLines=it))})}
        Text("BOM 없는 텍스트 열기 인코딩")
        Row {listOf("UTF-8","MS949").forEach {e -> FilterChip(settings.encoding==e,{change(settings.copy(encoding=e))},label={Text(e)},modifier=Modifier.padding(end=8.dp))}}
        Text("BOM 인코딩을 우선하고 저장 시 인코딩과 원래 줄바꿈을 유지합니다. 자동 저장은 원본을 덮어쓰지 않습니다.",style=MaterialTheme.typography.bodySmall)
        TextButton(onClick={licenses=true}) {Text("오픈소스 라이선스")}
        TextButton(onClick={officeLicenses=true}) {Text("정밀 오피스 엔진 고지")}
    }},confirmButton={TextButton(onClick=close){Text("완료")}})
    if(licenses) {
        val context=LocalContext.current
        val notice by produceState("읽는 중…") {
            value=withContext(Dispatchers.IO) {context.assets.list("licenses")!!.sorted().joinToString("\n\n") {name ->
                name+"\n\n"+context.assets.open("licenses/$name").bufferedReader().use {it.readText()}
            }}
        }
        AlertDialog(onDismissRequest={licenses=false},title={Text("오픈소스 라이선스")},text={Text(notice,style=MaterialTheme.typography.bodySmall,modifier=Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState()))},confirmButton={TextButton(onClick={licenses=false}){Text("닫기")}})
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
private fun Editor(s: Session,settings: Settings,vm: EditorViewModel,busy: Boolean,openOffice: ()->Unit) {
    var draw by rememberSaveable {mutableStateOf(s.format==Format.PDF)}
    var preview by rememberSaveable {mutableStateOf(false)}
    var search by rememberSaveable {mutableStateOf(false)}
    var encoding by remember {mutableStateOf(false)}
    var section by rememberSaveable {mutableIntStateOf(0)}
    var page by rememberSaveable {mutableIntStateOf(0)}
    var continuous by rememberSaveable {mutableStateOf(false)}
    var navigationRequest by remember {mutableIntStateOf(0)}
    val navigate: (Int)->Unit = {page=it;navigationRequest++}
    val current=s.sections.getOrNull(section)
    LaunchedEffect(s.base) {section=section.coerceIn(0,(s.sections.size-1).coerceAtLeast(0));page=page.coerceIn(0,((s.pdf?.pages ?: 1)-1).coerceAtLeast(0))}
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            if(s.format in DocumentRepository.officeFormats) AssistChip(onClick=openOffice,label={Text("정밀 편집")},enabled=!busy)
            if(s.format!=Format.PDF) FilterChip(!draw && !preview,{draw=false;preview=false},label={Text("${s.format.label} 편집")},enabled=!busy)
            FilterChip(draw,{draw=true;preview=false;continuous=false},label={Text(if(s.format==Format.PDF) "PDF 필기" else "필기 페이지")},enabled=!busy)
            if(s.format==Format.PDF) FilterChip(continuous,{continuous=true;draw=false;preview=false},label={Text("연속 보기")},enabled=!busy)
            if(s.format==Format.MD) FilterChip(preview,{preview=!preview;draw=false},label={Text("미리보기")},enabled=!busy)
            if(s.format==Format.PPTX) FilterChip(preview,{preview=!preview;draw=false},label={Text("개체 미리보기")},enabled=!busy)
            if(s.format in listOf(Format.TXT,Format.MD)) AssistChip({search=true},label={Text("검색·바꾸기")},enabled=!busy)
            if(s.textContent!=null) AssistChip({encoding=true},label={Text("저장 인코딩")},enabled=!busy)
        }
        Text(when(s.format) {
            Format.PDF -> if(s.pdf!!.archival) "PDF/A 표식 감지 · 일반 PDF로만 별도 저장" else "페이지·양식·본문 문자열 편집 · 원래 폰트가 지원하는 가로 본문 · 표준 Ink 저장"
            Format.TXT,Format.MD -> "필기는 고정 페이지에 별도 보존 · 인코딩 ${s.textContent!!.charset}"
            Format.XLSX,Format.XLS -> "기본 셀 편집 · 차트·전체 시트는 정밀 편집에서 보기 · 필기 별도 보존"
            Format.DOCX,Format.DOC,Format.PPTX,Format.PPT -> "텍스트·개체 도구 · 원본 배치는 정밀 편집에서 보기 · 필기 별도 보존"
            else -> "기존 텍스트 런 편집 · 원본 배치 보기 미지원 · 필기 별도 보존"
        },style=MaterialTheme.typography.labelSmall,color=Color(0xFF686B65),modifier=Modifier.padding(horizontal=16.dp,vertical=4.dp))
        if(s.format==Format.PDF || (!draw && !preview)) DocumentTools(s,current,vm,busy,page,navigate)
        if(s.sections.size>1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=12.dp)) {
            s.sections.forEachIndexed {i,sec -> FilterChip(section==i,{section=i},label={Text(sec.title)},modifier=Modifier.padding(end=6.dp),enabled=!busy)}
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide=maxWidth>=840.dp
            Row(Modifier.fillMaxSize()) {
                if(wide) LazyColumn(Modifier.width(200.dp).fillMaxHeight().background(Color(0xFFEEEDE6)).padding(12.dp)) {
                    item {Text("문서 탐색",style=MaterialTheme.typography.titleSmall,modifier=Modifier.padding(vertical=12.dp))}
                    if(s.format==Format.PDF) {
                        items(s.pdf!!.pages) {i -> TextButton(onClick={navigate(i)},enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text("${i+1} 페이지")}}
                    } else if(s.sections.isNotEmpty()) {
                        items(s.sections.size) {i -> TextButton(onClick={section=i},enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text(s.sections[i].title)}}
                    } else item {Text("텍스트 본문\n고정 필기 페이지",style=MaterialTheme.typography.bodySmall)}
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if(draw) DrawingPage(s,current,page,settings,vm,busy)
                    else if(continuous && s.format==Format.PDF) PdfContinuousReader(s,page,navigationRequest,busy,{page=it}) {page=it;continuous=false;draw=true}
                    else if(preview && s.format==Format.MD) MarkdownPreview(s.edits.text)
                    else if(preview && s.format==Format.PPTX && current!=null) SlidePreview(s,current,vm,busy)
                    else if(s.format in listOf(Format.TXT,Format.MD)) NativeTextEditor(s.edits.text,settings.fontSize,busy,settings.wrapLines,vm::text)
                    else if(s.format in listOf(Format.XLSX,Format.XLS) && current!=null) SheetEditor(s,current,vm,busy)
                    else if(current!=null) RunEditor(s,current,vm,busy)
                }
            }
        }
        if(s.format==Format.PDF) Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center) {
            IconButton(onClick={navigate(page-1)},enabled=page>0 && !busy){Icon(Icons.AutoMirrored.Filled.NavigateBefore,"이전 페이지")}
            Text("${page+1} / ${s.pdf!!.pages}")
            IconButton(onClick={navigate(page+1)},enabled=page<s.pdf.pages-1 && !busy){Icon(Icons.AutoMirrored.Filled.NavigateNext,"다음 페이지")}
        }
    }
    if(search) SearchDialog(s,vm){search=false}
    if(encoding) {
        var charset by remember {mutableStateOf(s.textContent!!.charset)};var bom by remember {mutableStateOf(s.textContent!!.bom)}
        AlertDialog(onDismissRequest={encoding=false},title={Text("저장 인코딩")},text={Column {
            listOf("UTF-8","UTF-16LE","UTF-16BE","MS949").forEach {cs->Row(verticalAlignment=Alignment.CenterVertically) {RadioButton(charset==cs,{charset=cs});Text(cs)}}
            Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(bom,{bom=it},enabled=charset!="MS949");Text("BOM 포함")}
            Text("표현할 수 없는 문자가 있으면 변환을 거부합니다. 원본은 저장 버튼을 누를 때 변경됩니다.")
        }},confirmButton={TextButton({vm.reencode(charset,bom && charset!="MS949");encoding=false}) {Text("적용")}},dismissButton={TextButton({encoding=false}) {Text("취소")}})
    }
}

@Composable
private fun NativeTextEditor(text: String,fontSize: Float,busy: Boolean,wrap: Boolean,change: (String)->Unit) {
    val onChange by rememberUpdatedState(change)
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
    } },update={view ->
        view.textSize=fontSize;view.isEnabled=!busy;view.setHorizontallyScrolling(!wrap)
        if(view.text.toString()!=text) {view.tag=true;val position=view.selectionStart.coerceAtLeast(0).coerceAtMost(text.length);view.setText(text);view.setSelection(position);view.tag=false}
    },modifier=Modifier.fillMaxSize())
}

@Composable
private fun RunEditor(s: Session,sec: Section,vm: EditorViewModel,busy: Boolean) {
    var styleField by remember {mutableStateOf<Field?>(null)}
    LazyColumn(Modifier.fillMaxSize().background(Color.White),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        if(sec.fields.isEmpty()) item {Text("표시할 기존 텍스트 런이 없습니다.")}
        items(sec.fields,key={it.id}) {f -> Column {
            val properties=f.styles+(s.edits.styles[f.id] ?: emptyMap())
            val ink=properties["color"]?.let {runCatching {Color(AndroidColor.parseColor("#"+it.removePrefix("#")))}.getOrNull()} ?: Color.Unspecified
            val decoration=listOfNotNull(if(properties["underline"]=="true") TextDecoration.Underline else null,if(properties["strike"]=="true") TextDecoration.LineThrough else null)
            OutlinedTextField(s.edits.values[f.id] ?: f.text,{vm.field(f.id,it)},label={Text(f.label)},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth(),textStyle=LocalTextStyle.current.copy(fontWeight=if(s.edits.bold[f.id] ?: properties["bold"]?.toBoolean() ?: f.bold) FontWeight.Bold else FontWeight.Normal,fontStyle=if(properties["italic"]=="true") FontStyle.Italic else FontStyle.Normal,color=ink,textDecoration=if(decoration.isEmpty()) TextDecoration.None else TextDecoration.combine(decoration),fontSize=(properties["size"]?.toFloatOrNull() ?: 16f).coerceIn(8f,32f).sp))
            if(s.format in listOf(Format.DOCX,Format.DOC,Format.PPT)) Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(s.edits.bold[f.id] ?: f.bold,{vm.bold(f.id,it)},enabled=!busy);Text("굵게",style=MaterialTheme.typography.labelMedium) }
            if(s.format in listOf(Format.DOCX,Format.PPTX,Format.HWPX)) TextButton(onClick={styleField=f},enabled=!busy) {Text("글자·문단 서식")}
        } }
    }
    styleField?.let {f -> StyleDialog(f.id,s.format,f.styles+(s.edits.styles[f.id] ?: emptyMap()),vm) {styleField=null}}
}

@Composable
private fun SheetEditor(s: Session,sec: Section,vm: EditorViewModel,busy: Boolean) {
    var style by remember {mutableStateOf(false)}
    var address by rememberSaveable(sec.id) {mutableStateOf("A1")}
    var rowStart by rememberSaveable(sec.id) {mutableIntStateOf(1)}
    var colStart by rememberSaveable(sec.id) {mutableIntStateOf(1)}
    var windowRequest by remember {mutableIntStateOf(0)}
    val maxRow=if(s.format==Format.XLS) 65536 else 1048576
    val maxColumn=if(s.format==Format.XLS) 256 else 16384
    val lastRow=minOf(rowStart+24,maxRow)
    val lastColumn=minOf(colStart+5,maxColumn)
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
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val compact=maxHeight<420.dp
    Column(Modifier.fillMaxSize().background(Color.White).padding(12.dp)) {
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
            OutlinedTextField(address,{address=it.uppercase()},label={Text("셀 주소")},singleLine=true,modifier=Modifier.width(94.dp),enabled=!busy,isError=!addressValid,
                keyboardOptions=KeyboardOptions(imeAction=ImeAction.Go),keyboardActions=KeyboardActions(onGo={goToCell()}))
            OutlinedTextField(input,{vm.field(sec.id+"#"+address,it)},label={Text("값 또는 =수식")},singleLine=true,modifier=Modifier.weight(1f),enabled=!busy && addressValid,
                keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={focus.clearFocus()}))
            FilledIconButton(onClick=goToCell,enabled=!busy){Icon(Icons.Default.Check,"셀로 이동")}
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text("계산: ${if(addressValid) calculation.raw[address] ?: "계산 중…" else "주소 오류"}",style=MaterialTheme.typography.labelMedium,modifier=Modifier.weight(1f).padding(vertical=6.dp),maxLines=1,overflow=TextOverflow.Ellipsis)
            if(s.format==Format.XLSX) TextButton(onClick={style=true},enabled=!busy && addressValid) {Text("선택 셀 서식")}
        }
        if(s.format==Format.XLSX) SheetRangeTools(s,sec,address,cells,vm,busy)
        if(!compact) {
        Text("SUM · COUNTIF · SUMIF · IF/IFS · VLOOKUP · XLOOKUP · INDEX/MATCH · TEXT 등 / 같은 시트·다른 시트 계산",style=MaterialTheme.typography.labelSmall)
        Row(verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick={rowStart=(rowStart-25).coerceAtLeast(1)},enabled=!busy){Text("↑ 25행")};TextButton(onClick={rowStart=(rowStart+25).coerceAtMost(maxRow)},enabled=!busy){Text("↓ 25행")}
            TextButton(onClick={colStart=(colStart-6).coerceAtLeast(1)},enabled=!busy){Text("← 열")};TextButton(onClick={colStart=(colStart+6).coerceAtMost(maxColumn)},enabled=!busy){Text("열 →")}
        }
        }
        key(sec.id,rowStart,colStart,windowRequest) {
        Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            LazyColumn {
                item { Row {CellBox("",false,{});for(c in colStart..lastColumn) CellBox(columnName(c),false,{})} }
                items((rowStart..lastRow).toList()) {r -> Row {
                    CellBox(r.toString(),false,{})
                    for(c in colStart..lastColumn) {val a=columnName(c)+r;val styles=(sec.cellStyles[a] ?: emptyMap())+(s.edits.styles[sec.id+"#"+a] ?: emptyMap());CellBox(calculation.formatted[a] ?: "…",a==address,{if(!busy) address=a},styles)}
                } }
            }
        }
        }
    }
    }
    if(style) StyleDialog(sec.id+"#"+address,s.format,(sec.cellStyles[address] ?: emptyMap())+(s.edits.styles[sec.id+"#"+address] ?: emptyMap()),vm) {style=false}
}
private data class SheetCalculation(val raw: Map<String,String> = emptyMap(),val formatted: Map<String,String> = emptyMap())
@Composable private fun CellBox(text: String,selected: Boolean,click: ()->Unit,styles: Map<String,String> = emptyMap()) {
    val background=styles["background"]?.let {runCatching {Color(AndroidColor.parseColor("#$it"))}.getOrNull()} ?: Color.White
    val color=styles["color"]?.let {runCatching {Color(AndroidColor.parseColor("#$it"))}.getOrNull()} ?: Color.Unspecified
    val formatted=text
    Box(Modifier.width(112.dp).height(44.dp).border(0.5.dp,if(selected) Green else Color(0xFFDDDFDA)).background(if(selected) Color(0xFFE4ECE5) else background).clickable(onClick=click).padding(8.dp),contentAlignment=when(styles["align"]) {"center"->Alignment.Center;"right"->Alignment.CenterEnd;else->Alignment.CenterStart}) {
        Text(formatted,maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=(styles["size"]?.toFloatOrNull() ?: 13f).coerceIn(8f,28f).sp,color=color,fontWeight=if(styles["bold"]=="true") FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun SearchDialog(s: Session,vm: EditorViewModel,close: ()->Unit) {
    var find by remember {mutableStateOf("")};var replacement by remember {mutableStateOf("")}
    val count=remember(s.edits.text,find) {var count=0;var from=0;if(find.isNotEmpty()) while(true) {val next=s.edits.text.indexOf(find,from);if(next<0) break;count++;from=next+find.length};count}
    AlertDialog(onDismissRequest=close,title={Text("검색·바꾸기")},text={Column {
        OutlinedTextField(find,{find=it},label={Text("찾을 문자열")},singleLine=true)
        Text("일치 ${count}개",modifier=Modifier.padding(vertical=8.dp))
        OutlinedTextField(replacement,{replacement=it},label={Text("바꿀 문자열")},singleLine=true)
    }},confirmButton={TextButton(onClick={vm.text(s.edits.text.replace(find,replacement));close()},enabled=find.isNotEmpty() && count>0){Text("모두 바꾸기")}},dismissButton={TextButton(onClick=close){Text("닫기")}})
}
