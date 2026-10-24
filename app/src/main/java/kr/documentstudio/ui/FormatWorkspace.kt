package kr.documentstudio.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.unit.dp
import kr.documentstudio.*
import kr.documentstudio.core.*
import kr.documentstudio.data.*

@Composable
fun FormatWorkspace(s: Session,settings: Settings,vm: DocumentActions,busy: Boolean,openOffice: ()->Unit) {
    val family=WorkspaceFamily.of(s.format)
    var tab by rememberSaveable {mutableStateOf(family.tabs.first())}
    var expanded by rememberSaveable {mutableStateOf(true)}
    var draw by rememberSaveable {mutableStateOf(false)}
    var preview by rememberSaveable {mutableStateOf(s.format==Format.PPTX)}
    var section by rememberSaveable {mutableIntStateOf(0)}
    var page by rememberSaveable {mutableIntStateOf(0)}
    var continuous by rememberSaveable {mutableStateOf(true)}
    var horizontal by rememberSaveable {mutableStateOf(false)}
    var navigationRequest by remember {mutableIntStateOf(0)}
    var navigator by rememberSaveable {mutableStateOf(false)}
    var selectedParagraph by rememberSaveable {mutableStateOf<String?>(null)}
    var selectionStart by rememberSaveable {mutableIntStateOf(0)}
    var selectionEnd by rememberSaveable {mutableIntStateOf(0)}
    var readOnly by rememberSaveable {mutableStateOf(false)}
    var ocr by remember {mutableStateOf(false)}
    var search by remember {mutableStateOf(false)}
    var encoding by remember {mutableStateOf(false)}
    var help by remember {mutableStateOf(false)}
    val current=s.sections.getOrNull(section)
    val selection=selectedParagraph?.let {PaperSelection(it,selectionStart,selectionEnd)}
    val navigate: (Int)->Unit = {page=it.coerceIn(0,((s.pdf?.pages ?: 1)-1).coerceAtLeast(0));navigationRequest++}
    val selectTab: (String)->Unit = {name ->
        tab=name;expanded=true;draw=name=="그리기"
        if(draw) preview=false
    }
    LaunchedEffect(s.base) {
        section=section.coerceIn(0,(s.sections.size-1).coerceAtLeast(0))
        page=page.coerceIn(0,((s.pdf?.pages ?: 1)-1).coerceAtLeast(0))
    }
    Column(Modifier.fillMaxSize().testTag("workspace-${family.name.lowercase()}")) {
        if(s.format in DocumentRepository.officeFormats) Surface(color=family.accent.copy(alpha=0.07f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
                Icon(family.icon,null,tint=family.accent,modifier=Modifier.size(22.dp))
                Column(Modifier.weight(1f).padding(horizontal=10.dp)) {
                    Text("${family.title} 문서 편집",style=MaterialTheme.typography.labelLarge)
                    Text(when(family) {WorkspaceFamily.SHEET->"셀·수식·행과 열·차트";WorkspaceFamily.SLIDE->"개체 선택·이동·크기·슬라이드";else->"본문·표·그림·개체 배치"},style=MaterialTheme.typography.labelSmall)
                }
                FilledTonalButton(openOffice,enabled=!busy,contentPadding=PaddingValues(horizontal=12.dp)) {Text("편집기 열기")}
            }
        }
        WorkspaceRibbon(family,tab,expanded,busy,selectTab,{expanded=!expanded}) {
            if(tab!="그리기") {
                if(tab=="보기" || family==WorkspaceFamily.TEXT || (tab=="홈" && family==WorkspaceFamily.WORD)) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {

                        if(s.format==Format.PDF && tab=="보기") {
                            FilterChip(continuous && !horizontal,{continuous=true;horizontal=false;draw=false},label={Text("연속 보기")},enabled=!busy)
                            Spacer(Modifier.width(6.dp))
                            FilterChip(continuous && horizontal,{continuous=true;horizontal=true;draw=false},label={Text("가로 보기")},enabled=!busy)
                        }
                        if(family==WorkspaceFamily.TEXT) {
                            TextButton({search=true},enabled=!busy) {Icon(Icons.Default.Search,null,Modifier.size(18.dp));Text("검색·바꾸기")}
                            if(s.format==Format.MD) FilterChip(preview,{preview=!preview},label={Text("미리보기")},enabled=!busy)
                            if(tab=="보기") TextButton({encoding=true},enabled=!busy) {Text("저장 인코딩")}
                        }
                        if(family==WorkspaceFamily.SLIDE && tab=="보기") {
                            FilterChip(preview,{preview=true},label={Text("개체 미리보기")},enabled=!busy)
                            TextButton({preview=false},enabled=!busy) {Text("텍스트 편집")}
                        }
                        if(tab=="보기") {
                            if(family in listOf(WorkspaceFamily.WORD,WorkspaceFamily.HANGUL)) FilterChip(readOnly,{readOnly=!readOnly},label={Text(if(readOnly) "읽기 모드" else "편집 모드")},enabled=!busy)
                            if(s.sections.size>1) TextButton({navigator=!navigator},enabled=!busy) {Icon(Icons.AutoMirrored.Filled.ViewSidebar,null,Modifier.size(18.dp));Text("문서 탐색")}
                            TextButton({help=true}) {Icon(Icons.Default.Info,null,Modifier.size(18.dp));Text("편집 안내")}
                        }
                        if(tab=="홈" || tab=="보기" || (s.format==Format.PDF && tab=="주석")) TextButton({ocr=true},enabled=!busy) {Icon(Icons.Default.DocumentScanner,null,Modifier.size(18.dp));Spacer(Modifier.width(4.dp));Text("문자 인식")}
                    }
                }
                if(tab=="홈" && current!=null && family in listOf(WorkspaceFamily.WORD,WorkspaceFamily.HANGUL)) PaperTextTools(s,current,selection,vm,busy || readOnly)
                if(family!=WorkspaceFamily.TEXT) DocumentTools(s,current,vm,busy,page,navigate,tab) {ocr=true}
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val showSide=maxWidth>=840.dp && !draw && family!=WorkspaceFamily.SHEET && s.sections.size>1
            Row(Modifier.fillMaxSize()) {
                if(showSide) SectionRail(s,section,busy,{section=it},Modifier.width(if(family==WorkspaceFamily.SLIDE) 184.dp else 168.dp).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if(s.sections.size>1 && family!=WorkspaceFamily.SHEET && !showSide) {
                        Row(Modifier.fillMaxWidth().background(Color(0xFFF8F9FC)).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                            TextButton({navigator=true},enabled=!busy,modifier=Modifier.weight(1f)) {Icon(Icons.AutoMirrored.Filled.List,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text(current?.title ?: "문서 탐색",maxLines=1)}
                            IconButton({section=(section-1).coerceAtLeast(0)},enabled=!busy && section>0) {Icon(Icons.AutoMirrored.Filled.NavigateBefore,"이전 구역")}
                            IconButton({section=(section+1).coerceAtMost(s.sections.lastIndex)},enabled=!busy && section<s.sections.lastIndex) {Icon(Icons.AutoMirrored.Filled.NavigateNext,"다음 구역")}
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFFE8ECF3))) {
                        when {
                            draw->DrawingPage(s,current,page,settings,vm,busy)
                            s.format==Format.PDF->PdfContinuousReader(s,page,navigationRequest,busy,horizontal,{page=it}) {page=it;selectTab("그리기")}
                            preview && s.format==Format.MD->MarkdownPreview(s.edits.text,busy,vm::text)
                            preview && s.format==Format.PPTX && current!=null->SlidePreview(s,current,vm,busy)
                            family==WorkspaceFamily.TEXT->NativeTextEditor(s.edits.text,settings.fontSize,busy,settings.wrapLines,vm::focus,vm::text,s.format==Format.MD)
                            family==WorkspaceFamily.SHEET && current!=null->SheetEditor(s,current,vm,busy,tab)
                            current!=null->DocumentPaper(s,current,vm,busy,readOnly) {selectedParagraph=it.paragraph;selectionStart=it.start;selectionEnd=it.end}
                        }
                    }
                }
            }
        }
        if(family==WorkspaceFamily.SHEET && s.sections.isNotEmpty()) Row(Modifier.fillMaxWidth().background(Color.White).horizontalScroll(rememberScrollState()).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
            s.sections.forEachIndexed {i,sec ->
                TextButton({section=i},enabled=!busy,colors=ButtonDefaults.textButtonColors(contentColor=if(i==section) family.accent else Color(0xFF677182)),modifier=Modifier.testTag("sheet-tab-$i")) {if(i==section) Icon(Icons.Default.TableChart,null,Modifier.size(16.dp));Text(sec.title)}
            }
            IconButton({vm.change(DocumentChange("sheet.add",current?.id ?: "",mapOf("name" to "시트${s.sections.size+1}")))},enabled=!busy && s.format==Format.XLSX) {Icon(Icons.Default.Add,"시트 추가")}
        }
        if(s.format==Format.PDF) Row(Modifier.fillMaxWidth().background(Color.White),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center) {
            IconButton({navigate(page-1)},enabled=page>0 && !busy) {Icon(Icons.AutoMirrored.Filled.NavigateBefore,"이전 페이지")}
            Text("${page+1} / ${s.pdf!!.pages}",style=MaterialTheme.typography.labelLarge)
            IconButton({navigate(page+1)},enabled=page<s.pdf.pages-1 && !busy) {Icon(Icons.AutoMirrored.Filled.NavigateNext,"다음 페이지")}
        } else WorkspaceStatus(if(draw) "${s.edits.strokes.size}획 · 문서와 함께 복구" else if(family==WorkspaceFamily.TEXT) "${s.edits.text.length}자 · ${s.textContent?.charset ?: "UTF-8"}" else if(family==WorkspaceFamily.SLIDE) "슬라이드 ${section+1} / ${s.sections.size}" else "${s.format.label} · ${current?.title ?: "본문"}",if(s.dirty) "수정됨" else "저장됨")
    }
    if(navigator) AlertDialog(onDismissRequest={navigator=false},title={Text(if(family==WorkspaceFamily.SLIDE) "슬라이드" else "문서 탐색")},text={SectionRail(s,section,busy,{section=it;navigator=false},Modifier.fillMaxWidth().heightIn(max=430.dp))},confirmButton={TextButton({navigator=false}) {Text("닫기")}})
    if(ocr) OcrDialog(s,current,page,vm) {ocr=false}
    if(search) SearchDialog(s,vm) {search=false}
    if(encoding && s.textContent!=null) EncodingDialog(s,vm) {encoding=false}
    if(help) AlertDialog(onDismissRequest={help=false},title={Text("${s.format.label} 편집 안내")},text={Text(when(family) {
        WorkspaceFamily.WORD,WorkspaceFamily.SHEET,WorkspaceFamily.SLIDE->"‘문서에서 편집’은 실제 배치와 개체를 편집합니다. 기본 편집에서 지원하지 않는 복잡한 개체도 이 화면에서 작업하세요. 닫고 돌아온 뒤 상단 저장을 눌러 원본 파일에 반영합니다. 필기는 별도 작업 데이터에 보존됩니다."
        WorkspaceFamily.HANGUL->"본문을 눌러 입력하고 홈에서 글자 서식을 바꿉니다. 삽입과 레이아웃 도구로 표와 용지를 편집합니다. 원래 쪽 배치를 재현하는 전문 한글 조판은 지원하지 않습니다. HWP는 HWPX로 변환한 뒤 추가 편집할 수 있습니다."
        WorkspaceFamily.PDF->"보기에서 읽고, 주석에서 텍스트 표시와 메모, 그리기에서 필기를 사용하세요. 기존 본문 편집은 원래 폰트가 지원하는 가로 문자열에 한정됩니다. PDF/A는 일반 PDF 복사본으로 저장합니다."
        WorkspaceFamily.TEXT->"본문을 직접 입력하세요. Markdown은 미리보기로 결과를 확인할 수 있습니다. 그리기는 본문과 별도의 고정 필기 페이지입니다."
    })},confirmButton={TextButton({help=false}) {Text("확인")}})
}

@Composable
private fun SectionRail(s: Session,index: Int,busy: Boolean,select: (Int)->Unit,modifier: Modifier) {
    LazyColumn(modifier.background(Color(0xFFF4F6FA)).padding(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        itemsIndexed(s.sections,key={_,sec->sec.id}) {i,sec ->
            Surface(onClick={select(i)},enabled=!busy,color=if(i==index) WorkspaceFamily.of(s.format).accent.copy(alpha=0.09f) else Color.White,border=BorderStroke(if(i==index) 2.dp else 1.dp,if(i==index) WorkspaceFamily.of(s.format).accent else Color(0xFFE0E5EE))) {
                Column(Modifier.fillMaxWidth().padding(10.dp)) {
                    if(s.format==Format.PPTX) {
                        val scene=rememberSlide(s,sec)
                        scene?.let {model->androidx.compose.ui.viewinterop.AndroidView(factory={SlideView(it)},update={it.scene=model;it.editable=false;it.invalidate()},modifier=Modifier.fillMaxWidth().aspectRatio(model.width/model.height))}
                    }
                    Text(sec.title,maxLines=2,style=MaterialTheme.typography.labelLarge,modifier=Modifier.padding(top=4.dp))
                }
            }
        }
    }
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
