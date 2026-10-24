package kr.documentstudio.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.documentstudio.DocumentActions
import kr.documentstudio.core.*
import kr.documentstudio.data.Session

private val tableAddress=Regex("^표 (\\d+) · (\\d+)행 (\\d+)열")
private data class PaperBlock(val fields: List<Field>,val table: Int?=null)
private fun blocks(fields: List<Field>): List<PaperBlock> {
    val result=mutableListOf<PaperBlock>()
    var group=mutableListOf<Field>()
    var currentTable: Int?=null
    fun flush() {if(group.isNotEmpty()) {result+=PaperBlock(group.toList(),currentTable);group=mutableListOf()}}
    for(f in fields) {
        val table=tableAddress.find(f.label)?.groupValues?.get(1)?.toIntOrNull()
        if(table!=currentTable || (table==null && group.lastOrNull()?.paragraph!=f.paragraph)) flush()
        currentTable=table;group+=f
    }
    flush()
    return result
}

/** A reflowed editing paper. Original pagination remains the Office engine's job. */
@Composable
fun DocumentPaper(s: Session,sec: Section,vm: DocumentActions,busy: Boolean,readOnly: Boolean=false,select: (PaperSelection)->Unit) {
    val content=remember(sec) {blocks(sec.fields)}
    Box(Modifier.fillMaxSize().padding(horizontal=12.dp,vertical=16.dp),contentAlignment=Alignment.TopCenter) {
        Surface(Modifier.widthIn(max=816.dp).fillMaxSize(),color=Color.White,shadowElevation=3.dp) {
            LazyColumn(Modifier.fillMaxSize().testTag("document-paper"),contentPadding=PaddingValues(horizontal=28.dp,vertical=36.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                if(sec.fields.isEmpty()) item {Text("삽입에서 문단을 추가해 작성을 시작하세요.",color=Color.Gray)}
                items(content,key={it.fields.first().id}) {block ->
                    if(block.table!=null) PaperTable(s,block,vm,busy,readOnly,select)
                    else ParagraphField(s,block.fields,vm,busy,readOnly,select)
                }
            }
        }
    }
}

@Composable
private fun PaperTable(s: Session,block: PaperBlock,vm: DocumentActions,busy: Boolean,readOnly: Boolean,select: (PaperSelection)->Unit) {
    val cells=remember(block) {block.fields.groupBy {f->val match=tableAddress.find(f.label)!!;match.groupValues[2].toInt() to match.groupValues[3].toInt()}}
    val rows=remember(cells) {cells.keys.map {it.first}.distinct().sorted()}
    val columns=remember(cells) {cells.keys.map {it.second}.distinct().sorted()}
    Column(Modifier.padding(vertical=12.dp)) {
        Text("표 ${block.table}",style=MaterialTheme.typography.labelMedium,color=Color(0xFF68758A),modifier=Modifier.padding(bottom=6.dp))
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            LazyColumn(Modifier.width((columns.size*144).coerceAtLeast(144).dp).heightIn(max=400.dp).height((rows.size*76).coerceIn(76,400).dp).testTag("table-grid-${block.table}")) {
                items(rows,key={it}) {row->Row {
                    columns.forEach {column->
                        Column(Modifier.width(144.dp).heightIn(min=76.dp).border(0.5.dp,Color(0xFFCDD5E2)).padding(8.dp)) {
                            cells[row to column]?.groupBy {it.paragraph}?.values?.forEach {fields->ParagraphField(s,fields,vm,busy,readOnly,select)}
                        }
                    }
                }}
            }
        }
    }
}

data class PaperSelection(val paragraph: String,val start: Int,val end: Int) {
    val length get()=kotlin.math.abs(end-start)
}

