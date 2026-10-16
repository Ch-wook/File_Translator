package kr.documentstudio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kr.documentstudio.core.FormulaCatalog

@Composable
fun FormulaTools(input: String,enabled: Boolean,update: (String)->Unit) {
    var show by remember {mutableStateOf(false)}
    val suggestion=remember(input) {FormulaCatalog.suggestions(input).firstOrNull()}
    suggestion?.let {help -> TextButton({update(FormulaCatalog.complete(input,help.name))},enabled=enabled) {Text(help.name+"(")}}
    TextButton({show=true},enabled=enabled) {Text("함수")}
    if(show) {
        var query by remember {mutableStateOf("")}
        AlertDialog(onDismissRequest={show=false},title={Text("함수 검색")},text={Column {
            OutlinedTextField(query,{query=it},label={Text("함수 이름 또는 설명")},singleLine=true,modifier=Modifier.fillMaxWidth())
            val results=remember(query) {FormulaCatalog.search(query)}
            Text("입력 중인 함수 이름을 완성하거나 빈 셀에 삽입합니다.",style=MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.heightIn(max=330.dp)) {
                if(results.isEmpty()) item {Text("일치하는 함수가 없습니다.")}
                items(results,key={it.name}) {help ->
                    Column(Modifier.fillMaxWidth().padding(vertical=8.dp)) {
                        Text(help.signature,style=MaterialTheme.typography.titleSmall)
                        Text(help.description,style=MaterialTheme.typography.bodySmall)
                        Text(help.example+" → "+help.result,style=MaterialTheme.typography.bodySmall)
                        TextButton({update(FormulaCatalog.complete(input,help.name));show=false},enabled=FormulaCatalog.complete(input,help.name)!=input) {Text(help.name+" 삽입")}
                    }
                }
            }
        }},confirmButton={TextButton({show=false}) {Text("닫기")}})
    }
}
