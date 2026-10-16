package kr.documentstudio.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kr.documentstudio.DocumentActions
import kr.documentstudio.core.*
import kr.documentstudio.data.Session

@Composable
fun SheetRangeTools(s: Session,sec: Section,address: String,cells: Map<String,String>,vm: DocumentActions,busy: Boolean) {
    val context=LocalContext.current
    var range by remember(sec.id,address) {mutableStateOf(address)}
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(range,{range=it.uppercase()},label={Text("셀 범위")},singleLine=true,enabled=!busy,modifier=Modifier.width(165.dp))
        TextButton({try {
            val rect=CellRange.parse(range);require(rect.size<=10000) {"복사는 최대 10,000셀까지 지원합니다."}
            val text=TabularText.encode((rect.top..rect.bottom).map {r->(rect.left..rect.right).map {c->cells[columnName(c)+r] ?: ""}})
            require(text.length<=100000) {"클립보드 크기 제한을 초과했습니다. 범위를 줄여 주세요."}
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("문서 스튜디오 셀",text));vm.message("값·수식을 복사했습니다. 셀 서식 복사는 문서 도구를 사용하세요.")
        } catch(e: Exception) {vm.message(e.message)}},enabled=!busy) {Text("복사")}
        TextButton({try {
            val rect=CellRange.parse(range)
            val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text=clipboard.primaryClip?.takeIf {it.itemCount>0}?.getItemAt(0)?.text?.toString() ?: error("클립보드에 표 텍스트가 없습니다.")
            require(text.length<=2_000_000);vm.change(DocumentChange("sheet.paste",sec.id,mapOf("range" to columnName(rect.left)+rect.top,"text" to text)))
        } catch(e: Exception) {vm.message(e.message)}},enabled=!busy) {Text("붙여넣기")}
        TextButton({try {CellRange.parse(range);vm.change(DocumentChange("sheet.clearRange",sec.id,mapOf("range" to range)))} catch(e: Exception) {vm.message(e.message)}},enabled=!busy) {Text("내용 지우기")}
    }
}
