package kr.documentstudio.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** User-facing controls keep the engine's serialized arguments out of the workflow. */
@Composable
fun CommandInput(key: String,label: String,value: String,change: (String)->Unit) {
    if(key in setOf("landscape","pageNumber","header","descending")) {
        val title=when(key) {"landscape"->"가로 방향";"pageNumber"->"쪽 번호 포함";"header"->"첫 행은 제목";else->"내림차순"}
        Row(Modifier.fillMaxWidth().padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {Text(title,Modifier.weight(1f));Switch(value=="true",{change(it.toString())},modifier=Modifier.semantics {contentDescription=title})}
        return
    }
    val options=when(key) {
        "align"->if(value in listOf("LEFT","CENTER","RIGHT")) listOf("LEFT" to "왼쪽","CENTER" to "가운데","RIGHT" to "오른쪽") else listOf("left" to "왼쪽","center" to "가운데","right" to "오른쪽")+if(label.contains("both")) listOf("both" to "양쪽") else emptyList()
        "vertical"->if(value==value.uppercase()) listOf("TOP" to "위쪽","CENTER" to "가운데","BOTTOM" to "아래쪽") else listOf("top" to "위쪽","center" to "가운데","bottom" to "아래쪽")
        "kind"->listOf("greaterThan" to "보다 큼","lessThan" to "보다 작음","equal" to "같음","duplicateValues" to "중복 값","clear" to "규칙 지우기")
        "style"->listOf("thin" to "얇은 선","medium" to "굵은 선","dashed" to "파선","dotted" to "점선","double" to "이중선","none" to "없음")
        "position"->listOf("baseline" to "기본","superscript" to "위 첨자","subscript" to "아래 첨자")
        "headingType"->listOf("NONE" to "없음","NUMBER" to "번호","BULLET" to "글머리표")
        "highlight"->listOf("none" to "없음","yellow" to "노랑","green" to "초록")
        "font"->listOf("Noto Sans CJK KR" to "Noto Sans 한국어","맑은 고딕" to "맑은 고딕","나눔고딕" to "나눔고딕","Arial" to "Arial","Times New Roman" to "Times New Roman")
        "numberFormat"->listOf("0" to "정수","0.00" to "소수 2자리","#,##0" to "천 단위","0%" to "백분율","yyyy-mm-dd" to "날짜","@" to "텍스트")
        else->emptyList()
    }
    if(options.isNotEmpty()) {
        var custom by remember(key) {mutableStateOf(false)}
        var menu by remember {mutableStateOf(false)}
        Text(when(key) {"font"->"글꼴";"align"->"정렬";"position"->"글자 위치";"headingType"->"번호·글머리표";"highlight"->"강조색";else->label.substringBefore(" (").substringBefore(" TOP").substringBefore(" thin").substringBefore(" greater")},style=MaterialTheme.typography.labelMedium)
        Box {OutlinedButton({menu=true},modifier=Modifier.fillMaxWidth().semantics {contentDescription=when(key) {"numberFormat"->"숫자 형식 선택";"font"->"글꼴 선택";else->label.substringBefore(" (")+" 선택"}}) {Text(options.firstOrNull {it.first==value}?.second ?: value.ifBlank {"선택"})}
            DropdownMenu(menu,{menu=false}) {options.forEach {(v,title)->DropdownMenuItem(text={Text(title)},onClick={change(v);menu=false})}}
        }
        if(key in setOf("font","numberFormat")) {
            val title=if(key=="font") "글꼴" else "숫자 형식"
            TextButton({custom=!custom},modifier=Modifier.semantics {contentDescription=title+" 직접 입력"}) {Text(if(custom) "직접 입력 접기" else "직접 입력")}
            if(custom) OutlinedTextField(value,change,label={Text(if(key=="font") "글꼴 이름" else "사용자 지정 숫자 형식")},singleLine=true,modifier=Modifier.fillMaxWidth())
        }
        return
    }
    if(key in setOf("color","background","border")) {
        var custom by remember(key) {mutableStateOf(false)}
        Text(when(key) {"background"->"배경색";"border"->"테두리색";else->"색상"},style=MaterialTheme.typography.labelMedium,modifier=Modifier.padding(top=8.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            (listOf(value,"1E2633","FFFFFF","2858A5","217346","C45332","FFF2CC","EEF3EF")).distinct().forEach {hex ->
                val color=runCatching {Color(android.graphics.Color.parseColor("#"+hex.removePrefix("#")))}.getOrDefault(Color.White)
                Box(Modifier.size(40.dp).background(color,CircleShape).border(if(hex==value) 3.dp else 1.dp,if(hex==value) MaterialTheme.colorScheme.primary else Color.LightGray,CircleShape).clickable {change(hex)}.semantics {contentDescription="색상 $hex"})
            }
        }
        TextButton({custom=!custom}) {Text(if(custom) "색상 직접 입력 접기" else "다른 색상 직접 입력")}
        if(custom) OutlinedTextField(value,change,label={Text("사용자 지정 색상")},supportingText={Text("RRGGBB 색상 코드")},singleLine=true,modifier=Modifier.fillMaxWidth())
        return
    }
    val numeric=key in setOf("rows","columns","row","column","lastRow","lastColumn","count","index","shape","widthMm","heightMm","gapMm","marginMm","x","y","width","height","rotation","size","first","last")
    OutlinedTextField(value,change,label={Text(label)},modifier=Modifier.fillMaxWidth().padding(vertical=4.dp),singleLine=key!="text",keyboardOptions=KeyboardOptions(keyboardType=if(numeric) KeyboardType.Decimal else KeyboardType.Text))
}
