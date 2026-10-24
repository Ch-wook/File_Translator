package kr.documentstudio.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.*
import kr.documentstudio.data.PdfEngine
import kr.documentstudio.data.PdfBodyText
import kr.documentstudio.data.PdfBodyRun
import kr.documentstudio.DocumentActions
import kr.documentstudio.core.*
import kr.documentstudio.data.Session

private data class Action(val label: String,val type: String,val inputs: List<Triple<String,String,String>> = emptyList())
@Composable
fun DocumentTools(s: Session,sec: Section?,vm: DocumentActions,busy: Boolean,page: Int=0,navigate: (Int)->Unit = {},category: String="홈",onOcr: ()->Unit={}) {
    if(s.format==Format.HWP && category in listOf("홈","삽입")) {
        var confirm by remember(s.id) {mutableStateOf(false)}
        OutlinedButton({confirm=true},enabled=!busy,modifier=Modifier.padding(horizontal=12.dp)) {Text("HWPX로 변환")}
        if(confirm) AlertDialog(onDismissRequest={confirm=false},title={Text("HWPX로 변환")},text={Text("현재 편집 내용을 새 HWPX 작업으로 변환합니다. 기존 HWP와 필기를 보존합니다.\n\n지원하지 않는 필드·그리기·OLE/차트·외부 연결 자원은 변환을 중단합니다. 글꼴과 쪽 배치의 시각적 동일성은 보장하지 않습니다. 결과의 점검 보고서를 확인하고 다른 이름으로 저장하세요.")},confirmButton={TextButton({confirm=false;vm.convertHangul()}) {Text("새 작업으로 변환")}},dismissButton={TextButton({confirm=false}) {Text("취소")}})
    }
    if(s.format==Format.HWPX && category=="홈") {
        val report by produceState<HangulConversionReport?>(null,s.id) {value=runCatching {vm.repository.conversionReport(s)}.getOrElse {vm.message(it.message);null}}
        var show by remember(s.id) {mutableStateOf(false)}
        report?.let {r ->
            TextButton({show=true},enabled=!busy,modifier=Modifier.padding(horizontal=12.dp)) {Text("변환 시점 점검 결과")}
            if(show) AlertDialog(onDismissRequest={show=false},title={Text("변환 시점 점검 결과")},text={SelectionContainer {Text(r.description(),modifier=Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState()))}},confirmButton={TextButton({show=false}) {Text("닫기")}})
        }
    }
    val actions=when(s.format) {
        Format.DOCX -> listOf(
            Action("그림 삽입","word.image"),
            Action("링크 삽입","word.hyperlink",listOf(Triple("text","표시할 텍스트","링크"),Triple("url","http / https / mailto 주소","https://example.com"))),
            Action("머리글 문단 추가 · 마지막 구역","word.header",listOf(Triple("text","머리글 문단","머리글"))),
            Action("꼬리글·쪽 번호 추가 · 마지막 구역","word.footer",listOf(Triple("text","꼬리글 문단",""),Triple("pageNumber","쪽 번호 필드 추가 (true / false)","true"))),
            Action("표 행 삽입","word.tableAddRow",listOf(Triple("table","표 번호 (1부터)","1"),Triple("row","삽입 행 위치 (1부터)","2"))),
            Action("표 행 삭제","word.tableDeleteRow",listOf(Triple("table","표 번호","1"),Triple("row","삭제 행","2"))),
            Action("표 열 삽입","word.tableAddColumn",listOf(Triple("table","표 번호","1"),Triple("column","삽입 열 위치 (1부터)","2"))),
            Action("표 열 삭제","word.tableDeleteColumn",listOf(Triple("table","표 번호","1"),Triple("column","삭제 열","2"))),
            Action("표 셀 병합","word.tableMerge",listOf(Triple("table","표 번호","1"),Triple("row","시작 행","1"),Triple("column","시작 열","1"),Triple("lastRow","끝 행","2"),Triple("lastColumn","끝 열","2"))),
            Action("표 병합 셀 분할","word.tableSplit",listOf(Triple("table","표 번호","1"),Triple("row","병합 시작 행","1"),Triple("column","병합 시작 열","1"))),
            Action("표 셀 배경·테두리","word.tableCellStyle",listOf(Triple("table","표 번호","1"),Triple("row","행","1"),Triple("column","열","1"),Triple("background","배경색 RRGGBB","EEF3EF"),Triple("border","테두리색 RRGGBB","315D54"),Triple("vertical","세로 정렬 top / center / bottom","center"))),
            Action("문단 추가","word.paragraph",listOf(Triple("text","문단 내용","새 문단"))),
            Action("표 삽입","word.table",listOf(Triple("rows","행 수","3"),Triple("columns","열 수","3"))),
            Action("페이지 나누기","word.break"),
            Action("A4 용지·여백","word.layout",listOf(Triple("landscape","가로 방향 (true / false)","false"),Triple("marginMm","여백 mm","20"),Triple("columns","단 수 (1~4)","1"))))
        Format.HWPX -> listOf(
            Action("다단 설정","hwpx.columns",listOf(Triple("columns","단 수 (1~4)","2"),Triple("gapMm","단 간격 mm (0~30)","8"))),
            Action("표 가로 정렬","hwpx.tableAlign",listOf(Triple("table","표 번호","1"),Triple("align","LEFT / CENTER / RIGHT","CENTER"))),
            Action("표 삽입","hwpx.table",listOf(Triple("rows","행 수 (1~50)","3"),Triple("columns","열 수 (1~20)","3"),Triple("widthMm","표 너비 mm","160"))),
            Action("표 행 삽입","hwpx.tableAddRow",listOf(Triple("table","표 번호 (1부터)","1"),Triple("row","삽입 행 위치 (1부터)","2"))),
            Action("표 행 삭제","hwpx.tableDeleteRow",listOf(Triple("table","표 번호","1"),Triple("row","삭제 행","2"))),
            Action("표 열 삽입","hwpx.tableAddColumn",listOf(Triple("table","표 번호","1"),Triple("column","삽입 열 위치 (1부터)","2"))),
            Action("표 열 삭제","hwpx.tableDeleteColumn",listOf(Triple("table","표 번호","1"),Triple("column","삭제 열","2"))),
            Action("표 셀 병합 · 내용 유지","hwpx.tableMerge",listOf(Triple("table","표 번호","1"),Triple("row","시작 행","1"),Triple("column","시작 열","1"),Triple("lastRow","끝 행","2"),Triple("lastColumn","끝 열","2"))),
            Action("표 병합 셀 분할","hwpx.tableSplit",listOf(Triple("table","표 번호","1"),Triple("row","병합 시작 행","1"),Triple("column","병합 시작 열","1"))),
            Action("표 셀 배경·테두리","hwpx.tableCellStyle",listOf(Triple("table","표 번호","1"),Triple("row","행","1"),Triple("column","열","1"),Triple("background","배경색 RRGGBB","EEF3EF"),Triple("border","테두리색 RRGGBB","315D54"),Triple("vertical","세로 정렬 TOP / CENTER / BOTTOM","CENTER"))),
            Action("표 크기 · 균등 배분","hwpx.tableSize",listOf(Triple("table","표 번호","1"),Triple("widthMm","표 너비 mm","160"),Triple("heightMm","표 높이 mm","30"))),
            Action("문단 추가","hwpx.paragraph",listOf(Triple("text","문단 내용","새 문단"))),Action("쪽 나누기","hwpx.break"),Action("A4 용지·여백","hwpx.layout",listOf(Triple("landscape","가로 방향 (true / false)","false"),Triple("marginMm","여백 mm","20"))))
        Format.XLSX -> listOf(
            Action("범위 테두리","sheet.borders",listOf(Triple("range","범위","A1:B5"),Triple("style","thin / medium / dashed / dotted / double / none","thin"),Triple("color","색 RRGGBB","315D54"))),
            Action("조건부 서식","sheet.conditional",listOf(Triple("range","범위","B2:B20"),Triple("kind","greaterThan / lessThan / equal / duplicateValues / clear","greaterThan"),Triple("value","비교 숫자 (중복·지우기는 무시)","100"),Triple("color","배경색 RRGGBB","FFF2CC"))),
            Action("드롭다운 목록","sheet.validation",listOf(Triple("range","범위","C2:C20"),Triple("items","고유한 항목 (|로 구분, 쉼표·따옴표 제외)","대기|진행|완료"))),
            Action("데이터 표 만들기","sheet.table",listOf(Triple("range","범위 (첫 행에 고유한 열 이름 필요)","A1:C5"))),
            Action("범위 복사 · 셀 서식 포함","sheet.copyRange",listOf(Triple("source","원본 범위","A1:B5"),Triple("destination","대상 시작 셀","D1"))),
            Action("자동 채우기","sheet.fill",listOf(Triple("source","원본 범위 (두 숫자는 등차수열)","A1:A2"),Triple("destination","채울 전체 범위 (원본 포함)","A1:A20"))),
            Action("데이터 정렬","sheet.sort",listOf(Triple("range","정렬 범위","A1:D20"),Triple("column","기준 열 번호 (A=1)","1"),Triple("header","첫 행이 제목 (true / false)","true"),Triple("descending","내림차순 (true / false)","false"))),
            Action("행 삽입","sheet.insertRows",listOf(Triple("index","삽입 위치 (1부터)","2"),Triple("count","행 수","1"))),
            Action("행 삭제","sheet.deleteRows",listOf(Triple("index","삭제 시작 행","2"),Triple("count","행 수","1"))),
            Action("열 삽입","sheet.insertColumns",listOf(Triple("index","삽입 열 번호 (A=1)","2"),Triple("count","열 수","1"))),
            Action("열 삭제","sheet.deleteColumns",listOf(Triple("index","삭제 시작 열 번호","2"),Triple("count","열 수","1"))),
            Action("시트 추가","sheet.add",listOf(Triple("name","새 시트 이름","시트${s.sections.size+1}"))),
            Action("시트 이름","sheet.rename",listOf(Triple("name","시트 이름",sec?.title ?: "시트"))),
            Action("시트 복제","sheet.duplicate",listOf(Triple("name","복제본 이름",(sec?.title ?: "시트").take(25)+" 복사"))),
            Action("현재 시트 삭제","sheet.delete"),
            Action("셀 병합","sheet.merge",listOf(Triple("range","범위 (다른 셀은 비어 있어야 함)","A1:B1"))),
            Action("병합 해제","sheet.unmerge",listOf(Triple("range","병합 범위","A1:B1"))),
            Action("열 너비","sheet.width",listOf(Triple("column","열 번호 (A=1)","1"),Triple("width","너비","20"))),
            Action("행 높이","sheet.height",listOf(Triple("row","행 번호","1"),Triple("height","높이 pt","28"))),
            Action("틀 고정","sheet.freeze",listOf(Triple("rows","고정할 행 수","1"),Triple("columns","고정할 열 수","0"))),
            Action("자동 필터 범위","sheet.filter",listOf(Triple("range","필터 범위","A1:D10"))))
        Format.PPTX -> listOf(
            Action("그림 삽입","slide.image"),
            Action("개체 삭제","slide.deleteObject",listOf(Triple("shape","직접 개체 순번 (0부터)","0"))),
            Action("개체 계층 순서","slide.objectOrder",listOf(Triple("shape","직접 개체 순번 (0부터)","0"),Triple("index","새 순번 (클수록 앞으로)","0"))),
            Action("빈 슬라이드 추가","slide.add"),Action("현재 슬라이드 복제","slide.duplicate"),Action("현재 슬라이드 삭제","slide.delete"),
            Action("슬라이드 순서","slide.move",listOf(Triple("index","새 위치 (첫 번째=0)","0"))),
            Action("텍스트 상자 삽입","slide.text",listOf(Triple("text","텍스트","새 텍스트 상자"),Triple("x","왼쪽 cm","1"),Triple("y","위쪽 cm","1"),Triple("width","너비 cm","12"),Triple("height","높이 cm","3"))),
            Action("사각형 삽입","slide.rectangle",listOf(Triple("color","색상 RRGGBB","315D54"),Triple("x","왼쪽 cm","1"),Triple("y","위쪽 cm","5"),Triple("width","너비 cm","5"),Triple("height","높이 cm","3"))),
            Action("개체 이동·크기·회전","slide.transform",listOf(Triple("shape","도형 순번 (0부터)","0"),Triple("x","왼쪽 cm","1"),Triple("y","위쪽 cm","1"),Triple("width","너비 cm","12"),Triple("height","높이 cm","3"),Triple("rotation","회전 각도","0"))))
        Format.PDF -> listOf(Action("페이지 추가","pdf.add"),Action("페이지 삭제","pdf.delete"),Action("90° 회전","pdf.rotate"),Action("페이지 순서","pdf.move",listOf(Triple("index","새 위치 (첫 번째=0)","0"))),Action("페이지 추출·분할","pdf.extract",listOf(Triple("first","시작 페이지 (1부터)","1"),Triple("last","끝 페이지",s.pdf!!.pages.toString()))),Action("PDF 병합","pdf.merge"),Action("메모 주석","pdf.note",listOf(Triple("text","메모","메모"),Triple("x","왼쪽 위치 %","10"),Triple("y","위쪽 위치 %","10"))),Action("본문 텍스트 추가","pdf.text",listOf(Triple("text","추가할 텍스트 (기존 텍스트 수정 아님)","새 텍스트"),Triple("x","왼쪽 위치 %","10"),Triple("y","위쪽 위치 %","10"),Triple("size","크기 pt","14"))),Action("이미지 삽입 (10%, 10%, 폭 40%)","pdf.image"))
        else -> emptyList()
    }
    if(actions.isEmpty()) return
    var menu by remember {mutableStateOf(false)};var selected by remember {mutableStateOf<Action?>(null)}
    val merge=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->if(uri!=null) vm.change(DocumentChange("pdf.merge",page.toString(),mapOf("uri" to uri.toString())))}
    var imageAction by remember {mutableStateOf("pdf.image")}
    val image=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->if(uri!=null) vm.change(DocumentChange(imageAction,if(s.format==Format.PDF) page.toString() else sec?.id ?: "",mapOf("uri" to uri.toString())))}
    val choose: (Action)->Unit = {a->menu=false;when(a.type) {"pdf.merge"->merge.launch(arrayOf("application/pdf"));"pdf.image","word.image","slide.image"->{imageAction=a.type;image.launch(arrayOf("image/*"))};else->selected=a}}
    val quick=actions.filter {a->when(category) {
        "삽입"->a.type.substringAfter('.') in setOf("image","hyperlink","table","paragraph","text","rectangle")
        "레이아웃"->a.type.substringAfter('.') in setOf("layout","columns","break","header","footer")
        "데이터"->a.type.substringAfter('.') in setOf("sort","filter","validation","table","conditional")
        "슬라이드"->a.type.substringAfter('.') in setOf("add","duplicate","delete","move")
        "페이지"->a.type.substringAfter('.') in setOf("add","delete","rotate","move","extract","merge")
        "주석"->a.type=="pdf.note"
        "편집"->a.type in setOf("pdf.text","pdf.image")
        "홈"->a.type.substringAfter('.') in setOf("paragraph","merge","table","borders")
        else->false
    }}.take(2)
    if(category!="보기" && category!="수식") Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=8.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
        OutlinedButton(onClick={menu=true},enabled=!busy) {Text("삽입·문서 도구")}
        if(category=="주석") TextButton(onOcr,enabled=!busy) {Text("문자 인식")}
        if(!menu) quick.forEach {a->TextButton({choose(a)},enabled=!busy) {Text(a.label)}}
    }
    if(menu) {
        var query by remember {mutableStateOf("")}
        AlertDialog(onDismissRequest={menu=false},title={Text("문서 도구")},text={Column {
            OutlinedTextField(query,{query=it},label={Text("도구 이름 검색")},singleLine=true,modifier=Modifier.fillMaxWidth())
            Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState())) {
                val found=actions.filter {it.label.contains(query,true)}
                if(found.isEmpty()) Text("일치하는 도구가 없습니다.",modifier=Modifier.padding(12.dp))
                found.forEach {a->TextButton({choose(a)},modifier=Modifier.fillMaxWidth()) {Text(a.label,modifier=Modifier.fillMaxWidth())}}
            }
        }},confirmButton={TextButton({menu=false}) {Text("닫기")}})
    }
    selected?.let {a ->
        val values=remember(a) {mutableStateMapOf<String,String>().apply {a.inputs.forEach {put(it.first,it.third)}}}
        AlertDialog(onDismissRequest={selected=null},title={Text(a.label)},text={Column(Modifier.verticalScroll(rememberScrollState())) {
            if(a.inputs.isEmpty()) Text(if(a.type.endsWith("delete")) "삭제한 내용과 필기는 실행 취소로 복원할 수 있습니다." else "현재 문서에 추가합니다. 실행 취소와 복구 저장을 지원합니다.")
            a.inputs.forEach {(key,label,_)->CommandInput(key,label,values[key] ?: "") {values[key]=it}}
        }},confirmButton={TextButton(onClick={vm.change(DocumentChange(a.type,if(s.format==Format.PDF) page.toString() else sec?.id ?: "",values.toMap()));selected=null}) {Text("적용")}},dismissButton={TextButton(onClick={selected=null}) {Text("취소")}})
    }
    if(s.format==Format.PDF) PdfTextAndForms(s,page,vm,busy,navigate,category)
}

