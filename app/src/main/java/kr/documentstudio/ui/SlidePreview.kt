package kr.documentstudio.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.MotionEvent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*
import kr.documentstudio.DocumentActions
import kr.documentstudio.core.*
import kr.documentstudio.data.*

@Composable
fun rememberSlide(s: Session,sec: Section?): SlideScene? {
    val scene by key(s.base,sec?.id,s.edits.values,s.edits.styles) {produceState<SlideScene?>(null) {
        var loaded: SlideScene?=null
        try {if(sec!=null) {withContext(Dispatchers.IO) {loaded=SlideEngine.load(s,sec)};value=loaded};awaitDispose {loaded?.recycle()}}
        catch(e: CancellationException) {loaded?.recycle();throw e}
        catch(_: Exception) {value=null;loaded?.recycle()}
    }};return scene
}
class SlideView(context: Context): View(context) {
    var scene: SlideScene?=null
        set(value) {if(field!==value) {preview=null;original=null};field=value}
    var strokes: List<Stroke> = emptyList()
    var editable=false
    var onTransform: (SlideShape)->Unit={}
    var onSelect: (SlideShape?)->Unit={}
    var selectedId: String?=null
    private var original: SlideShape?=null
    private var preview: SlideShape?=null
    private var downX=0f;private var downY=0f
    private var corner: Pair<Boolean,Boolean>?=null
    private var rotating=false
    private var changed=false
    private val strokePainter=StrokePainter()
    private val selectionPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=android.graphics.Color.rgb(40,88,165);style=Paint.Style.STROKE;strokeWidth=2f*resources.displayMetrics.density}
    private val handlePaint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=android.graphics.Color.WHITE}
    init {contentDescription="슬라이드 기본 개체 미리보기";isFocusable=true}
    private fun frame(s: SlideShape)=ObjectFrame(s.x,s.y,s.width,s.height,s.rotation)
    private fun SlideShape.withFrame(f: ObjectFrame)=copy(x=f.x,y=f.y,width=f.width,height=f.height,rotation=f.rotation)
    override fun onDraw(canvas: Canvas) {
        val model=scene ?: return
        val shown=preview?.let {p->model.copy(shapes=model.shapes.map {if(it.id==p.id) p else it})} ?: model
        SlideEngine.draw(canvas,shown,width.toFloat(),height.toFloat())
        strokes.forEach {strokePainter.draw(canvas,it,width.toFloat(),height.toFloat())}
        if(!editable) return
        val shape=preview ?: model.shapes.firstOrNull {it.id==selectedId} ?: return
        val sx=width/model.width;val sy=height/model.height
        canvas.save();canvas.scale(sx,sy);canvas.rotate(shape.rotation,shape.x+shape.width/2,shape.y+shape.height/2)
        selectionPaint.strokeWidth=2f*resources.displayMetrics.density/sx
        val radius=5f*resources.displayMetrics.density/sx
        canvas.drawRect(shape.x,shape.y,shape.x+shape.width,shape.y+shape.height,selectionPaint)
        listOf(shape.x to shape.y,shape.x+shape.width to shape.y,shape.x to shape.y+shape.height,shape.x+shape.width to shape.y+shape.height).forEach {(x,y)->canvas.drawCircle(x,y,radius,handlePaint);canvas.drawCircle(x,y,radius,selectionPaint)}
        val rotateY=shape.y-26f*resources.displayMetrics.density/sy
        canvas.drawLine(shape.x+shape.width/2,shape.y,shape.x+shape.width/2,rotateY,selectionPaint)
        canvas.drawCircle(shape.x+shape.width/2,rotateY,radius,handlePaint);canvas.drawCircle(shape.x+shape.width/2,rotateY,radius,selectionPaint)
        canvas.restore()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(!editable || width==0 || height==0) return false
        val model=scene ?: return false
        val px=event.x*model.width/width;val py=event.y*model.height/height
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val active=model.shapes.firstOrNull {it.id==selectedId}
                val radius=18f*resources.displayMetrics.density*model.width/width
                corner=null;rotating=false;changed=false
                if(active!=null) {
                    val (u,v)=frame(active).local(px,py)
                    corner=listOf(true to true,false to true,true to false,false to false).firstOrNull {(left,top)->kotlin.math.hypot(u-(if(left) 0f else active.width),v-(if(top) 0f else active.height))<radius}
                    rotating=kotlin.math.hypot(u-active.width/2,v+26f*resources.displayMetrics.density*model.height/height)<radius
                }
                val hit=if(corner!=null || rotating) active else model.shapes.lastOrNull {frame(it).contains(px,py)}
                selectedId=hit?.id;original=hit;preview=null;downX=px;downY=py;onSelect(hit)
                if(hit==null) {invalidate();return false}
                parent.requestDisallowInterceptTouchEvent(true);invalidate();return true
            }
            MotionEvent.ACTION_MOVE -> {
                val start=original ?: return false
                if(kotlin.math.hypot(px-downX,py-downY)<3f*resources.displayMetrics.density*model.width/width && !changed) return true
                changed=true
                val next=when {
                    rotating->frame(start).copy(rotation=Math.toDegrees(kotlin.math.atan2((py-start.y-start.height/2).toDouble(),(px-start.x-start.width/2).toDouble())).toFloat()+90f)
                    corner!=null->frame(start).resize(px,py,corner!!.first,corner!!.second,12f*resources.displayMetrics.density*model.width/width)
                    else->frame(start).copy(x=(start.x+px-downX).coerceIn(0f,(model.width-start.width).coerceAtLeast(0f)),y=(start.y+py-downY).coerceIn(0f,(model.height-start.height).coerceAtLeast(0f)))
                }
                preview=start.withFrame(next);invalidate();return true
            }
            MotionEvent.ACTION_UP -> {
                if(changed) preview?.let {onTransform(it);onSelect(it)}
                original=null;parent.requestDisallowInterceptTouchEvent(false);invalidate();performClick();return true
            }
            MotionEvent.ACTION_CANCEL -> {original=null;preview=null;changed=false;parent.requestDisallowInterceptTouchEvent(false);invalidate();return true}
        };return original!=null
    }
    override fun performClick(): Boolean {super.performClick();return true}
}
@Composable
fun SlidePreview(s: Session,sec: Section,vm: DocumentActions,busy: Boolean) {
    val scene=rememberSlide(s,sec)
    var show by remember {mutableStateOf(false)}
    var selected by remember(sec.id) {mutableStateOf<SlideShape?>(null)}
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("기본 텍스트·그림·도형 미리보기 · 마스터/테마/SmartArt/그룹 제외",style=MaterialTheme.typography.labelSmall)
        scene?.let {model->
            Text(if(selected==null) "개체를 눌러 선택 · 끌어서 이동" else "모서리로 크기 변경 · 위쪽 핸들로 회전",style=MaterialTheme.typography.labelSmall)
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text(selected?.let {"개체 ${it.id}"} ?: "${model.shapes.size}개 개체",style=MaterialTheme.typography.labelMedium,modifier=Modifier.weight(1f))
                TextButton({selected?.let {vm.change(DocumentChange("slide.objectOrder",sec.id,mapOf("shapeId" to it.id,"index" to (model.shapes.size-1).toString()))) }},enabled=!busy && selected!=null) {Text("맨 앞으로")}
                TextButton({selected?.let {vm.change(DocumentChange("slide.deleteObject",sec.id,mapOf("shapeId" to it.id)));selected=null}},enabled=!busy && selected!=null) {Text("삭제")}
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds(),contentAlignment=Alignment.Center) {
                val w=minOf(maxWidth,maxHeight*(model.width/model.height))
                AndroidView(factory={SlideView(it)},update={view->view.scene=model;view.selectedId=selected?.id;view.editable=!busy;view.onSelect={selected=it};view.onTransform={shape->vm.change(DocumentChange("slide.transform",sec.id,mapOf("shapeId" to shape.id,"x" to (shape.x*2.54/72).toString(),"y" to (shape.y*2.54/72).toString(),"width" to (shape.width*2.54/72).toString(),"height" to (shape.height*2.54/72).toString(),"rotation" to shape.rotation.toString())))};view.invalidate()},modifier=Modifier.width(w).aspectRatio(model.width/model.height))
            }
            Button({show=true},enabled=!busy) {Text("슬라이드 쇼")}
        } ?: Text("이 슬라이드는 기본 개체 미리보기로 표시할 수 없습니다.")
    }
    if(show) Dialog({show=false},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        var index by remember {mutableIntStateOf(s.sections.indexOf(sec))};val model=rememberSlide(s,s.sections.getOrNull(index))
        Surface(Modifier.fillMaxSize()) {Column(Modifier.fillMaxSize(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
            model?.let {m->BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) {val w=minOf(maxWidth,maxHeight*(m.width/m.height));AndroidView(factory={SlideView(it)},update={it.scene=m;it.strokes=s.edits.strokes.filter {stroke->stroke.anchor==s.sections[index].id};it.editable=false;it.invalidate()},modifier=Modifier.width(w).aspectRatio(m.width/m.height))}}
            Row {TextButton({index--},enabled=index>0) {Text("이전")};Text("${index+1}/${s.sections.size}",modifier=Modifier.padding(16.dp));TextButton({index++},enabled=index<s.sections.size-1) {Text("다음")};TextButton({show=false}) {Text("종료")}}
        }}
    }
}
