package kr.documentstudio.core

import kotlin.math.*
import java.util.UUID

object InkGeometry {
    fun path(s: Stroke): List<InkPoint> {
        if(s.points.isEmpty()) return emptyList()
        val a=s.points.first();val b=s.points.last()
        return when(s.tool) {
            InkTool.LINE -> listOf(a,b)
            InkTool.ARROW -> {
                val angle=atan2(b.y-a.y,b.x-a.x);val size=max(s.width*4,0.025f)
                listOf(a,b,InkPoint((b.x-size*cos(angle-0.5f)).coerceIn(0f,1f),(b.y-size*sin(angle-0.5f)).coerceIn(0f,1f)),b,InkPoint((b.x-size*cos(angle+0.5f)).coerceIn(0f,1f),(b.y-size*sin(angle+0.5f)).coerceIn(0f,1f)))
            }
            InkTool.RECTANGLE -> listOf(a,InkPoint(b.x,a.y),b,InkPoint(a.x,b.y),a)
            InkTool.ELLIPSE -> (0..60).map {i->val theta=i*Math.PI*2/60;InkPoint((a.x+b.x)/2+(b.x-a.x)/2*cos(theta).toFloat(),(a.y+b.y)/2+(b.y-a.y)/2*sin(theta).toFloat())}
            else -> s.points
        }
    }
    private fun inside(p: InkPoint,polygon: List<InkPoint>): Boolean {
        var result=false;var j=polygon.lastIndex
        for(i in polygon.indices) {val a=polygon[i];val b=polygon[j];if((a.y>p.y)!=(b.y>p.y) && p.x<(b.x-a.x)*(p.y-a.y)/(b.y-a.y)+a.x) result=!result;j=i};return result
    }
    private fun cross(a: InkPoint,b: InkPoint,c: InkPoint)=(b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x)
    private fun intersect(a: InkPoint,b: InkPoint,c: InkPoint,d: InkPoint): Boolean {
        if(max(a.x,b.x)<min(c.x,d.x) || max(c.x,d.x)<min(a.x,b.x) || max(a.y,b.y)<min(c.y,d.y) || max(c.y,d.y)<min(a.y,b.y)) return false
        return cross(a,b,c)*cross(a,b,d)<=0 && cross(c,d,a)*cross(c,d,b)<=0
    }
    fun select(strokes: List<Stroke>,anchor: String,polygon: List<InkPoint>): Set<String> {
        if(polygon.size<3) return emptySet();require(polygon.size<=10000)
        val stride=(polygon.size/256).coerceAtLeast(1);val outline=polygon.filterIndexed {i,_->i%stride==0 || i==polygon.lastIndex}
        val edges=(outline+outline.first()).zipWithNext()
        val bounds=listOf(outline.minOf {it.x},outline.minOf {it.y},outline.maxOf {it.x},outline.maxOf {it.y})
        return strokes.filter {it.anchor==anchor}.filter {s->
            val pts=path(s);if(pts.isEmpty() || pts.maxOf {it.x}<bounds[0] || pts.minOf {it.x}>bounds[2] || pts.maxOf {it.y}<bounds[1] || pts.minOf {it.y}>bounds[3]) false
            else pts.any {inside(it,outline)} || pts.zipWithNext().any {(a,b)->edges.any {(c,d)->intersect(a,b,c,d)}}
        }.map {it.id}.toSet()
    }
    fun transform(strokes: List<Stroke>,ids: Set<String>,dx: Float=0f,dy: Float=0f,scale: Float=1f,degrees: Float=0f,duplicate: Boolean=false): List<Stroke> {
        require(scale in 0.1f..10f && listOf(dx,dy,scale,degrees).all {it.isFinite()})
        val selected=strokes.filter {it.id in ids};val all=selected.flatMap(::path);if(all.isEmpty()) return strokes
        val cx=(all.minOf {it.x}+all.maxOf {it.x})/2;val cy=(all.minOf {it.y}+all.maxOf {it.y})/2;val angle=degrees*PI.toFloat()/180
        val transformed=selected.map {s->
            val pts=if(degrees!=0f && s.tool in listOf(InkTool.RECTANGLE,InkTool.ELLIPSE,InkTool.ARROW)) path(s) else s.points
            s.copy(id=if(duplicate) UUID.randomUUID().toString() else s.id,tool=if(pts!==s.points) InkTool.PEN else s.tool,width=(s.width*scale).coerceIn(0.0001f,0.1f),points=pts.map {p->val x=(p.x-cx)*scale;val y=(p.y-cy)*scale;p.copy(x=cx+x*cos(angle)-y*sin(angle)+dx,y=cy+x*sin(angle)+y*cos(angle)+dy)})
        }
        require(transformed.flatMap {it.points}.all {it.x in 0f..1f && it.y in 0f..1f}) {"변형한 필기가 페이지를 벗어납니다. 이동·크기 값을 줄여 주세요."}
        return if(duplicate) strokes+transformed else strokes.map {s->transformed.firstOrNull {it.id==s.id} ?: s}
    }
}