@Composable
private fun PdfTextAndForms(s: Session,page: Int,vm: DocumentActions,busy: Boolean,navigate: (Int)->Unit,category: String) {
    var bodyEditor by remember {mutableStateOf(false)}
    var imageEditor by remember {mutableStateOf(false)}
    var forms by remember {mutableStateOf(false)};var text by remember {mutableStateOf(false)};var annotations by remember {mutableStateOf(false)};var search by remember {mutableStateOf(false)}
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=12.dp)) {
        if(category in listOf("보기","페이지")) PdfNavigationActions(s,page,vm,busy,navigate)


        if(category=="주석") TextButton({text=true},enabled=!busy) {Text("텍스트 선택")};if(category=="주석") TextButton({annotations=true},enabled=!busy) {Text("주석 관리")};if(category=="보기") TextButton({search=true},enabled=!busy) {Text("PDF 검색")}
        if(category=="편집") TextButton({forms=true},enabled=!busy && s.pdf!!.forms.isNotEmpty()) {Text("양식 (${s.pdf!!.forms.size})")}
    }
    if(forms) AlertDialog(onDismissRequest={forms=false},title={Text("PDF 양식")},text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState())) {
        s.pdf!!.forms.forEach {f ->
            val id="pdf:form#"+f.name;val value=s.edits.values[id] ?: f.value;val enabled=!f.readOnly && f.kind!="unsupported"
            if(f.kind=="checkbox") Row {Checkbox(value!="Off" && value.isNotBlank(),{vm.field(id,if(it) f.options.firstOrNull() ?: "Yes" else "Off")},enabled=enabled);Text(f.name,modifier=Modifier.padding(top=12.dp))}
            else {OutlinedTextField(value,{vm.field(id,it)},label={Text(f.name)},enabled=enabled,modifier=Modifier.fillMaxWidth().padding(vertical=4.dp));if(f.options.isNotEmpty()) Text("선택 값: "+f.options.joinToString(", "),style=MaterialTheme.typography.labelSmall)}
        }
    }},confirmButton={TextButton({forms=false}) {Text("완료")}})
    if(text || annotations) PdfMarkupTools(s,page,vm,annotations) {text=false;annotations=false}
    if(search) {
        var query by remember {mutableStateOf("")};var found by remember {mutableStateOf<List<Int>?>(null)};var working by remember {mutableStateOf(false)};val scope=rememberCoroutineScope()
        AlertDialog(onDismissRequest={search=false},title={Text("PDF 텍스트 검색")},text={Column {
            OutlinedTextField(query,{query=it;found=null},label={Text("찾을 문자열")},singleLine=true,enabled=!working)
            if(working) LinearProgressIndicator(Modifier.fillMaxWidth())
            found?.let {results ->Text("일치 페이지 ${results.size}개");Column(Modifier.heightIn(max=280.dp).verticalScroll(rememberScrollState())) {results.forEach {i->TextButton({navigate(i);search=false}) {Text("${i+1} 페이지로 이동")}}}}
        }},confirmButton={TextButton({working=true;scope.launch {try {found=withContext(Dispatchers.IO) {PdfEngine.search(s.base,query)}} catch(e: CancellationException) {throw e} catch(e: Exception) {vm.message(e.message)} finally {working=false}}},enabled=!working && query.isNotBlank()) {Text("검색")}},dismissButton={TextButton({search=false}) {Text("닫기")}})
    }
}

