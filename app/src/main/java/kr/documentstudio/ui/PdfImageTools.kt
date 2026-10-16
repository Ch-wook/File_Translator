package kr.documentstudio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.documentstudio.DocumentActions
import kr.documentstudio.core.DocumentChange
import kr.documentstudio.data.*

@Composable
fun PdfImageTools(s: Session,page: Int,vm: DocumentActions,close: ()->Unit) {
    var selected by remember(s.base,page) {mutableStateOf<PdfImagePlacement?>(null)}
    val placements by produceState<List<PdfImagePlacement>?>(null,s.base,page) {
        value=withContext(Dispatchers.IO) {runCatching {PdfImages.list(s.base,page)}.getOrElse {vm.message(it.message);emptyList()}}
    }
    val values=remember(selected) {mutableStateMapOf<String,String>().apply {selected?.let {p->putAll(mapOf("x" to p.x.toString(),"y" to p.y.toString(),"width" to p.width.toString(),"height" to p.height.toString()))}}}
    fun apply(type: String) {selected?.let {p->vm.change(DocumentChange(type,page.toString(),values.toMap()+mapOf("id" to p.id.toString(),"expected" to p.token)));close()}}
    AlertDialog(onDismissRequest=close,title={Text("기존 PDF 이미지")},text={Column(Modifier.heightIn(max=430.dp).verticalScroll(rememberScrollState())) {
        Text("페이지의 직접 이미지를 수정합니다. 중첩·클리핑·태그·스텐실 이미지는 제외합니다. 회전·기울기는 지정한 직사각형으로 맞춥니다. 삭제는 보안 지우기가 아닙니다.",style=MaterialTheme.typography.bodySmall)
        if(placements==null) LinearProgressIndicator(Modifier.fillMaxWidth())
        else if(placements!!.isEmpty()) Text("편집 가능한 이미지가 없습니다.")
        else if(selected==null) placements!!.forEach {p->TextButton({selected=p}) {Text("이미지 ${p.id+1} · ${p.name} · ${p.width.toInt()}% × ${p.height.toInt()}%")}}
        selected?.let {
            listOf("x" to "왼쪽 위치 %","y" to "위쪽 위치 %","width" to "너비 %","height" to "높이 %").forEach {(key,label)->OutlinedTextField(values[key] ?: "",{values[key]=it},label={Text(label)},singleLine=true,modifier=Modifier.fillMaxWidth())}
            TextButton({selected=null}) {Text("다른 이미지 선택")}
            TextButton({apply("pdf.deleteImage")}) {Text("선택 이미지 삭제")}
        }
    }},confirmButton={TextButton({apply("pdf.transformImage")},enabled=selected!=null) {Text("위치·크기 적용")}},dismissButton={TextButton(close) {Text("취소")}})
}
