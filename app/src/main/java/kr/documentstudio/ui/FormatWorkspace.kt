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
fun FormatWorkspace(s: Session,settings: Settings,vm: DocumentActions,busy: Boolean,presentation: PresentationState=PresentationState.READING,openOffice: ()->Unit) {
    if(s.format in EditorPolicy.visualFormats) {VisualDocumentWorkspace(s,settings,vm,busy,presentation,openOffice);return}
    val family=WorkspaceFamily.of(s.format)
    val editing=presentation.allowsMutation
    var panel by rememberSaveable(s.id) {mutableStateOf<String?>(null)}
    var preview by rememberSaveable(s.id) {mutableStateOf(false)}
    var ink by rememberSaveable(s.id) {mutableStateOf(false)}
    var page by rememberSaveable(s.id) {mutableIntStateOf(0)}
    var search by remember {mutableStateOf(false)}
    var encoding by remember {mutableStateOf(false)}
    var ocr by remember {mutableStateOf(false)}
    val textController=remember(s.id) {NativeEditorController()}
    val pdfController=remember(s.id) {PdfEditorController()}
    val context=LocalContext.current
    val preferences=remember {context.getSharedPreferences("editor-display",0)}
    var displayFont by rememberSaveable {mutableStateOf(preferences.getString("font","고정폭") ?: "고정폭")}
    var displaySize by rememberSaveable {mutableFloatStateOf(preferences.getFloat("size",settings.fontSize))}
    var fontList by rememberSaveable {mutableStateOf(false)}
    var fontQuery by rememberSaveable {mutableStateOf("")}
    val navigate: (Int)->Unit={page=it.coerceIn(0,((s.pdf?.pages ?: 1)-1).coerceAtLeast(0))}
    LaunchedEffect(s.base) {navigate(page)}
    LaunchedEffect(editing) {panel=null;if(!editing)ink=false}
    val imageSelected=pdfController.selected is PdfImagePlacement
    LaunchedEffect(imageSelected) {if(panel!=null)panel=if(imageSelected) "그림" else "글자"}
    fun choose(value: String){textController.capture();fontList=false;panel=if(panel==value)null else value}
    @Composable fun Tool(label: String,action: ()->Unit){TextButton(action,enabled=!busy,modifier=Modifier.focusProperties {canFocus=false}) {Text(label)}}
    androidx.activity.compose.BackHandler(panel!=null) {panel=null;fontList=false}
    BoxWithConstraints(Modifier.fillMaxSize().testTag("workspace-${family.name.lowercase()}")) {
      val sheetLimit=(maxHeight*.4f).coerceAtMost(280.dp)
      Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFFE8ECF3))) {
            when {
                editing && ink->DrawingPage(s,null,page,settings,vm,busy)
                s.format==Format.PDF->PdfCanvasEditor(s,page,vm,busy,readOnly=!editing,controller=pdfController)
                preview && s.format==Format.MD->MarkdownPreview(s.edits.text,busy || !editing,vm::text)
                else->NativeTextEditor(s.edits.text,displaySize,busy,settings.wrapLines,vm::focus,vm::text,s.format==Format.MD,readOnly=!editing,controller=textController,showToolbar=false,fontFamily=displayFont)
            }
            if(!editing) FilledTonalButton({vm.beginEditing()},enabled=!busy && presentation!=PresentationState.READ_ONLY && presentation!=PresentationState.ERROR,
                modifier=Modifier.align(Alignment.BottomEnd).padding(12.dp).testTag("edit-document")) {Icon(Icons.Default.Edit,null);Text("편집하기")}
        }
        if(panel!=null) MobileEditorSheet(if(fontList)"보기 글꼴" else panel!!,sheetLimit,{panel=null;fontList=false;if(editing&&s.format!=Format.PDF)textController.focus()}) {
          when(panel) {
            "글자"->if(s.format==Format.PDF) {
                val run=pdfController.selected as? PdfBodyRun
                Text(run?.let {"현재 글꼴: ${it.font}"} ?: "페이지의 본문을 눌러 편집합니다.",style=MaterialTheme.typography.bodySmall)
                Text("PDF 기존 본문은 원본 글꼴로 편집합니다. 글꼴·크기·자간·장평 변경은 지원하지 않습니다.",style=MaterialTheme.typography.bodySmall)
                Row {Tool("적용",pdfController.apply);Tool("취소",pdfController.cancel)}
            } else {
              Text("보기 설정 · TXT·Markdown 파일에는 글꼴·크기·색상 서식이 저장되지 않습니다.",style=MaterialTheme.typography.bodySmall)
              if(fontList) {
                OutlinedTextField(fontQuery,{fontQuery=it},label={Text("글꼴 검색")},singleLine=true,modifier=Modifier.fillMaxWidth())
                val actualFonts=listOf("Noto Sans KR","시스템 고딕","시스템 바탕","고정폭")
                val recent=preferences.getString("recent","")
                actualFonts.sortedBy {if(it==recent)0 else 1}.filter {it.contains(fontQuery,true)}.forEach {name->
                    val face=remember(name) {when(name) {"Noto Sans KR"->android.graphics.Typeface.createFromAsset(context.assets,"fonts/NotoSansKR.ttf");"시스템 바탕"->android.graphics.Typeface.SERIF;"고정폭"->android.graphics.Typeface.MONOSPACE;else->android.graphics.Typeface.SANS_SERIF}}
                    TextButton({displayFont=name;preferences.edit().putString("font",name).putString("recent",name).apply();fontList=false;textController.restore()},modifier=Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {Text((if(name==displayFont)"✓ " else "")+name);Text("가나다 Aa 123",fontFamily=androidx.compose.ui.text.font.FontFamily(face))}
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
                if(s.format==Format.MD) Row {Tool("굵게"){textController.markdown("bold")};Tool("기울임"){textController.markdown("italic")};Tool("인라인 코드"){textController.markdown("code")}}
              }
            }
            "문단"->if(s.format==Format.MD) {
                Row(Modifier.horizontalScroll(rememberScrollState())) {listOf("heading" to "제목","list" to "글머리표","number" to "번호","task" to "체크리스트","quote" to "인용").forEach {(id,label)->Tool(label){textController.markdown(id)}}}
                Text("Markdown 구문으로 저장합니다. 글자색·장평·문단 정렬은 이 형식에 저장할 수 없습니다.",style=MaterialTheme.typography.bodySmall)
            } else Text(if(s.format==Format.TXT)"TXT는 원문 텍스트와 줄바꿈을 보존합니다. 문단 서식은 지원하지 않습니다." else "PDF 문단 재배치와 줄 간격 변경은 지원하지 않습니다.",style=MaterialTheme.typography.bodySmall)
            "표"->if(s.format==Format.MD) {Tool("표 삽입"){textController.markdown("table")};Text("Markdown 표 구문을 삽입합니다. 셀 병합은 지원하지 않습니다.",style=MaterialTheme.typography.bodySmall)}
                else Text("이 파일 형식은 표 구조 편집을 지원하지 않습니다.",style=MaterialTheme.typography.bodySmall)
            "그림"->if(s.format==Format.PDF) {Text("페이지에서 그림을 드래그해 이동하고 오른쪽 아래 핸들로 크기를 바꿉니다.",style=MaterialTheme.typography.bodySmall);Row {Tool("적용",pdfController.apply);Tool("취소",pdfController.cancel);Tool("이미지 삭제",pdfController.deleteImage)};DocumentTools(s,null,vm,busy,page,navigate,"삽입"){ocr=true}}
            "페이지","주석","PDF 검색"->DocumentTools(s,null,vm,busy,page,navigate,if(panel=="PDF 검색")"보기" else panel!!){ocr=true}
            "삽입"->if(s.format==Format.PDF) DocumentTools(s,null,vm,busy,page,navigate,"삽입"){ocr=true}
                else Row(Modifier.horizontalScroll(rememberScrollState())) {
                    if(s.format==Format.MD) {Tool("링크"){textController.markdown("link")};Tool("코드 블록"){textController.markdown("block")}}
                    Tool("붙여넣기"){textController.clipboard(android.R.id.paste)}
                    Tool("서식 없이 붙여넣기"){textController.clipboard(android.R.id.pasteAsPlainText)}
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
                } else {
                    Row(verticalAlignment=Alignment.CenterVertically) {Tool("이전 페이지"){navigate(page-1)};Text("${page+1} / ${s.pdf!!.pages}");Tool("다음 페이지"){navigate(page+1)}}
                    Row(verticalAlignment=Alignment.CenterVertically) {Tool("축소"){pdfController.setZoom(pdfController.zoom-.25f)};Text("${(pdfController.zoom*100).toInt()}%");Tool("확대"){pdfController.setZoom(pdfController.zoom+.25f)};Tool("너비 맞춤"){pdfController.setZoom(1f)}}
                    Tool("PDF 검색"){panel="PDF 검색"}
                    if(editing){Row{Tool("페이지 도구"){panel="페이지"};Tool("주석"){panel="주석"}};DocumentTools(s,null,vm,busy,page,navigate,"편집"){ocr=true}}
                }
                if(editing)Tool("문자 인식"){ocr=true}
                Text(if(s.dirty)"수정됨 · 원본과 분리된 작업 복사본에 자동 저장" else "저장됨",style=MaterialTheme.typography.bodySmall)
            }
          }
        }
        if(editing) {
            val items=listOf(MobileDockItem("글자",Icons.Default.TextFields),MobileDockItem("문단",Icons.Default.FormatAlignLeft))+
                (if(imageSelected)listOf(MobileDockItem("그림",Icons.Default.Image)) else if(s.format==Format.MD)listOf(MobileDockItem("표",Icons.Default.TableChart)) else emptyList())+
                listOf(MobileDockItem("삽입",Icons.Default.Add),MobileDockItem("더보기",Icons.Default.MoreHoriz))
            MobileEditorDock(items,panel,!busy,::choose)
        } else MobileEditorDock(listOf(MobileDockItem("보기 설정",Icons.Default.Tune)),panel,!busy,::choose)
      }
    }
    if(search) SearchDialog(s,vm){search=false}
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
