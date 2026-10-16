package kr.documentstudio.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kr.documentstudio.DocumentActions
import kr.documentstudio.core.DocumentChange
import kr.documentstudio.data.*

@Composable
fun PdfMarkupTools(s: Session,page: Int,vm: DocumentActions,initialManage: Boolean=false,close: ()->Unit) {
    var manage by remember {mutableStateOf(initialManage)}
    var entry by remember {mutableStateOf<PdfAnnotationEntry?>(null)}
    var comment by remember {mutableStateOf("")};var color by remember {mutableStateOf("FFE36B")};var kind by remember {mutableStateOf("Highlight")}
    var problem by remember {mutableStateOf<String?>(null)}
    val source by produceState<PdfSelectableText?>(null,s.base,page) {
        try {value=withContext(Dispatchers.IO) {PdfAnnotations.selectable(s.base,page)}}
        catch(e: CancellationException) {throw e} catch(e: Exception) {problem=e.message ?: "텍스트를 읽을 수 없습니다."}
    }
    val entries by produceState<List<PdfAnnotationEntry>?>(null,s.base,page) {
        try {value=withContext(Dispatchers.IO) {PdfAnnotations.list(s.base,page)}}
        catch(e: CancellationException) {throw e} catch(e: Exception) {problem=e.message ?: "주석을 읽을 수 없습니다."}
    }
    val display=source?.text.orEmpty().take(50000)
    var text by remember(source) {mutableStateOf(TextFieldValue(display))}
    val first=text.selection.min;val last=text.selection.max
    AlertDialog(onDismissRequest=close,title={Text("${page+1} 페이지 텍스트 주석")},text={Column(Modifier.heightIn(max=460.dp).verticalScroll(rememberScrollState())) {
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(!manage,{manage=false;entry=null},label={Text("문자 선택")})
            FilterChip(manage,{manage=true},label={Text("기존 주석")})
        }
        problem?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        if(!manage) {
            Text("글자를 길게 눌러 범위를 선택하세요. 원문은 바뀌지 않으며 PDF 주석으로 저장됩니다.",style=MaterialTheme.typography.labelSmall)
            if(source==null && problem==null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if(source==null) Text("닫은 후 문서를 다시 열어 주세요.")
            else if(source!!.text.isBlank()) Text("선택 가능한 텍스트가 없습니다. 스캔 문서는 OCR이 필요합니다.")
            else OutlinedTextField(text,{next -> text=TextFieldValue(display,TextRange(next.selection.start.coerceIn(0,display.length),next.selection.end.coerceIn(0,display.length)))},readOnly=true,
                label={Text("주석을 넣을 텍스트 선택")},modifier=Modifier.fillMaxWidth().heightIn(min=130.dp,max=260.dp))
            if(source?.text.orEmpty().length>50000) Text("처음 50,000자를 표시합니다.",style=MaterialTheme.typography.labelSmall)
            Text("${last-first}자 선택",style=MaterialTheme.typography.labelMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf("Highlight" to "형광 강조","Underline" to "밑줄","StrikeOut" to "취소선").forEach {(value,title)->FilterChip(kind==value,{kind=value},label={Text(title)})}
            }
        } else if(entry==null) {
            if(entries==null && problem==null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if(entries?.isEmpty()==true) Text("텍스트 주석·메모가 없습니다.")
            entries?.let {rows -> LazyColumn(Modifier.heightIn(max=280.dp)) {items(rows,key={it.index}) {ann -> TextButton({entry=ann;comment=ann.text;color=ann.color},modifier=Modifier.fillMaxWidth()) {Text("${ann.kind} · "+ann.text.take(90))}}}}
        } else {
            OutlinedTextField(comment,{comment=it.take(10000)},label={Text("주석 내용")},modifier=Modifier.fillMaxWidth())
            TextButton({val ann=entry!!;vm.change(DocumentChange("pdf.annotationDelete",page.toString(),mapOf("id" to ann.index.toString(),"expected" to ann.expected)));close()}) {Text("이 주석 삭제")}
            TextButton({entry=null}) {Text("목록으로")}
        }
        if(!manage || entry!=null) Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            listOf("FFE36B" to "노랑","315FBB" to "파랑","BC4949" to "빨강","54A869" to "초록").forEach {(hex,label)->
                FilterChip(color==hex,{color=hex},label={Text(label,color=Color(android.graphics.Color.parseColor("#$hex")))})
            }
        }
    }},confirmButton={TextButton({
        if(manage) {
            val ann=entry!!;vm.change(DocumentChange("pdf.annotationEdit",page.toString(),mapOf("id" to ann.index.toString(),"expected" to ann.expected,"text" to comment,"color" to color)))
        } else vm.change(DocumentChange("pdf.markupAdd",page.toString(),mapOf("first" to first.toString(),"last" to last.toString(),"expected" to text.text.substring(first,last),"kind" to kind,"color" to color)))
        close()
    },enabled=if(manage) entry!=null else first<last && last-first<=10000) {Text("적용")}},dismissButton={TextButton(close) {Text("닫기")}})
}
