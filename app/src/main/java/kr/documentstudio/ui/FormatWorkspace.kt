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
import androidx.compose.ui.unit.dp
import kr.documentstudio.*
import kr.documentstudio.core.*
import kr.documentstudio.data.*

@Composable
fun FormatWorkspace(s: Session,settings: Settings,vm: DocumentActions,busy: Boolean,openOffice: ()->Unit) {
    if(s.format in EditorPolicy.visualFormats) {
        VisualDocumentWorkspace(s,settings,vm,busy,openOffice)
        return
    }
    val family=WorkspaceFamily.of(s.format)
    var tab by rememberSaveable(s.id) {mutableStateOf(family.tabs.first())}
    var expanded by rememberSaveable {mutableStateOf(true)}
    var preview by rememberSaveable {mutableStateOf(false)}
    var page by rememberSaveable {mutableIntStateOf(0)}
    var horizontal by rememberSaveable {mutableStateOf(false)}
    var navigationRequest by remember {mutableIntStateOf(0)}
    var search by remember {mutableStateOf(false)}
    var encoding by remember {mutableStateOf(false)}
    var ocr by remember {mutableStateOf(false)}
    val navigate: (Int)->Unit={page=it.coerceIn(0,((s.pdf?.pages ?: 1)-1).coerceAtLeast(0));navigationRequest++}
    LaunchedEffect(s.base) {page=page.coerceIn(0,((s.pdf?.pages ?: 1)-1).coerceAtLeast(0))}
    Column(Modifier.fillMaxSize().testTag("workspace-${family.name.lowercase()}")) {
        WorkspaceRibbon(family,tab,expanded,busy,{tab=it;expanded=true},{expanded=!expanded}) {
            if(tab!="그리기") {
                if(s.format==Format.PDF) DocumentTools(s,null,vm,busy,page,navigate,tab) {ocr=true}
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    if(s.format==Format.PDF && tab=="보기") {
                        FilterChip(!horizontal,{horizontal=false},label={Text("연속 보기")},enabled=!busy)
                        Spacer(Modifier.width(6.dp));FilterChip(horizontal,{horizontal=true},label={Text("가로 보기")},enabled=!busy)
                    }
                    if(family==WorkspaceFamily.TEXT) {
                        TextButton({search=true},enabled=!busy) {Icon(Icons.Default.Search,null);Text("검색·바꾸기")}
                        if(s.format==Format.MD) FilterChip(preview,{preview=!preview},label={Text("미리보기")},enabled=!busy)
                        if(tab=="보기") TextButton({encoding=true},enabled=!busy) {Text("저장 인코딩")}
                        TextButton({ocr=true},enabled=!busy) {Text("문자 인식")}
                    }
                    if(s.format==Format.PDF && tab=="편집") Text("페이지의 글자·이미지를 눌러 선택하고 직접 편집하세요.",style=MaterialTheme.typography.labelMedium,modifier=Modifier.padding(8.dp))
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFFE8ECF3))) {
            when {
                tab=="그리기"->DrawingPage(s,null,page,settings,vm,busy)
                s.format==Format.PDF && tab=="편집"->PdfCanvasEditor(s,page,vm,busy)
                s.format==Format.PDF->PdfContinuousReader(s,page,navigationRequest,busy,horizontal,{page=it}) {page=it;tab="그리기"}
                preview && s.format==Format.MD->MarkdownPreview(s.edits.text,busy,vm::text)
                else->NativeTextEditor(s.edits.text,settings.fontSize,busy,settings.wrapLines,vm::focus,vm::text,s.format==Format.MD)
            }
        }
        if(s.format==Format.PDF) Row(Modifier.fillMaxWidth().background(Color.White),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center) {
            IconButton({navigate(page-1)},enabled=page>0 && !busy) {Icon(Icons.AutoMirrored.Filled.NavigateBefore,"이전 페이지")}
            Text("${page+1} / ${s.pdf!!.pages}",style=MaterialTheme.typography.labelLarge)
            IconButton({navigate(page+1)},enabled=page<s.pdf.pages-1 && !busy) {Icon(Icons.AutoMirrored.Filled.NavigateNext,"다음 페이지")}
        } else WorkspaceStatus("${s.edits.text.length}자 · ${s.textContent?.charset ?: "UTF-8"}",if(s.dirty) "수정됨" else "저장됨")
    }
    if(search) SearchDialog(s,vm) {search=false}
    if(ocr) OcrDialog(s,null,page,vm) {ocr=false}
    if(encoding && s.textContent!=null) EncodingDialog(s,vm) {encoding=false}
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
