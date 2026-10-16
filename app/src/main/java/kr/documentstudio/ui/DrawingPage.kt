package kr.documentstudio.ui

import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kr.documentstudio.*
import kr.documentstudio.core.*
import kr.documentstudio.data.*

@Composable
fun DrawingPage(s: Session,sec: Section?,page: Int,settings: Settings,vm: DocumentActions,busy: Boolean) {
    val tool=runCatching {InkTool.valueOf(settings.penTool)}.getOrDefault(InkTool.PEN)
    val color=Color(settings.penColor)
    val line=settings.penWidth
    var activeLayer by rememberSaveable(s.id) {mutableStateOf("default")}
    var showLayers by remember {mutableStateOf(false)}
    LaunchedEffect(s.edits.layers) {if(s.edits.layers.none {it.id==activeLayer}) activeLayer=s.edits.layers.first().id}
    val visibleStrokes=remember(s.edits.strokes,s.edits.layers) {InkLayers.ordered(s.edits)}
    var zoom by remember {mutableFloatStateOf(1f)}
    var visible by remember {mutableStateOf(true)}
    val anchor=if(s.format==Format.PDF) "pdf:$page" else sec?.id ?: "text"
    var selected by remember(anchor) {mutableStateOf<Set<String>>(emptySet())}
    var transform by remember {mutableStateOf(false)};var customColor by remember {mutableStateOf(false)}
    val vertical=rememberScrollState();val horizontal=rememberScrollState();val scope=rememberCoroutineScope()
    val keyboard=LocalSoftwareKeyboardController.current
    val slide=if(s.format==Format.PPTX) rememberSlide(s,sec) else null
    LaunchedEffect(Unit) {keyboard?.hide()}
    var renderRetry by remember {mutableIntStateOf(0)}
    var renderFailure by remember(s.base,page,renderRetry) {mutableStateOf<String?>(null)}
    val image by key(s.base,page,renderRetry) {produceState<android.graphics.Bitmap?>(null) {
        var loaded: android.graphics.Bitmap?=null
        try {
            if(s.format==Format.PDF) {
                // Keep ownership here even if cancellation arrives as IO completes.
                withContext(Dispatchers.IO) {loaded=PdfEngine.render(s.base,page)}
                value=loaded
            }
            awaitDispose {loaded?.recycle()}
        } catch(e: kotlinx.coroutines.CancellationException) {loaded?.recycle();throw e}
        catch(e: Exception) {loaded?.recycle();renderFailure=e.message ?: "페이지를 표시하지 못했습니다."}
    }}
    val ratio=if(image!=null) image!!.width.toFloat()/image!!.height else if(slide!=null) slide.width/slide.height else if(s.format in listOf(Format.PPTX,Format.PPT)) 16f/9f else 595f/842f
    val text by produceState("",sec,s.sections,s.edits.text,s.edits.values) {
        value=withContext(Dispatchers.Default) {
            if(sec==null) s.edits.text else if(s.format in listOf(Format.XLSX,Format.XLS)) {
                val cells=s.sheetCells(sec)
                val formula=s.calculator(sec);cells.entries.take(80).joinToString("\n") {"${it.key}    ${formula.display(it.key)}"}
            } else sec.fields.joinToString("\n") {s.edits.values[it.id] ?: it.text}
        }
    }
    val toolbar: @Composable ()->Unit = {Column {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            TextButton({vm.settings(settings.copy(toolbarCollapsed=!settings.toolbarCollapsed))}) {Text(if(settings.toolbarCollapsed) "필기 도구 펼치기" else "필기 도구 접기")}
            TextButton({vm.settings(settings.copy(toolbarTop=!settings.toolbarTop))}) {Text(if(settings.toolbarTop) "도구 아래로" else "도구 위로")}
            TextButton({showLayers=true},enabled=!busy) {Text("레이어")}
        }
        if(!settings.toolbarCollapsed) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=8.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            InkTool.entries.forEach { t -> FilterChip(tool==t,{vm.settings(settings.copy(penTool=t.name))},label={Text(when(t){InkTool.PEN->"볼펜";InkTool.FOUNTAIN->"만년필";InkTool.PENCIL->"연필";InkTool.BRUSH->"브러시";InkTool.HIGHLIGHTER->"형광펜";InkTool.ERASER->"획 지우개";InkTool.LINE->"직선";InkTool.ARROW->"화살표";InkTool.RECTANGLE->"사각형";InkTool.ELLIPSE->"원";InkTool.LASSO->"올가미";InkTool.HAND->"손"})},enabled=!busy)}
            FilterChip(visible,{visible=!visible},label={Text("필기 표시")},enabled=!busy)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
            (listOf(0xFF294E46.toInt(),0xFF1A1A1A.toInt(),0xFFBC4949.toInt(),0xFF315FBB.toInt(),0xFFF2C844.toInt())+settings.favoriteColors).distinct().map {Color(it)}.forEach { c ->
                Box(Modifier.size(36.dp).padding(5.dp).background(c,androidx.compose.foundation.shape.CircleShape).border(if(c==color) 3.dp else 0.dp,Color.Gray,androidx.compose.foundation.shape.CircleShape).clickable(enabled=!busy){vm.settings(settings.copy(penColor=c.toArgb()))})
            }
            Text(if(tool==InkTool.ERASER) "지우개 크기" else "굵기",style=MaterialTheme.typography.labelSmall)
            if(tool==InkTool.ERASER) Slider(settings.eraserRadius,{vm.settings(settings.copy(eraserRadius=it))},valueRange=0.005f..0.08f,modifier=Modifier.width(100.dp),enabled=!busy)
            else Slider(line,{vm.settings(settings.copy(penWidth=it))},valueRange=0.002f..0.025f,modifier=Modifier.width(100.dp),enabled=!busy)
            TextButton({customColor=true},enabled=!busy) {Text("설정")}
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("${(zoom*100).toInt()}%",style=MaterialTheme.typography.labelSmall)
            Slider(zoom,{zoom=it},valueRange=1f..5f,modifier=Modifier.width(100.dp),enabled=!busy)
            if(s.format!=Format.PDF) Text("문서 필기",style=MaterialTheme.typography.labelSmall)
        }
        if(selected.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=8.dp)) {
            Text("${selected.size}획 선택",modifier=Modifier.padding(12.dp));TextButton({transform=true},enabled=!busy) {Text("이동·크기·회전")};TextButton({vm.transformInk(selected,duplicate=true)},enabled=!busy) {Text("복사")};TextButton({vm.erase(selected);selected=emptySet()},enabled=!busy) {Text("삭제")};TextButton({selected=emptySet()}) {Text("선택 해제")}
        }
        if(tool==InkTool.ERASER) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                FilterChip(!settings.partialEraser,{vm.settings(settings.copy(partialEraser=false))},label={Text("획 전체")},enabled=!busy)
                Spacer(Modifier.width(8.dp))
                FilterChip(settings.partialEraser,{vm.settings(settings.copy(partialEraser=true))},label={Text("지나간 부분만")},enabled=!busy)
                TextButton({vm.erase(s.edits.strokes.filter {it.anchor==anchor}.map {it.id}.toSet())},enabled=!busy) {Text("페이지 필기 전체 지우기")}
            }
            Text("펜을 떼면 지우기가 적용됩니다 · 실행 취소 가능",style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(horizontal=16.dp))
        }
        if(s.format!=Format.PDF) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=8.dp)) {
            Paper.entries.forEach {p->FilterChip(s.edits.paper==p,{vm.paper(p)},label={Text(when(p) {Paper.BLANK->"무지";Paper.LINED->"줄노트";Paper.GRID->"격자";Paper.DOTS->"점노트"})},enabled=!busy)}
        }
        }
    }}
    Column(Modifier.fillMaxSize()) {
        if(settings.toolbarTop) toolbar()
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(Color(0xFFE5E5DF))) {
            val pageWidth=minOf(maxWidth-24.dp,(maxHeight-24.dp)*ratio)*zoom
            val viewportWidth=maxWidth-24.dp
            Box(Modifier.fillMaxSize().verticalScroll(vertical).horizontalScroll(horizontal).padding(12.dp)) {
                Box(Modifier.width(maxOf(pageWidth,viewportWidth)),contentAlignment=Alignment.TopCenter) {
                Box(Modifier.width(pageWidth).aspectRatio(ratio).background(Color.White).border(1.dp,Color(0xFFD5D7D1))) {
                    if(s.format==Format.PDF) {
                        if(image!=null) Image(image!!.asImageBitmap(),"PDF ${page+1}페이지",modifier=Modifier.fillMaxSize())
                        else if(renderFailure==null) CircularProgressIndicator(Modifier.align(Alignment.Center))
                        else Column(Modifier.align(Alignment.Center).padding(16.dp)) {
                            Text("PDF 페이지 표시 실패: $renderFailure")
                            TextButton({renderRetry++},enabled=!busy) {Text("다시 불러오기")}
                        }
                    } else if(slide!=null) AndroidView(factory={SlideView(it)},update={it.scene=slide;it.editable=false;it.invalidate()},modifier=Modifier.fillMaxSize())
                    else AndroidView(factory={context -> NoteView(context)},update={it.text=text;it.pageRatio=ratio;it.paper=s.edits.paper;it.invalidate()},modifier=Modifier.fillMaxSize())
                    if(visible && (s.format!=Format.PDF || image!=null)) AndroidView(factory={context -> InkView(context)},update={v ->
                        v.strokes=visibleStrokes;v.anchor=anchor;v.tool=tool;v.inkColor=color.toArgb();v.lineWidth=line
                        v.opacity=settings.penOpacity;v.pressureCurve=settings.pressureCurve;v.smoothing=settings.smoothing;v.autoSnap=settings.autoSnap;v.layerId=activeLayer;v.selected=selected;v.onSelect={selected=it}
                        v.onLasso={polygon->val snapshot=visibleStrokes.filter {it.layerId in InkLayers.editable(s.edits)};scope.launch {selected=withContext(Dispatchers.Default) {InkGeometry.select(snapshot,anchor,polygon)}}}
                        v.onZoom={zoom=(zoom*it).coerceIn(1f,5f)};v.onPan={dx,dy->scope.launch {horizontal.scrollTo((horizontal.value-dx).toInt());vertical.scrollTo((vertical.value-dy).toInt())}}
                        v.stylusOnly=settings.stylusOnly;v.onStroke=vm::addStroke;v.eraserRadius=settings.eraserRadius
                        v.onEraseGesture={gesture,radius,aspect->vm.eraseInk(anchor,gesture,radius,aspect,settings.partialEraser)};v.isEnabled=!busy && activeLayer in InkLayers.editable(s.edits);v.invalidate()
                    },modifier=Modifier.fillMaxSize())
                }
                }
            }
        }
        if(!settings.toolbarTop) toolbar()
    }
    if(customColor) InkSettingsDialog(settings,vm) {customColor=false}
    if(showLayers) InkLayersDialog(s.edits,activeLayer,vm,{activeLayer=it}) {showLayers=false}
    if(transform) {
        var x by remember {mutableStateOf("0")};var y by remember {mutableStateOf("0")};var scale by remember {mutableStateOf("1")};var rotation by remember {mutableStateOf("0")}
        AlertDialog(onDismissRequest={transform=false},title={Text("선택 필기 변형")},text={Column {
            listOf(Triple("가로 이동 %",x,{v: String->x=v}),Triple("세로 이동 %",y,{v: String->y=v}),Triple("크기 배수",scale,{v: String->scale=v}),Triple("회전 각도",rotation,{v: String->rotation=v})).forEach {(label,value,change)->OutlinedTextField(value,change,label={Text(label)},singleLine=true)}
        }},confirmButton={TextButton({val numbers=listOf(x,y,scale,rotation).map {it.toFloatOrNull()};if(numbers.all {it!=null && it.isFinite()}) {vm.transformInk(selected,numbers[0]!!/100,numbers[1]!!/100,numbers[2]!!,numbers[3]!!);transform=false} else vm.message("숫자를 입력하세요.")}) {Text("적용")}},dismissButton={TextButton({transform=false}) {Text("취소")}})
    }
}

private class NoteView(context: android.content.Context): View(context) {
    var paper=Paper.BLANK
    var text=""
    var pageRatio=595f/842f
    private var cachedText=""
    private var layout: StaticLayout?=null
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawPaper(canvas,paper,width.toFloat(),height.toFloat())
        if(layout==null || cachedText!=text) {
            val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply {textSize=12f;color=AndroidColor.BLACK}
            layout=StaticLayout.Builder.obtain(text,0,text.length,paint,511).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(4f,1f).build()
            cachedText=text
        }
        canvas.save();canvas.scale(width/595f,width/595f);canvas.clipRect(0f,0f,595f,595f/pageRatio);canvas.translate(42f,48f);layout!!.draw(canvas);canvas.restore()
    }
}
