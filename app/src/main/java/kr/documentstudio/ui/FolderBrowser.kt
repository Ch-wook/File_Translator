package kr.documentstudio.ui

import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*
import kr.documentstudio.data.*

@Composable
fun FolderBrowser(tree: Uri,busy: Boolean,open: (Uri)->Unit,choose: ()->Unit,close: ()->Unit) {
    val context=LocalContext.current
    val root=remember(tree) {DocumentsContract.getTreeDocumentId(tree)}
    var parents by rememberSaveable(tree.toString()) {mutableStateOf(listOf(root))}
    var labels by rememberSaveable(tree.toString()) {mutableStateOf(listOf("선택한 폴더"))}
    var query by rememberSaveable {mutableStateOf("")}
    var recursive by rememberSaveable {mutableStateOf(true)}
    var refresh by remember {mutableIntStateOf(0)}
    val source=remember(tree,context,refresh) {DocumentTree(CachedTreeSource(SafTreeSource(context.contentResolver,tree)))}
    var failure by remember {mutableStateOf<String?>(null)}
    val listing by produceState<TreeListing?>(null,tree,parents.last(),query,recursive,refresh) {
        value=null;failure=null
        try {
            if(query.isNotEmpty()) delay(250)
            value=withContext(Dispatchers.Default) {source.list(parents.last(),query,recursive && query.isNotBlank())}
        } catch(e: CancellationException) {throw e}
        catch(e: Exception) {failure=kr.documentstudio.data.UserProblems.explain(e).advice}
    }
    Dialog(close,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.9f),shape=MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text("폴더 탐색·파일 검색",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
                    IconButton({refresh++},enabled=!busy) {Icon(Icons.Default.Refresh,"폴더 새로고침")}
                    IconButton(close) {Icon(Icons.Default.Close,"폴더 탐색 닫기")}
                }
                Row(verticalAlignment=Alignment.CenterVertically) {
                    TextButton({parents=parents.dropLast(1);labels=labels.dropLast(1);query=""},enabled=parents.size>1) {Text("상위 폴더")}
                    Text(labels.joinToString(" / "),maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
                    TextButton(choose) {Text("폴더 변경")}
                }
                OutlinedTextField(query,{query=it},label={Text("선택한 폴더에서 파일 이름 검색")},singleLine=true,modifier=Modifier.fillMaxWidth())
                Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(recursive,{recursive=it});Text("검색할 때 하위 폴더 포함",style=MaterialTheme.typography.labelMedium)}
                Text("선택한 폴더의 권한만 사용합니다. 최대 20,000항목 검사·500결과 표시.",style=MaterialTheme.typography.labelSmall)
                failure?.let {Text(it,color=MaterialTheme.colorScheme.error);TextButton({refresh++}) {Text("다시 읽기")}}
                if(listing==null && failure==null) LinearProgressIndicator(Modifier.fillMaxWidth())
                listing?.let {result ->
                    if(result.truncated) Text("표시 한도에 도달했습니다. 이름을 더 입력하거나 작은 폴더를 선택하세요.",color=MaterialTheme.colorScheme.error)
                    if(result.entries.isEmpty()) Text("일치하는 파일이 없습니다.",modifier=Modifier.padding(12.dp))
                    LazyColumn(Modifier.weight(1f)) {items(result.entries,key={it.id}) {entry ->
                        ListItem(headlineContent={Text(entry.name,maxLines=2,overflow=TextOverflow.Ellipsis)},
                            supportingContent={Text(if(entry.directory) "폴더" else "${entry.size.coerceAtLeast(0)/1024} KB")},
                            leadingContent={Icon(if(entry.directory) Icons.Default.Folder else Icons.Default.Description,null)},
                            modifier=Modifier.clickable(enabled=!busy) {
                                if(entry.directory) {
                                    val existing=parents.indexOf(entry.id)
                                    if(existing>=0) {parents=parents.take(existing+1);labels=labels.take(existing+1)}
                                    else {parents=parents+entry.id;labels=labels+entry.name}
                                    query=""
                                }
                                else {close();open(entry.uri)}
                            })
                    }}
                }
            }
        }
    }
}
