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
import kr.documentstudio.EditorViewModel
import kr.documentstudio.core.*
import kr.documentstudio.data.*

@Composable
fun rememberSlide(s: Session,sec: Section?): SlideScene? {
    val scene by produceState<SlideScene?>(null,s.base,sec?.id,s.edits.values,s.edits.styles) {
        var loaded: SlideScene?=null
        try {if(sec!=null) {loaded=withContext(Dispatchers.IO) {SlideEngine.load(s,sec)};value=loaded};awaitDispose {loaded?.recycle()}}
        catch(e: CancellationException) {loaded?.recycle();throw e}
        catch(_: Exception) {value=null;loaded?.recycle()}
    };return scene
}
class SlideView(context: Context): View(context) {
    var scene: SlideScene?=null
    var strokes: List<Stroke> = emptyList()
    var editable=false
    var onMove: (SlideShape,Float,Float)->Unit={_,_,_->}
    private var selected: SlideShape?=null;private var x=0f;private var y=0f;private var dx=0f;private var dy=0f
    init {contentDescription="슬라이드 기본 개체 미리보기"}
    override fun onDraw(canvas: Canvas) {
        // Ink uses the same normalized slide coordinates as the preview and PDF export.
        if(strokes.isNotEmpty()) {scene?.let {SlideEngine.draw(canvas,it,width.toFloat(),height.toFloat())};strokes.forEach {drawStroke(canvas,it,width.toFloat(),height.toFloat())};return}
        scene?.let {s->SlideEngine.draw(canvas,s,width.toFloat(),height.toFloat());selected?.let {shape->val paint=Paint().apply {color=android.graphics.Color.rgb(40,120,95);style=Paint.Style.STROKE;strokeWidth=2f};canvas.drawRect((shape.x+dx)*width/s.width,(shape.y+dy)*height/s.height,(shape.x+shape.width+dx)*width/s.width,(shape.y+shape.height+dy)*height/s.height,paint)}}
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(!editable) return false;val s=scene ?: return false
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {x=event.x*s.width/width;y=event.y*s.height/height;selected=s.shapes.lastOrNull {x in it.x..it.x+it.width && y in it.y..it.y+it.height};dx=0f;dy=0f;if(selected==null) return false;parent.requestDisallowInterceptTouchEvent(true);invalidate();return true}
            MotionEvent.ACTION_MOVE -> {dx=event.x*s.width/width-x;dy=event.y*s.height/height-y;invalidate();return selected!=null}
            MotionEvent.ACTION_UP -> {selected?.let {if(kotlin.math.abs(dx)+kotlin.math.abs(dy)>1f) onMove(it,it.x+dx,it.y+dy)};selected=null;parent.requestDisallowInterceptTouchEvent(false);invalidate();performClick();return true}
            MotionEvent.ACTION_CANCEL -> {selected=null;parent.requestDisallowInterceptTouchEvent(false);invalidate();return true}
        };return selected!=null
    }
    override fun performClick(): Boolean {super.performClick();return true}
}
@Composable
fun SlidePreview(s: Session,sec: Section,vm: EditorViewModel,busy: Boolean) {
    val scene=rememberSlide(s,sec)
    var show by remember {mutableStateOf(false)}
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("기본 텍스트·그림·도형 미리보기 · 마스터/테마/SmartArt/그룹 제외",style=MaterialTheme.typography.labelSmall)
        scene?.let {model->
            Text("직접 개체 ${model.shapes.size} · 제외/근사 ${model.omitted} · 개체를 끌어 이동",style=MaterialTheme.typography.labelSmall)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds(),contentAlignment=Alignment.Center) {
                val w=minOf(maxWidth,maxHeight*(model.width/model.height))
                AndroidView(factory={SlideView(it)},update={view->view.scene=model;view.editable=!busy;view.onMove={shape,x,y->vm.change(DocumentChange("slide.transform",sec.id,mapOf("shapeId" to shape.id,"x" to (x*2.54/72).toString(),"y" to (y*2.54/72).toString(),"width" to (shape.width*2.54/72).toString(),"height" to (shape.height*2.54/72).toString(),"rotation" to shape.rotation.toString())))};view.invalidate()},modifier=Modifier.width(w).aspectRatio(model.width/model.height))
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
