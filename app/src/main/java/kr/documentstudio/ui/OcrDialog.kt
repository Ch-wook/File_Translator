package kr.documentstudio.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kr.documentstudio.DocumentActions
import kr.documentstudio.core.*
import kr.documentstudio.data.*

@Composable
fun OcrDialog(s: Session,section: Section?,page: Int,vm: DocumentActions,onClose: ()->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var korean by remember {mutableStateOf(true)};var includeBody by remember {mutableStateOf(true)}
    var first by remember {mutableStateOf((page+1).toString())};var last by remember {mutableStateOf((page+1).toString())}
    var working by remember {mutableStateOf(false)};var progress by remember {mutableStateOf("")};var error by remember {mutableStateOf<String?>(null)}
    var results by remember {mutableStateOf<List<OcrPage>>(emptyList())};var sourceBase by remember {mutableStateOf<String?>(null)}
    var selected by remember {mutableIntStateOf(0)};val corrections=remember {mutableStateMapOf<Int,String>()}
    fun start(block: suspend ()->List<OcrPage>) {
        working=true;error=null;results=emptyList();corrections.clear();selected=0
        scope.launch {try {results=block()} catch(e: CancellationException) {throw e} catch(e: Exception) {error=e.message ?: "문자 인식 실패"} finally {working=false}}
    }
    val image=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->if(uri!=null) {sourceBase=null;start {withContext(Dispatchers.IO) {listOf(OcrPage(0,OcrEngine.recognizeOwned(OcrEngine.image(context,uri),korean)))}}}}
    fun edited(): List<OcrPage> = results.mapIndexed {i,result->
        val value=corrections[i] ?: result.text;val lines=value.replace("\r\n","\n").split('\n')
        require(lines.size==result.lines.size) {"PDF 좌표를 유지하려면 결과의 줄 수를 유지하세요. 자유로운 수정은 TXT로 만들기를 이용하세요."}
        result.copy(lines=result.lines.mapIndexed {n,line->line.copy(text=lines[n])}.filter {it.text.isNotBlank()})
    }
    AlertDialog(onDismissRequest={if(!working) onClose()},title={Text("오프라인 문자 인식")},text={Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState())) {
        Text("한국어·영어 번들 모델 · 인식 결과를 확인하고 적용하세요. 손글씨는 이미지 OCR로 처리해 정확도가 낮을 수 있습니다.",style=MaterialTheme.typography.bodySmall)
        Row {FilterChip(korean,{korean=true},label={Text("한국어+영어")},enabled=!working);Spacer(Modifier.width(8.dp));FilterChip(!korean,{korean=false},label={Text("영어")},enabled=!working)}
        if(s.pdf!=null) {
            Row {OutlinedTextField(first,{first=it},label={Text("시작 페이지")},singleLine=true,modifier=Modifier.weight(1f),enabled=!working);OutlinedTextField(last,{last=it},label={Text("끝 페이지 · 최대 20장")},singleLine=true,modifier=Modifier.weight(1f),enabled=!working)}
            Row {Checkbox(includeBody,{includeBody=it},enabled=!working);Text("PDF 본문과 표시된 필기 함께 인식",Modifier.padding(top=12.dp))}
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton({sourceBase=if(s.pdf!=null) s.base.name else null;start {
                val range=if(s.pdf!=null) {val a=first.toInt()-1;val b=last.toInt()-1;require(a>=0 && b>=a && b<s.pdf.pages && b-a<20) {"PDF 페이지 범위는 최대 20장입니다."};a..b} else 0..0
                val output=mutableListOf<OcrPage>()
                for(index in range) {currentCoroutineContext().ensureActive();progress="${output.size+1} / ${range.count()} 페이지"
                    output+=withContext(Dispatchers.IO) {
                        val bitmap=if(s.pdf!=null && includeBody) PdfEngine.render(s.base,index,1600,2200,s.pdf.annotatedPages) else Bitmap.createBitmap(1600,2264,Bitmap.Config.ARGB_8888).apply {eraseColor(AndroidColor.WHITE)}
                        try {val anchor=if(s.pdf!=null) "pdf:$index" else section?.id ?: "text";val canvas=Canvas(bitmap);InkLayers.ordered(s.edits).filter {it.anchor==anchor}.forEach {drawStroke(canvas,it,bitmap.width.toFloat(),bitmap.height.toFloat())}}
                        catch(e: Exception) {bitmap.recycle();throw e}
                        OcrPage(index,OcrEngine.recognizeOwned(bitmap,korean))
                    }
                };output
            }},enabled=!working) {Text(if(s.pdf!=null) "PDF 범위 인식" else "현재 필기 인식")}
            TextButton({image.launch(arrayOf("image/*"))},enabled=!working) {Text("이미지 선택")}
        }
        if(working) {LinearProgressIndicator(Modifier.fillMaxWidth());Text(progress)}
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        if(results.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {results.forEachIndexed {i,r->FilterChip(selected==i,{selected=i},label={Text("${r.index+1}쪽 결과")})}}
            val result=results[selected]
            if(result.lines.isEmpty()) Text("인식한 문자가 없습니다. 선명한 이미지나 다른 언어 설정을 사용하세요.")
            else OutlinedTextField(corrections[selected] ?: result.text,{if(it.length<=50000) corrections[selected]=it},label={Text("인식 결과 · 수정 가능")},modifier=Modifier.fillMaxWidth().heightIn(min=140.dp),enabled=!working)
            TextButton({vm.textFromOcr(results.mapIndexed {i,r->corrections[i] ?: r.text}.joinToString("\n\n"));onClose()},enabled=!working && results.any {it.lines.isNotEmpty()}) {Text("TXT 문서로 만들기")}
            if(sourceBase!=null) TextButton({runCatching {edited().filter {it.lines.isNotEmpty()}.also {require(it.isNotEmpty())}.let {pages->vm.change(DocumentChange("pdf.ocrPages",page.toString(),mapOf("base" to sourceBase!!,"pages" to OcrEngine.json(pages))))}}.onSuccess {onClose()}.onFailure {error=it.message}},enabled=!working && results.any {it.lines.isNotEmpty()}) {Text("PDF에 검색 텍스트 추가")}
        }
    }},confirmButton={TextButton(onClose) {Text(if(working) "취소" else "닫기")}})
}
