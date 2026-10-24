package kr.documentstudio.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kr.documentstudio.DocumentActions
import kr.documentstudio.core.*
import kr.documentstudio.data.Session

@Composable
fun PaperTextTools(s: Session,sec: Section,selection: PaperSelection?,vm: DocumentActions,busy: Boolean) {
    val fields=sec.fields.filter {it.paragraph==selection?.paragraph}
    val enabled=!busy && fields.isNotEmpty() && s.format in listOf(Format.DOCX,Format.HWPX)
    val caret=minOf(selection?.start ?: 0,selection?.end ?: 0)
    val f=ParagraphEditing.fieldAt(fields,s.edits,caret)
    val props=f?.let {it.styles+(s.edits.styles[it.id] ?: emptyMap())} ?: emptyMap()
    fun format(properties: Map<String,String>) {
        if(!enabled || selection==null) return
        vm.change(DocumentChange("text.format",sec.id,mapOf("paragraph" to selection.paragraph.substringAfterLast(":p"),"start" to minOf(selection.start,selection.end).toString(),"end" to maxOf(selection.start,selection.end).toString())+properties))
    }
    var sizes by remember {mutableStateOf(false)}
    var details by remember {mutableStateOf(false)}
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
        Box {
            TextButton({sizes=true},enabled=enabled) {Text("${props["size"]?.toFloatOrNull()?.toInt() ?: 12} pt");Icon(Icons.Default.ArrowDropDown,null)}
            DropdownMenu(sizes,{sizes=false}) {listOf(9,10,11,12,14,16,18,20,24,28,32,36,48,72).forEach {n->DropdownMenuItem(text={Text("$n pt")},onClick={sizes=false;format(mapOf("size" to n.toString()))})}}
        }
        val bold=f?.let {s.edits.bold[it.id] ?: props["bold"]?.toBoolean() ?: it.bold} ?: false
        IconToggleButton(bold,{format(mapOf("bold" to it.toString()))},enabled=enabled) {Icon(Icons.Default.FormatBold,"굵게")}
        IconToggleButton(props["italic"]=="true",{format(mapOf("italic" to it.toString()))},enabled=enabled) {Icon(Icons.Default.FormatItalic,"기울임")}
        IconToggleButton(props["underline"]=="true",{format(mapOf("underline" to it.toString()))},enabled=enabled) {Icon(Icons.Default.FormatUnderlined,"밑줄")}
        IconButton({format(mapOf("highlight" to if(props["highlight"]=="yellow") "none" else "yellow"))},enabled=enabled && s.format==Format.DOCX) {Icon(Icons.Default.FormatColorFill,"텍스트 강조")}
        VerticalDivider(Modifier.height(24.dp).padding(horizontal=4.dp))
        listOf(Triple("left",Icons.Default.FormatAlignLeft,"왼쪽 정렬"),Triple("center",Icons.Default.FormatAlignCenter,"가운데 정렬"),Triple("right",Icons.Default.FormatAlignRight,"오른쪽 정렬")).forEach {(align,icon,label)->
            IconButton({f?.let {vm.style(it.id,mapOf("align" to align))}},enabled=enabled) {Icon(icon,label)}
        }
        TextButton({details=true},enabled=enabled) {Text("글자·문단 서식")}
        Text(if(selection==null) "본문을 눌러 편집" else if(selection.length>0) "${selection.length}자 선택" else "입력할 글자 서식",style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(horizontal=8.dp))
    }
    if(details && f!=null) StyleDialog(f.id,s.format,props,vm,apply={format(it)}) {details=false}
}
