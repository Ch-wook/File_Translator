package kr.documentstudio.ui

import android.widget.EditText
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kr.documentstudio.core.MarkdownEditing

/** Shared compact chrome for the existing native PDF/TXT/Markdown workspaces. */
data class MobileDockItem(val title: String,val icon: ImageVector)
@Composable
fun MobileEditorDock(items: List<MobileDockItem>,selected: String?,enabled: Boolean,onSelect: (String)->Unit) {
    Surface(tonalElevation=1.dp) {
        Row(Modifier.fillMaxWidth().height(48.dp).testTag("mobile-editor-dock"),verticalAlignment=Alignment.CenterVertically) {
            items.forEach {item->
                TextButton({onSelect(item.title)},enabled=enabled,modifier=Modifier.weight(1f).height(44.dp).focusProperties {canFocus=false},
                    contentPadding=PaddingValues(2.dp),colors=ButtonDefaults.textButtonColors(contentColor=if(selected==item.title) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)) {
                    Icon(item.icon,null,Modifier.size(18.dp));Spacer(Modifier.width(3.dp));Text(item.title,style=MaterialTheme.typography.labelSmall,maxLines=1)
                }
            }
        }
    }
}
@Composable
fun MobileEditorSheet(title: String,maxHeight: Dp,onClose: ()->Unit,content: @Composable ColumnScope.()->Unit) {
    Surface(tonalElevation=1.dp,shadowElevation=2.dp,modifier=Modifier.fillMaxWidth().heightIn(max=maxHeight).testTag("mobile-editor-sheet")) {
        Column {
            var drag by remember {mutableFloatStateOf(0f)}
            Row(Modifier.fillMaxWidth().height(40.dp).pointerInput(onClose) {
                detectVerticalDragGestures(onDragStart={drag=0f},onDragEnd={if(drag>45.dp.toPx())onClose()},onVerticalDrag={change,amount->drag+=amount;change.consume()})
            },verticalAlignment=Alignment.CenterVertically) {
                Text(title,style=MaterialTheme.typography.labelLarge,modifier=Modifier.weight(1f).padding(horizontal=12.dp))
                IconButton(onClose,Modifier.size(44.dp).focusProperties {canFocus=false}) {Icon(Icons.Default.KeyboardArrowDown,"설정 닫기")}
            }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=10.dp,vertical=4.dp),content=content)
        }
    }
}
class NativeEditorController {
    var editor: EditText?=null
    private var range: Pair<Int,Int>?=null
    fun selectionChanged(start: Int,end: Int){range=start to end}
    fun capture(){editor?.let {range=it.selectionStart.coerceAtLeast(0) to it.selectionEnd.coerceAtLeast(0)}}
    fun restore(){editor?.let {v->range?.let {(a,b)->v.setSelection(a.coerceIn(0,v.length()),b.coerceIn(0,v.length()))}}}
    fun focus(){restore();editor?.let {v->if(v.keyListener!=null){v.requestFocus();(v.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(v,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)}}}
    fun clipboard(id: Int){restore();editor?.let {it.onTextContextMenuItem(id);capture()}}
    fun markdown(command: String){restore();editor?.let {v->
        val edit=MarkdownEditing.apply(v.text.toString(),v.selectionStart,v.selectionEnd,command)
        v.text.replace(0,v.length(),edit.text);v.setSelection(edit.first,edit.last);capture();v.requestFocus()
    }}
    fun navigate(end: Boolean){editor?.let {v->v.setSelection(if(end)v.length() else 0);v.requestFocus();v.bringPointIntoView(v.selectionStart);capture()}}
}
