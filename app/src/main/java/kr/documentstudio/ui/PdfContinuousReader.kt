package kr.documentstudio.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kr.documentstudio.data.*

private val readerSlots=Semaphore(2)

/** Only composed pages own bitmaps. Leaving the lazy viewport releases them. */
@Composable
fun PdfContinuousReader(s: Session,page: Int,navigationRequest: Int,busy: Boolean,
                        onVisiblePage: (Int)->Unit,onEditPage: (Int)->Unit) {
    val count=s.pdf!!.pages
    val list=rememberLazyListState(page.coerceIn(0,count-1))
    var zoom by rememberSaveable {mutableFloatStateOf(1f)}
    val visiblePage by rememberUpdatedState(onVisiblePage)
    LaunchedEffect(s.base,navigationRequest) {list.scrollToItem(page.coerceIn(0,count-1))}
    LaunchedEffect(list,count) {snapshotFlow {list.firstVisibleItemIndex}.collect {visiblePage(it.coerceIn(0,count-1))}}
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("연속 보기 ${(zoom*100).toInt()}%",style=MaterialTheme.typography.labelSmall)
            Slider(zoom,{zoom=it},valueRange=1f..3f,enabled=!busy,modifier=Modifier.weight(1f))
            TextButton({onEditPage(list.firstVisibleItemIndex)},enabled=!busy) {Text("현재 페이지 필기")}
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(Color(0xFFE5E5DF))) {
            val pageWidth=maxWidth*zoom
            Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                LazyColumn(Modifier.width(pageWidth).fillMaxHeight().semantics {contentDescription="PDF 연속 페이지 목록"},state=list,
                    contentPadding=PaddingValues(8.dp),verticalArrangement=Arrangement.spacedBy(12.dp),userScrollEnabled=!busy) {
                    items(count,key={it}) {index -> ContinuousPage(s,index,busy) {onEditPage(index)}}
                }
            }
        }
    }
}

@Composable
private fun ContinuousPage(s: Session,index: Int,busy: Boolean,edit: ()->Unit) {
    var retry by remember {mutableIntStateOf(0)}
    var failure by remember(s.base,index,retry) {mutableStateOf<String?>(null)}
    val ink=remember(s.edits.strokes,index) {s.edits.strokes.filter {it.anchor=="pdf:$index"}}
    val bitmap by key(s.base,index,ink,retry) {produceState<Bitmap?>(null) {
        var loaded: Bitmap?=null
        try {
            readerSlots.withPermit {
                withContext(Dispatchers.IO) {
                    loaded=PdfEngine.render(s.base,index)
                    val image=loaded!!;val canvas=Canvas(image)
                    ink.forEach {drawStroke(canvas,it,image.width.toFloat(),image.height.toFloat())}
                }
            }
            value=loaded
            awaitDispose {loaded?.recycle()}
        } catch(e: CancellationException) {loaded?.recycle();throw e}
        catch(e: Exception) {loaded?.recycle();failure=e.message ?: "페이지를 표시하지 못했습니다."}
    }}
    Column(Modifier.fillMaxWidth().background(Color.White).border(1.dp,Color.LightGray)) {
        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("${index+1} 페이지",modifier=Modifier.weight(1f),style=MaterialTheme.typography.labelMedium)
            TextButton(edit,enabled=!busy) {Text("${index+1}페이지 필기")}
        }
        bitmap?.let {Image(it.asImageBitmap(),"연속 보기 ${index+1}페이지",modifier=Modifier.fillMaxWidth().aspectRatio(it.width.toFloat()/it.height))}
            ?: Box(Modifier.fillMaxWidth().aspectRatio(595f/842f),contentAlignment=Alignment.Center) {
                if(failure==null) CircularProgressIndicator()
                else Column(Modifier.padding(16.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                    Text("${index+1} 페이지: $failure")
                    TextButton({failure=null;retry++},enabled=!busy) {Text("다시 불러오기")}
                }
            }
    }
}
