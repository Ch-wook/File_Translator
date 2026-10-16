package kr.documentstudio.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.DashPathEffect
import android.view.ScaleGestureDetector
import android.view.MotionEvent
import android.view.View
import kr.documentstudio.core.*
import kr.documentstudio.data.drawStroke
import kr.documentstudio.data.shapePoints
import kotlin.math.hypot

/** All points and widths are in a fixed page/section coordinate system, independent of screen pixels. */
class InkView(context: Context): View(context) {
    var strokes: List<Stroke> = emptyList()
    var anchor: String="text"
    var tool=InkTool.PEN
    var inkColor=Color.rgb(40,65,58)
    var lineWidth=0.004f
    var opacity=1f
    var stylusOnly=false
    var onStroke: (Stroke)->Unit={}
    var eraserRadius=0.025f
    var onEraseGesture: (List<InkPoint>,Float,Float)->Unit={_,_,_->}
    var selected: Set<String> = emptySet()
    var onSelect: (Set<String>)->Unit={}
    var onLasso: (List<InkPoint>)->Unit={}
    var onZoom: (Float)->Unit={}
    var onPan: (Float,Float)->Unit={_,_->}
    private var activeId=-1
    private val points=mutableListOf<InkPoint>()
    private var strokeTool=InkTool.PEN
    private var preview: Stroke?=null
    private var navigation=false
    private var lastX=0f;private var lastY=0f
    private var hover: InkPoint?=null
    private var strokeId=""
    private val scaleDetector=ScaleGestureDetector(context,object: ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {onZoom(detector.scaleFactor);return true}
    })
    init { setLayerType(LAYER_TYPE_HARDWARE,null); contentDescription="필기 페이지" }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        strokes.filter { it.anchor==anchor }.forEach { drawStroke(canvas,it,width.toFloat(),height.toFloat()) }
        preview?.let { drawStroke(canvas,it,width.toFloat(),height.toFloat()) }
        val selectionPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(35,125,100);style=Paint.Style.STROKE;strokeWidth=2f;pathEffect=DashPathEffect(floatArrayOf(8f,5f),0f)}
        strokes.filter {it.anchor==anchor && it.id in selected}.forEach {s->val pts=shapePoints(s);if(pts.isNotEmpty()) canvas.drawRect(pts.minOf {it.x}*width-4,pts.minOf {it.y}*height-4,pts.maxOf {it.x}*width+4,pts.maxOf {it.y}*height+4,selectionPaint)}
        if(strokeTool==InkTool.LASSO && points.isNotEmpty()) canvas.drawPath(Path().apply {moveTo(points[0].x*width,points[0].y*height);points.drop(1).forEach {lineTo(it.x*width,it.y*height)};close()},selectionPaint)
        if(strokeTool==InkTool.ERASER && points.isNotEmpty()) {
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=0x5590A4AE;style=Paint.Style.STROKE;strokeWidth=eraserRadius*width*2;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
            if(points.size==1) canvas.drawCircle(points[0].x*width,points[0].y*height,eraserRadius*width,paint.apply {style=Paint.Style.FILL})
            else canvas.drawPath(Path().apply {moveTo(points[0].x*width,points[0].y*height);points.drop(1).forEach {lineTo(it.x*width,it.y*height)}},paint)
        }
        hover?.let {p->canvas.drawCircle(p.x*width,p.y*height,(if(tool==InkTool.ERASER) eraserRadius*width else lineWidth*width/2).coerceAtLeast(4f),selectionPaint)}
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(!isEnabled) return false
        val index=event.actionIndex
        val primaryStylus=activeId!=-1 && event.findPointerIndex(activeId).let {it>=0 && event.getToolType(it) in listOf(MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.TOOL_TYPE_ERASER)}
        if(!primaryStylus) scaleDetector.onTouchEvent(event)
        if(event.actionMasked==MotionEvent.ACTION_POINTER_DOWN && event.pointerCount>=2 && !primaryStylus) {
            activeId=-1;points.clear();preview=null;navigation=true;parent.requestDisallowInterceptTouchEvent(true);invalidate();return true
        }
        if(navigation) {
            if(event.actionMasked==MotionEvent.ACTION_MOVE && !scaleDetector.isInProgress && event.pointerCount==1) {onPan(event.x-lastX,event.y-lastY)}
            lastX=event.x;lastY=event.y
            if(event.actionMasked in listOf(MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL)) {navigation=false;clear()}
            return true
        }
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN,MotionEvent.ACTION_POINTER_DOWN -> {
                if(activeId!=-1) return true
                val type=event.getToolType(index)
                if(tool==InkTool.HAND || stylusOnly && type !in listOf(MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.TOOL_TYPE_ERASER,MotionEvent.TOOL_TYPE_MOUSE)) {
                    navigation=true;lastX=event.x;lastY=event.y;parent.requestDisallowInterceptTouchEvent(true);return true
                }
                if(event.flags and MotionEvent.FLAG_CANCELED != 0) return false
                activeId=event.getPointerId(index)
                strokeTool=if(type==MotionEvent.TOOL_TYPE_ERASER || event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0) InkTool.ERASER else tool
                strokeId=java.util.UUID.randomUUID().toString()
                points.clear(); parent.requestDisallowInterceptTouchEvent(true); sample(event,index); return true
            }
            MotionEvent.ACTION_MOVE -> {
                if(activeId==-1) return false
                val i=event.findPointerIndex(activeId); if(i<0) return true
                for(h in 0 until event.historySize) sample(event,i,h)
                sample(event,i); return true
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_POINTER_UP -> {
                if(event.getPointerId(index)!=activeId) return activeId!=-1
                if(event.flags and MotionEvent.FLAG_CANCELED == 0) {
                    sample(event,index)
                    if(strokeTool==InkTool.ERASER) onEraseGesture(points.toList(),eraserRadius,height.toFloat()/width.coerceAtLeast(1)) else if(strokeTool==InkTool.LASSO) onLasso(points.toList()) else preview?.let(onStroke)
                }
                clear(); performClick(); return true
            }
            MotionEvent.ACTION_CANCEL -> { clear(); return true }
        }
        return activeId!=-1
    }
    private fun sample(e: MotionEvent,i: Int,history: Int=-1) {
        if(width==0 || height==0) return
        val x=if(history<0) e.getX(i) else e.getHistoricalX(i,history)
        val y=if(history<0) e.getY(i) else e.getHistoricalY(i,history)
        val pressure=if(history<0) e.getPressure(i) else e.getHistoricalPressure(i,history)
        val point=InkPoint((x/width).coerceIn(0f,1f),(y/height).coerceIn(0f,1f),pressure.coerceIn(0.1f,2f),e.getAxisValue(MotionEvent.AXIS_TILT,i))
        if(points.size<10000 && (points.isEmpty() || hypot(x-points.last().x*width,y-points.last().y*height)>0.5)) points.add(point)
        preview=if(strokeTool in listOf(InkTool.LASSO,InkTool.ERASER)) null else Stroke(id=strokeId,anchor=anchor,tool=strokeTool,color=inkColor,width=lineWidth,points=points.toList(),opacity=opacity)
        invalidate()
    }
    private fun clear() { activeId=-1; points.clear(); preview=null; parent.requestDisallowInterceptTouchEvent(false); invalidate() }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onHoverEvent(event: MotionEvent): Boolean {
        hover=if(event.actionMasked==MotionEvent.ACTION_HOVER_EXIT) null else InkPoint(event.x/width.coerceAtLeast(1),event.y/height.coerceAtLeast(1));invalidate();return true
    }
}