@Composable
fun StyleDialog(id: String,format: Format,existing: Map<String,String>,vm: DocumentActions,apply: ((Map<String,String>)->Unit)?=null,close: ()->Unit) {
    val pending=remember {mutableStateMapOf<String,String>()}
    var advanced by remember {mutableStateOf(false)}
    val spreadsheet=format==Format.XLSX
    val fields=listOf("font" to "글꼴 이름","size" to "크기 pt","color" to "글자 색 RRGGBB") + if(spreadsheet) listOf("background" to "배경색 RRGGBB","numberFormat" to "숫자 형식 (0.00, #,##0 등)","align" to "정렬 (left / center / right)") else if(format in listOf(Format.DOCX,Format.HWPX)) listOf("align" to "문단 정렬 (left / center / right / both)","lineSpacing" to "줄 간격 배수 (1~5)") + if(format==Format.DOCX) listOf("highlight" to "강조색 (yellow / green / none)") else emptyList() else emptyList()
    AlertDialog(onDismissRequest=close,title={Text(if(spreadsheet) "셀 서식" else "글자·문단 서식")},text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState())) {
        Text("변경한 항목만 적용합니다. 원본 문서 서식으로 저장합니다.")
        val booleans=if(spreadsheet || format==Format.PPTX) listOf("bold" to "굵게","italic" to "기울임") else (if(format==Format.HWPX) listOf("bold" to "굵게") else emptyList())+listOf("italic" to "기울임","underline" to "밑줄","strike" to "취소선")
        booleans.forEach {(key,label)->Row {Checkbox((pending[key] ?: existing[key])=="true",{pending[key]=it.toString()});Text(label,modifier=Modifier.padding(top=12.dp))}}
        val allFields=fields+if(format==Format.DOCX) listOf("position" to "위치 superscript / subscript / baseline","spacing" to "문자 간격 pt (-20~100)","scale" to "문자 가로 폭 % (20~400)","beforePt" to "문단 앞 간격 pt","afterPt" to "문단 뒤 간격 pt","indentMm" to "왼쪽 들여쓰기 mm","firstLineMm" to "첫 줄 mm (음수는 내어쓰기)") else if(format==Format.HWPX) listOf("tracking" to "자간 % (-50~50)","scale" to "장평 % (50~200)","offset" to "글자 위치 % (-100~100)","beforePt" to "문단 앞 간격 pt","afterPt" to "문단 뒤 간격 pt","indentMm" to "왼쪽 들여쓰기 mm","firstLineMm" to "첫 줄 mm (음수는 내어쓰기)","styleId" to "기존 스타일 ID","headingType" to "머리 NONE / NUMBER / BULLET","headingLevel" to "번호 수준 (1~7)","numberingId" to "기존 번호/글머리표 ID (비우면 생성)") else emptyList()
        allFields.filter {it.first in setOf("font","size","color","background","numberFormat","align","lineSpacing","highlight")}.forEach {(key,label)->CommandInput(key,label,pending[key] ?: existing[key] ?: "") {pending[key]=it}}
        TextButton({advanced=!advanced}) {Text(if(advanced) "세부 문단 설정 접기" else "세부 문단 설정")}
        if(advanced) allFields.filter {it.first !in setOf("font","size","color","background","numberFormat","align","lineSpacing","highlight")}.forEach {(key,label)->CommandInput(key,label,pending[key] ?: existing[key] ?: "") {pending[key]=it}}
    }},confirmButton={TextButton(onClick={val properties=pending.filterValues {it.isNotBlank()};if(apply!=null) apply(properties) else vm.style(id,properties);close()},enabled=pending.isNotEmpty()) {Text("적용")}},dismissButton={TextButton(onClick=close) {Text("취소")}})
}
