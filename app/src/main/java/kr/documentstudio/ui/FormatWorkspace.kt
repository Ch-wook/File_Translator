package kr.documentstudio.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import kr.documentstudio.*
import kr.documentstudio.core.*
import kr.documentstudio.data.*

@Composable
fun FormatWorkspace(s: Session,settings: Settings,vm: DocumentActions,busy: Boolean,presentation: PresentationState=PresentationState.READING,reader: ReaderActions?=null,openOffice: ()->Unit) {
    if(s.format in EditorPolicy.visualFormats) {VisualDocumentWorkspace(s,settings,vm,busy,presentation,openOffice);return}
    val family=WorkspaceFamily.of(s.format)
    val editing=presentation.allowsMutation
    var panel by rememberSaveable(s.id) {mutableStateOf<String?>(null)}
    var preview by rememberSaveable(s.id) {mutableStateOf(s.format==Format.MD)}
    var ink by rememberSaveable(s.id) {mutableStateOf(false)}
    val readerContext=LocalContext.current
    val readerPreferences=remember {readerContext.getSharedPreferences("reading-position",0)}
    var page by rememberSaveable(s.id) {mutableIntStateOf(readerPreferences.getInt(s.id+":page",0))}
    var readingZoom by rememberSaveable(s.id) {mutableFloatStateOf(readerPreferences.getFloat(s.id+":zoom",1f))}
    var continuous by rememberSaveable(s.id) {mutableStateOf(false)}
    var search by remember {mutableStateOf(false)}
    var encoding by remember {mutableStateOf(false)}
    var ocr by remember {mutableStateOf(false)}
    val textController=remember(s.id) {NativeEditorController()}
    val pdfController=remember(s.id) {PdfEditorController()}
    val context=LocalContext.current
    val preferences=remember {context.getSharedPreferences("editor-display",0)}
    var displayFont by rememberSaveable {mutableStateOf(preferences.getString("font","고정폭") ?: "고정폭")}
    var displaySize by rememberSaveable {mutableFloatStateOf(preferences.getFloat("size",settings.fontSize))}
    var dark by rememberSaveable {mutableStateOf(preferences.getBoolean("dark",false))}
    var lineNumbers by rememberSaveable {mutableStateOf(preferences.getBoolean("lineNumbers",true))}
    val textStats=remember(s.edits.text){s.edits.text.codePointCount(0,s.edits.text.length) to (1+s.edits.text.count {it=='\n'})}
    var fontList by rememberSaveable {mutableStateOf(false)}
    var fontQuery by rememberSaveable {mutableStateOf("")}
    var favorites by remember {mutableStateOf(preferences.getStringSet("favorites",emptySet())!!.toSet())}
    var fontCategory by rememberSaveable {mutableStateOf("전체")}
    var wrap by rememberSaveable {mutableStateOf(preferences.getBoolean("wrap",settings.wrapLines))}
    val navigate: (Int)->Unit={page=it.coerceIn(0,((s.pdf?.pages ?: 1)-1).coerceAtLeast(0));readerPreferences.edit().putInt(s.id+":page",page).apply()}
    SideEffect {
        reader?.search={if(s.format==Format.PDF)panel="PDF 검색" else {preview=false;search=true}}
        reader?.settings={panel="보기 설정"}
    }
    DisposableEffect(s.id) {onDispose {readerPreferences.edit().putInt(s.id+":page",page).putFloat(s.id+":zoom",pdfController.zoom).apply()}}
    LaunchedEffect(s.base) {navigate(page)}
    LaunchedEffect(editing) {panel=null;if(!editing)ink=false;if(s.format==Format.MD)preview=!editing}
    val imageSelected=pdfController.selected is PdfImagePlacement
    LaunchedEffect(imageSelected) {if(panel!=null)panel=if(imageSelected) "그림" else "글자"}
    fun choose(value: String){
        textController.capture();fontList=false
        if(value=="미리보기"){preview=!preview;panel=null;return}
        if(s.format==Format.MD)preview=false
        val target=when(value){"목록"->"문단";"보기","텍스트"->"글자";"이미지"->"그림";else->value}
        panel=if(panel==target)null else target
    }
    @Composable fun Tool(label: String,action: ()->Unit){TextButton(action,enabled=!busy,modifier=Modifier.focusProperties {canFocus=false}) {Text(label)}}
    androidx.activity.compose.BackHandler(panel!=null) {panel=null;fontList=false}
    BoxWithConstraints(Modifier.fillMaxSize().testTag("workspace-${family.name.lowercase()}")) {
      val sheetLimit=(maxHeight*.4f).coerceAtMost(280.dp)
      Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFFE8ECF3))) {
            when {
                editing && ink->DrawingPage(s,null,page,settings,vm,busy)
                s.format==Format.PDF && !editing && continuous->PdfContinuousReader(s,page,0,busy,onVisiblePage=navigate,onEditPage=null)
                s.format==Format.PDF->PdfCanvasEditor(s,page,vm,busy,readOnly=!editing,controller=pdfController,initialZoom=readingZoom)
                preview && s.format==Format.MD->MarkdownPreview(s.edits.text,busy || !editing,vm::text,viewportKey=s.id,dark=dark)
                else->NativeTextEditor(s.edits.text,displaySize,busy,wrap,vm::focus,vm::text,s.format==Format.MD,readOnly=!editing,controller=textController,showToolbar=false,fontFamily=displayFont,showLineNumbers=lineNumbers,viewportKey=s.id,dark=dark)
            }
        }
        if(panel!=null) MobileEditorSheet(if(fontList)"보기 글꼴" else if(s.format==Format.TXT && panel=="글자")"보기 설정" else panel!!,sheetLimit,{panel=null;fontList=false;if(editing&&s.format!=Format.PDF)textController.focus()}) {
          when(panel) {
            "글자"->if(s.format==Format.PDF) {
                val run=pdfController.selected as? PdfBodyRun
                Text(run?.let {"현재 글꼴: ${it.font}"} ?: "페이지의 본문을 눌러 편집합니다.",style=MaterialTheme.typography.bodySmall)
                Text("PDF 기존 본문은 원본 글꼴로 편집합니다. 글꼴·크기·자간·장평 변경은 지원하지 않습니다.",style=MaterialTheme.typography.bodySmall)
                Row {Tool("적용",pdfController.apply);Tool("취소",pdfController.cancel)}
                DocumentTools(s,null,vm,busy,page,navigate,"편집"){ocr=true}
            } else {
                if(s.format==Format.MD) Row(Modifier.horizontalScroll(rememberScrollState())) {Tool("굵게"){textController.markdown("bold")};Tool("기울임"){textController.markdown("italic")};Tool("인라인 코드"){textController.markdown("code")};Tool("취소선"){textController.markdown("strike")}}
              Text("보기 설정 · TXT·Markdown 파일에는 글꼴·크기·색상 서식이 저장되지 않습니다.",style=MaterialTheme.typography.bodySmall)
              if(fontList) {
                OutlinedTextField(fontQuery,{fontQuery=it},label={Text("글꼴 검색")},singleLine=true,modifier=Modifier.fillMaxWidth())
                val actualFonts=EditorFonts.names
                Row(Modifier.horizontalScroll(rememberScrollState())) {listOf("전체","한국어","영문·기타","즐겨찾기").forEach {category->Tool((if(fontCategory==category)"✓ " else "")+category){fontCategory=category}}}
                val recent=(preferences.getString("recentList",null)?.split('\n') ?: listOf(preferences.getString("recent","")!!)).filter {it in actualFonts}.take(5)
                actualFonts.sortedBy {if(it in recent)recent.indexOf(it) else 5}.filter {EditorFonts.label(it).contains(fontQuery,true) && when(fontCategory) {"즐겨찾기"->it in favorites;"한국어"->it in EditorFonts.korean;"영문·기타"->it !in EditorFonts.korean;else->true}}.forEach {name->
                    val face=remember(name) {EditorFonts.face(context,name)}
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        TextButton({displayFont=name;preferences.edit().putString("font",name).putString("recent",name).putString("recentList",(listOf(name)+recent.filter {it!=name}).take(5).joinToString("\n")).apply();fontList=false;textController.restore()},modifier=Modifier.weight(1f)) {
                            Column(Modifier.weight(1f)) {Text((if(name==displayFont)"✓ " else "")+EditorFonts.label(name)+(if(name in recent)" · 최근" else ""));Text("가나다 Aa 123",fontFamily=androidx.compose.ui.text.font.FontFamily(face))}
                        }
                        IconToggleButton(name in favorites,{checked->favorites=if(checked)favorites+name else favorites-name;preferences.edit().putStringSet("favorites",favorites).apply()}) {Icon(if(name in favorites)Icons.Default.Star else Icons.Default.StarBorder,name+" 즐겨찾기")}
                    }
                }
                Tool("기본 보기 글꼴") {displayFont="고정폭";preferences.edit().putString("font",displayFont).apply();fontList=false;textController.restore()}
              } else {
                Tool("글꼴 · $displayFont") {fontList=true}
                var sizeText by remember(displaySize) {mutableStateOf(displaySize.toInt().toString())}
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Tool("−") {displaySize=(displaySize-1).coerceAtLeast(8f);preferences.edit().putFloat("size",displaySize).apply()}
                    OutlinedTextField(sizeText,{value->sizeText=value;value.toFloatOrNull()?.takeIf {it in 8f..72f}?.let {displaySize=it;preferences.edit().putFloat("size",it).apply()}},label={Text("보기 크기(sp)")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.weight(1f))
                    Tool("＋") {displaySize=(displaySize+1).coerceAtMost(72f);preferences.edit().putFloat("size",displaySize).apply()}
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {listOf(10,12,14,16,18,20,24,28,32).forEach {n->Tool("$n"){displaySize=n.toFloat();preferences.edit().putFloat("size",displaySize).apply()}}}

              }
            }
            "문단"->if(s.format==Format.MD) {
                Row(Modifier.horizontalScroll(rememberScrollState())) {(1..6).forEach {level->Tool("제목 $level"){textController.markdown("heading$level")}}}
                Row(Modifier.horizontalScroll(rememberScrollState())) {listOf("heading" to "제목","list" to "글머리표","number" to "번호","task" to "체크리스트","quote" to "인용").forEach {(id,label)->Tool(label){textController.markdown(id)}}}
                Text("Markdown 구문으로 저장합니다. 글자색·장평·문단 정렬은 이 형식에 저장할 수 없습니다.",style=MaterialTheme.typography.bodySmall)
            } else Text(if(s.format==Format.TXT)"TXT는 원문 텍스트와 줄바꿈을 보존합니다. 문단 서식은 지원하지 않습니다." else "PDF 문단 재배치와 줄 간격 변경은 지원하지 않습니다.",style=MaterialTheme.typography.bodySmall)
            "표"->if(s.format==Format.MD) {Tool("표 삽입"){textController.markdown("table")};Text("Markdown 표 구문을 삽입합니다. 셀 병합은 지원하지 않습니다.",style=MaterialTheme.typography.bodySmall)}
                else Text("이 파일 형식은 표 구조 편집을 지원하지 않습니다.",style=MaterialTheme.typography.bodySmall)
            "그림"->if(s.format==Format.PDF) {Text("페이지에서 그림을 드래그해 이동하고 오른쪽 아래 핸들로 크기를 바꿉니다.",style=MaterialTheme.typography.bodySmall);Row {Tool("적용",pdfController.apply);Tool("취소",pdfController.cancel);Tool("이미지 삭제",pdfController.deleteImage)};DocumentTools(s,null,vm,busy,page,navigate,"삽입"){ocr=true}}
            "선택","페이지","주석","PDF 검색"->DocumentTools(s,null,vm,busy,page,navigate,if(panel=="PDF 검색")"보기" else if(panel=="선택")"주석" else panel!!){ocr=true}
            "삽입"->if(s.format==Format.PDF) DocumentTools(s,null,vm,busy,page,navigate,"삽입"){ocr=true}
                else Row(Modifier.horizontalScroll(rememberScrollState())) {
                    if(s.format==Format.MD) {Tool("링크"){textController.markdown("link")};Tool("코드 블록"){textController.markdown("block")};Tool("이미지 링크"){textController.markdown("image")};Tool("표 삽입"){textController.markdown("table")};Tool("구분선"){textController.markdown("rule")}}
                    Tool("붙여넣기"){textController.clipboard(android.R.id.paste)}
                    Tool("서식 없이 붙여넣기"){textController.clipboard(android.R.id.pasteAsPlainText)}
                }
            "편집","찾기"->{
                Row(Modifier.horizontalScroll(rememberScrollState())) {Tool("복사"){textController.clipboard(android.R.id.copy)};Tool("잘라내기"){textController.clipboard(android.R.id.cut)};Tool("붙여넣기"){textController.clipboard(android.R.id.paste)};Tool("전체 선택"){textController.clipboard(android.R.id.selectAll)}}
                Tool("검색·바꾸기"){search=true}
                Text("${textStats.first}자 · ${textStats.second}줄",style=MaterialTheme.typography.bodySmall)
            }
            else->{
                if(editing) Tool("보기") {vm.readDocument();panel=null}
                Tool("별도 필기"){if(!editing)vm.beginInk();ink=!ink;panel=null}
                if(s.format!=Format.PDF) {
                    Row(Modifier.horizontalScroll(rememberScrollState())) {Tool("복사"){textController.clipboard(android.R.id.copy)};if(editing)Tool("잘라내기"){textController.clipboard(android.R.id.cut)};Tool("전체 선택"){textController.clipboard(android.R.id.selectAll)}}
                    if(editing)Tool("검색·바꾸기"){search=true}
                    Row {Tool("문서 맨 위"){textController.navigate(false)};Tool("문서 맨 아래"){textController.navigate(true)}}
                    if(s.format==Format.MD) Tool(if(preview)"원문 편집" else "미리보기"){preview=!preview;panel=null}
                    if(editing)Tool("저장 인코딩"){encoding=true}
                    Tool("보기 글꼴·크기"){panel="글자"}
                    Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(wrap,{wrap=it;preferences.edit().putBoolean("wrap",it).apply()});Text("자동 줄바꿈")}
                    Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(dark,{dark=it;preferences.edit().putBoolean("dark",it).apply()});Text("어두운 보기")}
                    Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(lineNumbers,{lineNumbers=it;preferences.edit().putBoolean("lineNumbers",it).apply()});Text("줄 번호")}
                    Text("${textStats.first}자 · ${textStats.second}줄",style=MaterialTheme.typography.bodySmall)
                } else {
                    if(!editing) Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(continuous,{continuous=it});Text("연속 페이지 보기")}
                    Row(verticalAlignment=Alignment.CenterVertically) {Tool("이전 페이지"){navigate(page-1)};Text("${page+1} / ${s.pdf!!.pages}");Tool("다음 페이지"){navigate(page+1)}}
                    Row(verticalAlignment=Alignment.CenterVertically) {Tool("축소"){pdfController.setZoom(pdfController.zoom-.25f)};Text("${(pdfController.zoom*100).toInt()}%");Tool("확대"){pdfController.setZoom(pdfController.zoom+.25f)};Tool("너비 맞춤"){pdfController.setZoom(1f)};Tool("전체 페이지 맞춤"){pdfController.fitPage()}}
                    Tool("PDF 검색"){panel="PDF 검색"}
                    if(editing){Row{Tool("페이지 도구"){panel="페이지"};Tool("주석"){panel="주석"}};DocumentTools(s,null,vm,busy,page,navigate,"편집"){ocr=true}}
                }
                if(editing)Tool("문자 인식"){ocr=true}
                Text(if(s.dirty)"수정됨 · 원본과 분리된 작업 복사본에 자동 저장" else "변경 사항 없음",style=MaterialTheme.typography.bodySmall)
            }
          }
        }
        if(editing) {
            val items=when(s.format) {
                Format.TXT->listOf(MobileDockItem("편집",Icons.Default.Edit),MobileDockItem("찾기",Icons.Default.Search),MobileDockItem("보기",Icons.Default.Tune),MobileDockItem("더보기",Icons.Default.MoreHoriz))
                Format.MD->listOf(MobileDockItem("글자",Icons.Default.TextFields),MobileDockItem("목록",Icons.Default.FormatListBulleted),MobileDockItem("삽입",Icons.Default.Add),MobileDockItem("미리보기",Icons.Default.Visibility),MobileDockItem("더보기",Icons.Default.MoreHoriz))
                else->listOf(MobileDockItem("선택",Icons.Default.SelectAll),MobileDockItem("주석",Icons.Default.Draw),MobileDockItem(if(imageSelected)"이미지" else "텍스트",if(imageSelected)Icons.Default.Image else Icons.Default.TextFields),MobileDockItem("페이지",Icons.Default.Description),MobileDockItem("더보기",Icons.Default.MoreHoriz))
            }
            val selected=if(s.format==Format.MD && preview)"미리보기" else when(panel) {"글자"->if(s.format==Format.TXT)"보기" else if(s.format==Format.PDF)"텍스트" else "글자";"문단"->"목록";"그림"->"이미지";else->panel}
            MobileEditorDock(items,selected,!busy,::choose)
        }
      }
    }
    if(search) SearchDialog(s,vm,textController,readOnly=!editing){search=false}
    if(ocr) OcrDialog(s,null,page,vm){ocr=false}
    if(encoding && s.textContent!=null)EncodingDialog(s,vm){encoding=false}
}
@Composable
private fun EncodingDialog(s: Session,vm: DocumentActions,close: ()->Unit) {
    var charset by remember {mutableStateOf(s.textContent!!.charset)}
    var bom by remember {mutableStateOf(s.textContent!!.bom)}
    AlertDialog(onDismissRequest=close,title={Text("저장 인코딩")},text={Column {
        listOf("UTF-8","UTF-16LE","UTF-16BE","MS949").forEach {cs->Row(verticalAlignment=Alignment.CenterVertically) {RadioButton(charset==cs,{charset=cs});Text(cs)}}
        Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(bom,{bom=it},enabled=charset!="MS949");Text("BOM 포함")}
        Text("변환할 수 없는 문자는 저장 전에 알려드립니다.")
    }},confirmButton={TextButton({vm.reencode(charset,bom && charset!="MS949");close()}) {Text("적용")}},dismissButton={TextButton(close) {Text("취소")}})
}
