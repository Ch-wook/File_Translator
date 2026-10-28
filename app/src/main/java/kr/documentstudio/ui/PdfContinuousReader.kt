package kr.documentstudio.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kr.documentstudio.data.*

private val readerSlots=Semaphore(2)

/** Only composed pages own bitmaps. Leaving the lazy viewport releases them. */
@Composable
fun PdfContinuousReader(s: Session,page: Int,navigationRequest: Int,busy: Boolean,horizontal: Boolean=false,
                        onVisiblePage: (Int)->Unit,onEditPage: ((Int)->Unit)?,initialZoom: Float=1f,onZoomChanged: (Float)->Unit={},initialOffset: Int=0,onPositionChanged: (Int,Int)->Unit={_,_->}) {
    val count=s.pdf!!.pages
    val list=rememberLazyListState(page.coerceIn(0,count-1),initialOffset.coerceAtLeast(0))
    var seenNavigation by remember {mutableIntStateOf(navigationRequest)}
    val pager=rememberPagerState(initialPage=page.coerceIn(0,count-1),pageCount={count})
    var zoom by rememberSaveable {mutableFloatStateOf(initialZoom.coerceIn(1f,3f))}
    val zoomChanged by rememberUpdatedState(onZoomChanged)
    LaunchedEffect(zoom){zoomChanged(zoom)}
    val visiblePage by rememberUpdatedState(onVisiblePage)
    val positionChanged by rememberUpdatedState(onPositionChanged)
    LaunchedEffect(s.base,navigationRequest,horizontal) {if(seenNavigation!=navigationRequest){if(horizontal)pager.scrollToPage(page.coerceIn(0,count-1)) else list.scrollToItem(page.coerceIn(0,count-1));seenNavigation=navigationRequest}}
    LaunchedEffect(list,pager,count,horizontal) {
        var previousPage=-1
        snapshotFlow {if(horizontal)pager.currentPage to 0 else list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset}.collect {(index,offset)->
            val current=index.coerceIn(0,count-1)
            if(previousPage!=current){visiblePage(current);previousPage=current}
            positionChanged(current,offset)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("${if(horizontal) "가로 보기" else "연속 보기"} ${(zoom*100).toInt()}%",style=MaterialTheme.typography.labelSmall)
            Slider(zoom,{zoom=it},valueRange=1f..3f,enabled=!busy,modifier=Modifier.weight(1f))
            if(onEditPage!=null) TextButton({onEditPage.invoke((if(horizontal) pager.currentPage else list.firstVisibleItemIndex).coerceIn(0,count-1))},enabled=!busy) {Text("현재 페이지 필기")}
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(Color(0xFFE5E5DF))) {
            val pageWidth=maxWidth*zoom
            if(horizontal) HorizontalPager(pager,modifier=Modifier.fillMaxSize().semantics {contentDescription="PDF 가로 페이지 목록"},userScrollEnabled=!busy && zoom==1f) {index ->
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(8.dp)) {
                    Column(Modifier.width((pageWidth-16.dp).coerceAtLeast(1.dp))) {ContinuousPage(s,index,busy,onEditPage?.let {callback->{callback(index)}})}
                }
            } else Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                LazyColumn(Modifier.width(pageWidth).fillMaxHeight().semantics {contentDescription="PDF 연속 페이지 목록";stateDescription="${list.firstVisibleItemIndex}:${list.firstVisibleItemScrollOffset}"},state=list,
                    contentPadding=PaddingValues(8.dp),verticalArrangement=Arrangement.spacedBy(12.dp),userScrollEnabled=!busy) {
                    items(count,key={it}) {index -> ContinuousPage(s,index,busy,onEditPage?.let {callback->{callback(index)}})}
                }
            }
        }
        if(horizontal && zoom>1f) Text("확대 중에는 가로로 이동합니다. 페이지는 아래 버튼으로 넘기세요.",style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(horizontal=12.dp))
    }
}

@Composable
private fun ContinuousPage(s: Session,index: Int,busy: Boolean,edit: (()->Unit)?) {
    val density=androidx.compose.ui.platform.LocalDensity.current
    val width=androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    var viewportPixels by remember {mutableIntStateOf(with(density){width.dp.roundToPx()})}
    val rasterWidth=PdfRasterBudget.requestedWidth(viewportPixels.coerceAtLeast(1),1f)
    var retry by remember {mutableIntStateOf(0)}
    var failure by remember(s.base,index,retry) {mutableStateOf<String?>(null)}
    val ink=remember(s.edits.strokes,index) {s.edits.strokes.filter {it.anchor=="pdf:$index"}}
    var bitmap by remember(s.base,index) {mutableStateOf<Bitmap?>(null)}
    val owned=remember(s.base,index) {mutableSetOf<Bitmap>()}
    DisposableEffect(s.base,index) {onDispose {owned.forEach {it.recycle()};owned.clear()}}
    LaunchedEffect(s.base,index,s.edits.values,ink,retry,rasterWidth) {
        var produced: Bitmap?=null
        try {
            if(bitmap!=null)delay(180)
            readerSlots.withPermit {
                withContext(Dispatchers.IO) {
                    produced=PdfEngine.renderWorking(s,index,rasterWidth)
                    val image=produced!!;val canvas=Canvas(image)
                    ink.forEach {drawStroke(canvas,it,image.width.toFloat(),image.height.toFloat())}
                }
            }
            val image=produced!!
            // Ownership transfers to the composed Image. Recycle its predecessor only
            // after Compose has actually removed that image, never from a posted task.
            owned+=image;bitmap=image;produced=null
        } catch(e: CancellationException) {produced?.recycle();throw e}
        catch(e: Exception) {produced?.recycle();failure=e.message ?: "페이지를 표시하지 못했습니다."}
    }
    Column(Modifier.fillMaxWidth().onSizeChanged {viewportPixels=it.width}.background(Color.White).border(1.dp,Color.LightGray)) {
        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("${index+1} 페이지",modifier=Modifier.weight(1f),style=MaterialTheme.typography.labelMedium)
            if(edit!=null) TextButton(edit,enabled=!busy) {Text("${index+1}페이지 필기")}
        }
        bitmap?.let {image->
            DisposableEffect(image) {onDispose {owned.remove(image);image.recycle()}}
            Image(image.asImageBitmap(),"연속 보기 ${index+1}페이지",modifier=Modifier.fillMaxWidth().aspectRatio(image.width.toFloat()/image.height))
        }
            ?: Box(Modifier.fillMaxWidth().aspectRatio(595f/842f),contentAlignment=Alignment.Center) {
                if(failure==null) CircularProgressIndicator()
                else Column(Modifier.padding(16.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                    Text("${index+1} 페이지: $failure")
                    TextButton({failure=null;retry++},enabled=!busy) {Text("다시 불러오기")}
                }
            }
    }
}
