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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
    val sheet=s.format in listOf(Format.XLSX,Format.XLS) && sec!=null
    var startCell by rememberSaveable(s.id,sec?.id) {mutableStateOf("A1")}
    var firstRow by rememberSaveable(s.id,sec?.id) {mutableIntStateOf(1)}
    var firstColumn by rememberSaveable(s.id,sec?.id) {mutableIntStateOf(1)}
    var bindCell by rememberSaveable(s.id) {mutableStateOf(true)}
    var gridError by remember(s.base,firstRow,firstColumn) {mutableStateOf<String?>(null)}
    val grid by produceState<SheetInkGrid?>(null,s.base,sec,firstRow,firstColumn) {
        if(sheet) try {value=withContext(Dispatchers.IO) {SheetInkMeasurements.read(s,sec!!,firstRow,firstColumn)}} catch(e: kotlinx.coroutines.CancellationException) {throw e} catch(e: Exception) {gridError=e.message}
    }
    val cellValues by produceState<Map<String,String>>(emptyMap(),s.sections,s.edits.values,grid) {
        if(sheet && grid!=null) value=withContext(Dispatchers.Default) {val formula=s.calculator(sec!!);grid!!.rows.flatMap {r->grid!!.columns.map {c->columnName(c.index)+r.index}}.associateWith {formula.display(it)}}
    }
    val projection=remember(sec?.id,grid) {if(sheet && grid!=null) InkProjection(sec!!.id,grid!!) else null}
    val visibleStrokes=remember(s.edits.strokes,s.edits.layers,projection) {InkLayers.ordered(s.edits).mapNotNull {projection?.display(it) ?: if(projection==null) it else null}}
    var zoom by remember {mutableFloatStateOf(1f)}
    var visible by remember {mutableStateOf(true)}
    val anchor=if(s.format==Format.PDF) "pdf:$page" else sec?.id ?: "text"
    var selected by remember(anchor) {mutableStateOf<Set<String>>(emptySet())}
    LaunchedEffect(projection) {selected=emptySet()}
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
    var moreTools by remember {mutableStateOf(false)}
    val names=mapOf(InkTool.PEN to "볼펜",InkTool.FOUNTAIN to "만년필",InkTool.PENCIL to "연필",InkTool.BRUSH to "브러시",InkTool.HIGHLIGHTER to "형광펜",InkTool.ERASER to "획 지우개",InkTool.LINE to "직선",InkTool.ARROW to "화살표",InkTool.RECTANGLE to "사각형",InkTool.ELLIPSE to "원",InkTool.LASSO to "올가미",InkTool.HAND to "손")
    val toolbar: @Composable ()->Unit = {
        Surface(color=Color.White,shadowElevation=4.dp) {
            Column {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center) {
                    val primary=listOf(InkTool.PEN,InkTool.HIGHLIGHTER,InkTool.ERASER,InkTool.LASSO,InkTool.HAND)
                    primary.forEach {t->
                        IconToggleButton(tool==t,{vm.settings(settings.copy(penTool=t.name))},enabled=!busy) {
                            Icon(when(t) {InkTool.PEN->Icons.Default.Edit;InkTool.HIGHLIGHTER->Icons.Default.BorderColor;InkTool.ERASER->Icons.Default.AutoFixNormal;InkTool.LASSO->Icons.Default.Gesture;else->Icons.Default.PanTool},names.getValue(t))
                        }
                    }
                    IconButton({showLayers=true},enabled=!busy) {Icon(Icons.Default.Layers,"레이어")}
                    IconButton({moreTools=true},enabled=!busy) {Icon(Icons.Default.MoreHoriz,"추가 필기 도구")}
                }
                if(!settings.toolbarCollapsed) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    listOf(0xFF1A1A1A.toInt(),0xFFBC4949.toInt(),0xFF315FBB.toInt()).forEach {v->
                        val c=Color(v)
                        Box(Modifier.size(40.dp).padding(7.dp).background(c,androidx.compose.foundation.shape.CircleShape).border(if(c==color) 3.dp else 1.dp,if(c==color) MaterialTheme.colorScheme.primary else Color.LightGray,androidx.compose.foundation.shape.CircleShape).clickable(enabled=!busy){vm.settings(settings.copy(penColor=v))}.semantics {contentDescription="필기 색상 "+Integer.toHexString(v)})
                    }
                    IconButton({customColor=true},enabled=!busy) {Icon(Icons.Default.Palette,"펜 설정")}
                    Text(if(tool==InkTool.ERASER) "크기" else "굵기",style=MaterialTheme.typography.labelSmall)
                    Slider(if(tool==InkTool.ERASER) settings.eraserRadius else line,{if(tool==InkTool.ERASER) vm.settings(settings.copy(eraserRadius=it)) else vm.settings(settings.copy(penWidth=it))},valueRange=if(tool==InkTool.ERASER) 0.005f..0.08f else 0.002f..0.025f,modifier=Modifier.width(88.dp),enabled=!busy)
                    TextButton({moreTools=true},enabled=!busy) {Text((zoom*100).toInt().toString()+"%")}
                }
                if(tool==InkTool.ERASER) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(!settings.partialEraser,{vm.settings(settings.copy(partialEraser=false))},label={Text("획 전체")},enabled=!busy)
                    FilterChip(settings.partialEraser,{vm.settings(settings.copy(partialEraser=true))},label={Text("지나간 부분만")},enabled=!busy)
                }
                if(selected.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=8.dp)) {
                    Text(selected.size.toString()+"획 선택",modifier=Modifier.padding(12.dp));TextButton({transform=true},enabled=!busy) {Text("이동·크기·회전")};TextButton({vm.transformInk(selected,duplicate=true,projection=projection)},enabled=!busy) {Text("복사")};TextButton({vm.erase(selected);selected=emptySet()},enabled=!busy) {Text("삭제")};TextButton({selected=emptySet()}) {Text("선택 해제")}
                }
            }
        }
    }
    if(moreTools) AlertDialog(onDismissRequest={moreTools=false},title={Text("필기 도구")},text={Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState())) {
        InkTool.entries.chunked(3).forEach {row->Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {row.forEach {t->FilterChip(tool==t,{vm.settings(settings.copy(penTool=t.name));moreTools=false},label={Text(names.getValue(t))},enabled=!busy)}}}
        if(s.format!=Format.PDF) {
            Text("종이",style=MaterialTheme.typography.titleSmall,modifier=Modifier.padding(top=12.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)) {Paper.entries.forEach {p->FilterChip(s.edits.paper==p,{vm.paper(p)},label={Text(when(p) {Paper.BLANK->"무지";Paper.LINED->"줄노트";Paper.GRID->"격자";Paper.DOTS->"점노트"})},enabled=!busy)}}
        }
        Text("확대 "+(zoom*100).toInt()+"%",modifier=Modifier.padding(top=12.dp))
        Slider(zoom,{zoom=it},valueRange=1f..5f,enabled=!busy)
        Row(verticalAlignment=Alignment.CenterVertically) {Text("필기 표시",Modifier.weight(1f));Switch(visible,{visible=it},enabled=!busy)}
        TextButton({moreTools=false;customColor=true},enabled=!busy) {Text("색상·필압·보정·프리셋 설정")}
        TextButton({vm.settings(settings.copy(toolbarTop=!settings.toolbarTop))}) {Text(if(settings.toolbarTop) "도구 아래로" else "도구 위로")}
        TextButton({vm.settings(settings.copy(toolbarCollapsed=!settings.toolbarCollapsed))}) {Text(if(settings.toolbarCollapsed) "필기 도구 펼치기" else "필기 도구 접기")}
        TextButton({vm.erase(s.edits.strokes.filter {it.anchor==anchor}.map {it.id}.toSet());moreTools=false},enabled=!busy) {Text("페이지 필기 전체 지우기")}
    }},confirmButton={TextButton({moreTools=false}) {Text("닫기")}})
    Column(Modifier.fillMaxSize()) {
        if(sheet) {
            Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                OutlinedTextField(startCell,{startCell=it.uppercase().take(10)},label={Text("필기 시작 셀")},singleLine=true,modifier=Modifier.width(120.dp))
                TextButton({if(validAddress(startCell)) {firstRow=startCell.dropWhile(Char::isLetter).toInt();firstColumn=column(startCell)} else vm.message("올바른 셀 주소를 입력하세요.")},enabled=!busy) {Text("셀로 이동")}
                Checkbox(bindCell,{bindCell=it},enabled=!busy);Text("셀 연결",style=MaterialTheme.typography.labelSmall)
            }
            Text(gridError ?: "필기를 시작한 셀에 연결 · 행/열 삭제 시 해당 필기도 삭제 · 실행 취소 가능",style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(horizontal=12.dp))
        }
        if(settings.toolbarTop) toolbar()
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(Color(0xFFE5E5DF))) {
            val pageWidth=minOf((maxWidth-24.dp).coerceAtLeast(1.dp),((maxHeight-24.dp)*ratio).coerceAtLeast(1.dp))*zoom
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
                    } else if(sheet) AndroidView(factory={SheetInkPage(it)},update={it.grid=grid;it.cells=cellValues;it.invalidate()},modifier=Modifier.fillMaxSize())
                    else if(slide!=null) AndroidView(factory={SlideView(it)},update={it.scene=slide;it.editable=false;it.invalidate()},modifier=Modifier.fillMaxSize())
                    else AndroidView(factory={context -> NoteView(context)},update={it.text=text;it.pageRatio=ratio;it.paper=s.edits.paper;it.invalidate()},modifier=Modifier.fillMaxSize())
                    if(visible && (s.format!=Format.PDF || image!=null)) AndroidView(factory={context -> InkView(context)},update={v ->
                        v.strokes=visibleStrokes;v.anchor=anchor;v.tool=tool;v.inkColor=color.toArgb();v.lineWidth=line
                        v.opacity=settings.penOpacity;v.pressureCurve=settings.pressureCurve;v.smoothing=settings.smoothing;v.autoSnap=settings.autoSnap;v.layerId=activeLayer;v.selected=selected;v.onSelect={selected=it}
                        v.onLasso={polygon->val snapshot=visibleStrokes.filter {it.layerId in InkLayers.editable(s.edits)};scope.launch {selected=withContext(Dispatchers.Default) {InkGeometry.select(snapshot,anchor,polygon)}}}
                        v.onZoom={zoom=(zoom*it).coerceIn(1f,5f)};v.onPan={dx,dy->scope.launch {horizontal.scrollTo((horizontal.value-dx).toInt());vertical.scrollTo((vertical.value-dy).toInt())}}
                        v.stylusOnly=settings.stylusOnly;v.onStroke={stroke ->
                            if(sheet && bindCell) {
                                val address=grid?.addressAt(stroke.points.first());val frame=address?.let {grid?.frame(it)}
                                if(address!=null && frame!=null) vm.addStroke(stroke.copy(cell=CellInkBinding(address,frame))) else vm.message("표의 셀 안에서 필기를 시작하세요.")
                            } else vm.addStroke(stroke)
                        };v.eraserRadius=settings.eraserRadius
                        v.onEraseGesture={gesture,radius,aspect->vm.eraseInk(anchor,gesture,radius,aspect,settings.partialEraser,projection)};v.isEnabled=!busy && (!sheet || grid!=null) && activeLayer in InkLayers.editable(s.edits);v.invalidate()
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
        }},confirmButton={TextButton({val numbers=listOf(x,y,scale,rotation).map {it.toFloatOrNull()};if(numbers.all {it!=null && it.isFinite()}) {vm.transformInk(selected,numbers[0]!!/100,numbers[1]!!/100,numbers[2]!!,numbers[3]!!,projection=projection);transform=false} else vm.message("숫자를 입력하세요.")}) {Text("적용")}},dismissButton={TextButton({transform=false}) {Text("취소")}})
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
