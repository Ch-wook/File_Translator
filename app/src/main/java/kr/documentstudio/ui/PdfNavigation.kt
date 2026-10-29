package kr.documentstudio.ui

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kr.documentstudio.DocumentActions
import kr.documentstudio.core.DocumentChange
import kr.documentstudio.data.*

private val thumbnailSlots=Semaphore(2)

@Composable
fun PdfNavigationActions(s: Session,page: Int,vm: DocumentActions,busy: Boolean,navigate: (Int)->Unit) {
    var thumbnails by remember {mutableStateOf(false)};var bookmarks by remember {mutableStateOf(false)}
    TextButton({thumbnails=true},enabled=!busy) {Text("페이지 미리보기")}
    TextButton({bookmarks=true},enabled=!busy) {Text("북마크")}
    if(thumbnails) AlertDialog(onDismissRequest={thumbnails=false},title={Text("PDF 페이지")},text={
        LazyVerticalGrid(columns=GridCells.Fixed(2),modifier=Modifier.heightIn(max=460.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            items(s.pdf!!.pages,key={it}) {index->Column(Modifier.border(if(index==page) 2.dp else 1.dp,if(index==page) MaterialTheme.colorScheme.primary else Color.LightGray).clickable {navigate(index);thumbnails=false}.padding(6.dp)) {Thumbnail(s,index,vm);Text("${index+1} 페이지",style=MaterialTheme.typography.labelSmall)}}
        }
    },confirmButton={TextButton({thumbnails=false}) {Text("닫기")}})
    if(bookmarks) {
        var title by remember(page) {mutableStateOf("${page+1} 페이지")};var rename by remember {mutableStateOf<PdfBookmark?>(null)};var newName by remember {mutableStateOf("")}
        val entries by produceState<List<PdfBookmark>?>(null,s.base) {value=withContext(Dispatchers.IO) {runCatching {PdfBookmarks.list(s.base)}.getOrElse {vm.message(it.message);emptyList()}}}
        AlertDialog(onDismissRequest={bookmarks=false},title={Text("PDF 북마크")},text={Column {
            if(vm.canEdit()) OutlinedTextField(title,{title=it},label={Text("현재 페이지 북마크 이름")},singleLine=true,enabled=!busy)
            if(vm.canEdit())TextButton({vm.change(DocumentChange("pdf.bookmarkAdd",page.toString(),mapOf("title" to title)))},enabled=!busy && title.isNotBlank()) {Text("현재 페이지 추가")}
            if(entries==null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LazyColumn(Modifier.heightIn(max=300.dp)) {items(entries!!,key={it.id}) {entry->Row(Modifier.fillMaxWidth().padding(start=entry.depth.coerceAtMost(5).times(5).dp)) {
                TextButton({navigate(entry.page);bookmarks=false},enabled=entry.page>=0 && !busy,modifier=Modifier.weight(1f)) {Text(entry.title+(if(entry.page>=0) " · ${entry.page+1}" else ""),maxLines=2)}
                if(vm.canEdit())TextButton({rename=entry;newName=entry.title},enabled=!busy) {Text("이름")};if(vm.canEdit())TextButton({vm.change(DocumentChange("pdf.bookmarkDelete",page.toString(),mapOf("id" to entry.id)))},enabled=!busy) {Text("삭제")}
            }}}
        }},confirmButton={TextButton({bookmarks=false}) {Text("닫기")}})
        rename?.let {entry->AlertDialog(onDismissRequest={rename=null},title={Text("북마크 이름")},text={OutlinedTextField(newName,{newName=it},singleLine=true)},confirmButton={TextButton({vm.change(DocumentChange("pdf.bookmarkRename",page.toString(),mapOf("id" to entry.id,"title" to newName)));rename=null},enabled=newName.isNotBlank() && !busy) {Text("적용")}},dismissButton={TextButton({rename=null}) {Text("취소")}})}
    }
}

@Composable
private fun Thumbnail(s: Session,index: Int,vm: DocumentActions) {
    val bitmap by key(s.base,index,s.edits.strokes) {produceState<Bitmap?>(null) {
        var loaded: Bitmap?=null
        try {
            thumbnailSlots.withPermit {withContext(Dispatchers.IO) {loaded=PdfEngine.render(s.base,index,240,340,s.pdf?.annotatedPages);val bitmap=loaded!!;val canvas=android.graphics.Canvas(bitmap);s.edits.strokes.filter {it.anchor=="pdf:$index"}.forEach {drawStroke(canvas,it,bitmap.width.toFloat(),bitmap.height.toFloat())}}}
            value=loaded;awaitDispose {loaded?.recycle()}
        } catch(e: CancellationException) {loaded?.recycle();throw e} catch(e: Exception) {loaded?.recycle();vm.message("페이지 미리보기 실패: ${e.message}")}
    }}
    bitmap?.let {Image(it.asImageBitmap(),"${index+1} 페이지 미리보기",modifier=Modifier.fillMaxWidth().height(150.dp))} ?: Box(Modifier.fillMaxWidth().height(150.dp)) {Text("불러오는 중…")}
}