private fun runStyle(s: Session,f: Field): SpanStyle {
    val props=f.styles+(s.edits.styles[f.id] ?: emptyMap())
    val size=((props["size"]?.toFloatOrNull()?.takeIf {it.isFinite()} ?: 12f)*1.33f).coerceIn(1.33f,266f)
    val ink=props["color"]?.let {runCatching {Color(AndroidColor.parseColor("#"+it.removePrefix("#")))}.getOrNull()} ?: Color(0xFF1E2633)
    val decoration=listOfNotNull(if(props["underline"]=="true") TextDecoration.Underline else null,if(props["strike"]=="true") TextDecoration.LineThrough else null)
    return SpanStyle(fontSize=size.sp,fontWeight=if(s.edits.bold[f.id] ?: props["bold"]?.toBoolean() ?: f.bold) FontWeight.Bold else FontWeight.Normal,
        fontStyle=if(props["italic"]=="true") FontStyle.Italic else FontStyle.Normal,color=ink,
        background=when(props["highlight"]) {"yellow"->Color(0xFFFFF59D);"green"->Color(0xFFA5D6A7);"cyan"->Color.Cyan;"magenta"->Color.Magenta;else->Color.Unspecified},
        textDecoration=if(decoration.isEmpty()) TextDecoration.None else TextDecoration.combine(decoration))
}

@Composable
private fun ParagraphField(s: Session,fields: List<Field>,vm: DocumentActions,busy: Boolean,readOnly: Boolean,select: (PaperSelection)->Unit) {
    val paragraph=fields.first().paragraph
    val rich=buildAnnotatedString {fields.forEach {f->val start=length;append(s.edits.values[f.id] ?: f.text);addStyle(runStyle(s,f),start,length)}}
    // Keep the IME composition and selection while incoming run styles change.
    var input by rememberSaveable(paragraph,stateSaver=TextFieldValue.Saver) {mutableStateOf(TextFieldValue(rich))}
    val value=if(input.text==rich.text) input.copy(annotatedString=rich) else TextFieldValue(rich,TextRange(input.selection.start.coerceIn(0,rich.length),input.selection.end.coerceIn(0,rich.length)))
    val props=fields.first().styles+(s.edits.styles[fields.first().id] ?: emptyMap())
    val maxSize=fields.maxOf {f->(((s.edits.styles[f.id]?.get("size") ?: f.styles["size"])?.toFloatOrNull()?.takeIf {it.isFinite()} ?: 12f)*1.33f).coerceIn(1.33f,266f)}
    val spacing=(props["lineSpacing"]?.toFloatOrNull()?.takeIf {it.isFinite()} ?: 1.6f).coerceIn(0.5f,5f)
    val style=LocalTextStyle.current.copy(color=Color(0xFF1E2633),fontSize=16.sp,lineHeight=(maxSize*spacing).sp,
        textAlign=when(props["align"]) {"center"->TextAlign.Center;"right"->TextAlign.Right;"both"->TextAlign.Justify;else->TextAlign.Start})
    if(readOnly) SelectionContainer {Text(rich,Modifier.fillMaxWidth().heightIn(min=32.dp),style=style)}
    else BasicTextField(value,{next->
        input=next
        if(next.text!=rich.text) vm.paragraph(fields,next.text)
        select(PaperSelection(paragraph,next.selection.start,next.selection.end))
    },readOnly=busy,modifier=Modifier.fillMaxWidth().heightIn(min=32.dp).onFocusChanged {
        if(it.isFocused) select(PaperSelection(paragraph,value.selection.start,value.selection.end))
    }.semantics {contentDescription=fields.first().label}.testTag("body-field-${fields.first().id}"),textStyle=style,
        cursorBrush=SolidColor(MaterialTheme.colorScheme.primary),decorationBox={inner->Box(Modifier.fillMaxWidth().padding(vertical=3.dp)) {
            if(rich.isEmpty()) Text("여기에 입력하세요",color=Color(0xFF9CA6B5),style=MaterialTheme.typography.bodyMedium)
            inner()
        }})
}
