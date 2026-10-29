package kr.documentstudio.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kr.documentstudio.data.*
import kotlin.math.*

private val readerSlots=Semaphore(2)

/** All logical pages remain in the lazy list; only nearby composed pages own rasters. */
@Composable
fun PdfContinuousReader(s: Session,page: Int,navigationRequest: Int,busy: Boolean,horizontal: Boolean=false,
                        onVisiblePage: (Int)->Unit,onEditPage: ((Int)->Unit)?,initialZoom: Float=1f,
                        onZoomChanged: (Float)->Unit={},initialOffset: Int=0,
                        onPositionChanged: (Int,Int)->Unit={_,_->},controller: PdfEditorController?=null) {
    val count=s.pdf!!.pages
    var renderer by remember(s.id) {mutableStateOf<PdfReadRenderer?>(null)}
    var rendererFailure by remember(s.id) {mutableStateOf(false)}
    var openRetry by remember(s.id) {mutableIntStateOf(0)}
    LaunchedEffect(s.base,s.edits.values,openRetry) {
        var opened: PdfReadRenderer?=null
        renderer=null;rendererFailure=false
        try {
            // One immutable working snapshot and native renderer per revision,
            // rather than reopening/re-serializing the whole PDF for every page.
            withContext(Dispatchers.IO){opened=PdfReadRenderer.open(s)}
            renderer=opened
            awaitCancellation()
        } catch(e: CancellationException){throw e}
        catch(e: Exception){rendererFailure=true}
        finally {
            if(renderer===opened)renderer=null
            withContext(NonCancellable+Dispatchers.IO){opened?.close()}
        }
    }
    val list=rememberLazyListState(page.coerceIn(0,count-1),initialOffset.coerceAtLeast(0))
    val pan=rememberScrollState()
    val scope=rememberCoroutineScope()
    val density=LocalDensity.current
    var seenNavigation by remember {mutableIntStateOf(navigationRequest)}
    val pager=rememberPagerState(initialPage=page.coerceIn(0,count-1),pageCount={count})
    var zoom by rememberSaveable(s.id) {mutableFloatStateOf(initialZoom.coerceIn(.5f,4f))}
    val zoomChanged by rememberUpdatedState(onZoomChanged)
    LaunchedEffect(zoom){zoomChanged(zoom)}
    val visiblePage by rememberUpdatedState(onVisiblePage)
    val positionChanged by rememberUpdatedState(onPositionChanged)
    LaunchedEffect(s.base,navigationRequest,horizontal) {
        if(seenNavigation!=navigationRequest){
            if(horizontal)pager.scrollToPage(page.coerceIn(0,count-1))
            else list.scrollToItem(page.coerceIn(0,count-1))
            seenNavigation=navigationRequest
        }
    }
    LaunchedEffect(list,pager,count,horizontal) {
        var previousPage=-1
        snapshotFlow {if(horizontal)pager.currentPage to 0 else list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset}.collect {(index,offset)->
            val current=index.coerceIn(0,count-1)
            if(previousPage!=current){visiblePage(current);previousPage=current}
            positionChanged(current,offset)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFFE5E5DF))) {
        val viewportWidth=with(density){maxWidth.toPx()}
        val viewportHeight=with(density){maxHeight.toPx()}
        val gutter=with(density){12.dp.toPx()}
        val baseWidth=(maxWidth-24.dp).coerceAtLeast(1.dp)
        val basePixels=with(density){baseWidth.toPx()}
        fun changeZoom(value: Float,oldFocus: Offset,newFocus: Offset) {
            if(!value.isFinite())return
            val old=zoom
            val next=value.coerceIn(.5f,4f)
            val ratio=next/old
            if(!horizontal) {
                val info=list.layoutInfo
                val anchor=info.visibleItemsInfo.firstOrNull {oldFocus.y>=it.offset && oldFocus.y<=it.offset+it.size}
                    ?: info.visibleItemsInfo.minByOrNull {abs((it.offset+it.size/2f)-oldFocus.y)}
                val first=info.visibleItemsInfo.firstOrNull {it.index==list.firstVisibleItemIndex}
                val padding=list.firstVisibleItemScrollOffset+(first?.offset ?: 0)
                if(anchor!=null) {
                    val within=(oldFocus.y-anchor.offset).coerceIn(0f,anchor.size.toFloat())
                    list.requestScrollToItem(anchor.index,(padding+within*ratio-newFocus.y).roundToInt())
                }
                val oldLeft=max(gutter,(viewportWidth-basePixels*old)/2f)
                val newLeft=max(gutter,(viewportWidth-basePixels*next)/2f)
                val x=((pan.value+oldFocus.x-oldLeft)*ratio+newLeft-newFocus.x).roundToInt()
                zoom=next
                scope.launch {withFrameNanos{};pan.scrollTo(x.coerceAtLeast(0))}
            } else zoom=next
        }
        val zoomAction by rememberUpdatedState<(Float,Offset,Offset)->Unit>(::changeZoom)
        val zoomValue by rememberUpdatedState(zoom)
        val center=Offset(viewportWidth/2f,viewportHeight/2f)
        SideEffect {
            controller?.zoom=zoom
            controller?.selected=null
            controller?.setZoom={zoomAction(it,center,center)}
            controller?.fitPage={
                val index=if(horizontal)pager.currentPage else list.firstVisibleItemIndex
                val size=s.pdf?.pageSizes?.getOrNull(index)
                val fit=if(size!=null)(viewportHeight-2*gutter)/(basePixels*size.height/size.width) else 1f
                zoomAction(min(1f,fit),center,center)
                if(!horizontal)list.requestScrollToItem(index,0)
            }
        }
        val gestures=Modifier.pointerInput(s.id,busy,horizontal) {
            if(busy)return@pointerInput
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed=false,pass=PointerEventPass.Initial)
                do {
                    val event=awaitPointerEvent(PointerEventPass.Initial)
                    if(event.changes.count {it.pressed}>=2) {
                        val current=event.calculateCentroid()
                        val previous=event.calculateCentroid(useCurrent=false).let {if(it.x.isFinite() && it.y.isFinite())it else current}
                        val factor=event.calculateZoom().takeIf {it.isFinite() && it>0} ?: 1f
                        zoomAction(zoomValue*factor,previous,current)
                        event.changes.forEach {it.consume()}
                    }
                } while(event.changes.any {it.pressed})
            }
        }.pointerInput(s.id,busy) {
            if(!busy)detectTapGestures(onDoubleTap={point->zoomAction(if(zoomValue<1.8f)2f else 1f,point,point)})
        }
        val documentWidth=baseWidth*zoom
        val surfaceWidth=maxOf(maxWidth,documentWidth+24.dp)
        Box(Modifier.fillMaxSize().then(gestures)) {
            if(rendererFailure) {
                Column(Modifier.align(Alignment.Center).padding(16.dp),horizontalAlignment=Alignment.CenterHorizontally){
                    Text("PDF 페이지를 준비하지 못했습니다. 원본 파일은 변경하지 않았습니다.")
                    TextButton({openRetry++},enabled=!busy){Text("다시 불러오기")}
                }
                return@Box
            }
            if(horizontal) HorizontalPager(pager,modifier=Modifier.fillMaxSize().semantics {contentDescription="PDF 가로 페이지 목록"},userScrollEnabled=!busy && zoom<=1f) {index->
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp)) {
                    ContinuousPage(s,index,busy,renderer,onEditPage?.let {callback->{callback(index)}},Modifier.width(documentWidth))
                }
            } else Box(Modifier.fillMaxSize().horizontalScroll(pan)) {
                LazyColumn(Modifier.width(surfaceWidth).fillMaxHeight().semantics {
                    contentDescription="PDF 연속 페이지 목록"
                    stateDescription="${list.firstVisibleItemIndex}:${list.firstVisibleItemScrollOffset}"
                },state=list,contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp),userScrollEnabled=!busy) {
                    items(count,key={it}) {index->
                        Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.TopCenter) {
                            ContinuousPage(s,index,busy,renderer,onEditPage?.let {callback->{callback(index)}},Modifier.width(documentWidth))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContinuousPage(s: Session,index: Int,busy: Boolean,renderer: PdfReadRenderer?,edit: (()->Unit)?,modifier: Modifier=Modifier) {
    val density=LocalDensity.current
    var viewportPixels by remember {mutableIntStateOf(with(density){360.dp.roundToPx()})}
    val rasterWidth=PdfRasterBudget.requestedWidth(viewportPixels.coerceAtLeast(1),1f)
    var retry by remember {mutableIntStateOf(0)}
    var failure by remember(s.base,index,retry) {mutableStateOf<String?>(null)}
    val ink=remember(s.edits.strokes,index) {s.edits.strokes.filter {it.anchor=="pdf:$index"}}
    var bitmap by remember(s.base,index) {mutableStateOf<Bitmap?>(null)}
    val size=s.pdf?.pageSizes?.getOrNull(index)
    val aspect=if(size!=null)size.width/size.height else 595f/842f
    val owned=remember(s.base,index) {mutableSetOf<Bitmap>()}
    DisposableEffect(s.base,index) {onDispose {owned.forEach {it.recycle()};owned.clear()}}
    LaunchedEffect(s.base,index,s.edits.values,ink,retry,rasterWidth,renderer) {
        if(renderer==null)return@LaunchedEffect
        var produced: Bitmap?=null
        try {
            if(bitmap!=null)delay(180)
            readerSlots.withPermit {
                withContext(Dispatchers.IO) {
                    produced=renderer.render(index,rasterWidth)
                    val image=produced!!;val canvas=Canvas(image)
                    ink.forEach {drawStroke(canvas,it,image.width.toFloat(),image.height.toFloat())}
                }
            }
            val image=produced!!
            owned+=image;bitmap=image;produced=null
        } catch(e: CancellationException) {produced?.recycle();throw e}
        catch(e: Exception) {produced?.recycle();failure="페이지를 표시하지 못했습니다. 다시 불러오세요."}
    }
    Box(modifier.onSizeChanged {viewportPixels=it.width}.aspectRatio(aspect).background(Color.White).border(1.dp,Color.LightGray)) {
        bitmap?.let {image->
            DisposableEffect(image) {onDispose {owned.remove(image);image.recycle()}}
            Image(image.asImageBitmap(),"연속 보기 ${index+1}페이지",modifier=Modifier.fillMaxSize())
        } ?: Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
            if(failure==null)CircularProgressIndicator()
            else Column(Modifier.padding(16.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Text("${index+1} 페이지: $failure")
                TextButton({failure=null;retry++},enabled=!busy) {Text("다시 불러오기")}
            }
        }
        Surface(modifier=Modifier.align(Alignment.BottomEnd).padding(6.dp),color=MaterialTheme.colorScheme.surface.copy(alpha=.88f),shape=MaterialTheme.shapes.small) {
            if(edit!=null)TextButton(edit,enabled=!busy){Text("${index+1}페이지 필기")}
            else Text("${index+1}",style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(horizontal=8.dp,vertical=4.dp))
        }
    }
}
