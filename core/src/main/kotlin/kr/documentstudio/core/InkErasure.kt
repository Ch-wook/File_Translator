package kr.documentstudio.core

import java.util.UUID
import kotlin.math.*

/** Clips stroke centre lines against a swept circular eraser, in page-width units.
 * The stroke radius is included so round caps do not bridge the erased area.
 * A gesture is atomic: limits throw before a caller replaces its original strokes. */
object InkErasure {
    private const val EPS = 1e-9
    private data class P(val x: Double, val y: Double) {
        operator fun minus(other: P) = P(x-other.x,y-other.y)
        fun dot(other: P) = x*other.x+y*other.y
    }
    private data class Interval(val lo: Double,val hi: Double)

    fun erase(strokes: List<Stroke>, anchor: String, gesture: List<InkPoint>, radius: Float,
              pageAspect: Float, partial: Boolean): List<Stroke> {
        require(radius.isFinite() && radius in 0.001f..0.2f && pageAspect.isFinite() && pageAspect in 0.05f..20f)
        require(gesture.size<=10000 && gesture.all {it.x.isFinite() && it.y.isFinite()})
        if(gesture.isEmpty()) return strokes
        fun position(p: InkPoint) = P(p.x.toDouble(),p.y.toDouble()*pageAspect)
        val trace=gesture.map(::position).distinctUntilChanged()
        val sweep=if(trace.size==1) listOf(trace[0] to trace[0]) else trace.zipWithNext()
        val minX=trace.minOf {it.x};val maxX=trace.maxOf {it.x}
        val minY=trace.minOf {it.y};val maxY=trace.maxOf {it.y}
        val output=ArrayList<Stroke>();var work=0;var pointCount=0
        val strokeLimit=maxOf(4000,strokes.size)
        fun keep(s: Stroke) {
            output.add(s);pointCount+=s.points.size
            require(output.size<=strokeLimit && pointCount<=200000) {"지운 뒤 필기 용량 제한을 넘습니다. 더 작은 구간을 지워 주세요."}
        }
        for(stroke in strokes) {
            if(stroke.anchor!=anchor || stroke.points.isEmpty()) {keep(stroke);continue}
            val isShape=stroke.tool in setOf(InkTool.LINE,InkTool.ARROW,InkTool.RECTANGLE,InkTool.ELLIPSE)
            // Shapes render at a constant width. Their flattened fragments must
            // retain that width when represented as ordinary open pen paths.
            val path=InkGeometry.path(stroke).let {points -> if(isShape) points.map {it.copy(pressure=1f,tilt=0f)} else points}
            val positions=path.map(::position)
            val response=when(stroke.tool) {
                InkTool.BRUSH -> 0.2+path.maxOf {it.pressure.toDouble().coerceIn(0.1,2.0).pow(2)}
                InkTool.FOUNTAIN -> 0.3+path.maxOf {it.pressure.toDouble().coerceIn(0.1,2.0)}*1.3
                InkTool.PENCIL -> 0.6+path.maxOf {it.pressure.toDouble().coerceIn(0.1,2.0)}*0.3
                InkTool.PEN -> 0.5+path.maxOf {it.pressure.toDouble().coerceIn(0.1,2.0)}/2
                else -> 1.0
            }
            val reach=radius+stroke.width*response/2
            if(positions.maxOf {it.x}<minX-reach || positions.minOf {it.x}>maxX+reach ||
                positions.maxOf {it.y}<minY-reach || positions.minOf {it.y}>maxY+reach) {keep(stroke);continue}
            fun cuts(a: P,b: P): List<Interval> {
                val intervals=ArrayList<Interval>()
                for((c,d) in sweep) {
                    require(++work<=8_000_000) {"지우기 작업이 너무 큽니다. 더 짧게 나누어 지워 주세요."}
                    if(max(a.x,b.x)<min(c.x,d.x)-reach || min(a.x,b.x)>max(c.x,d.x)+reach ||
                        max(a.y,b.y)<min(c.y,d.y)-reach || min(a.y,b.y)>max(c.y,d.y)+reach) continue
                    intervals.addAll(capsule(a,b,c,d,reach))
                }
                val sorted=intervals.sortedBy {it.lo};val merged=ArrayList<Interval>()
                for(i in sorted) {
                    val last=merged.lastOrNull()
                    if(last!=null && i.lo<=last.hi+EPS) merged[merged.lastIndex]=Interval(last.lo,max(last.hi,i.hi))
                    else merged.add(i)
                }
                return merged
            }
            if(path.size==1) {
                if(cuts(positions[0],positions[0]).isEmpty()) keep(stroke)
                continue
            }
            val fragments=ArrayList<List<InkPoint>>();var current=ArrayList<InkPoint>();var changed=false
            fun finish() {if(current.isNotEmpty()) fragments.add(current);current=ArrayList()}
            for(i in 0 until path.lastIndex) {
                val intervals=cuts(positions[i],positions[i+1])
                if(intervals.isNotEmpty()) changed=true
                if(!partial && changed) break
                var start=0.0
                fun append(from: Double,to: Double) {
                    if(to-from<=EPS) return
                    val a=interpolate(path[i],path[i+1],from);val b=interpolate(path[i],path[i+1],to)
                    if(current.isEmpty() || current.last()!=a) current.add(a)
                    current.add(b)
                }
                for(interval in intervals) {append(start,interval.lo);finish();start=interval.hi}
                append(start,1.0)
            }
            finish()
            if(!changed) keep(stroke)
            else if(partial) fragments.forEachIndexed {i,points ->
                keep(stroke.copy(id=if(i==0) stroke.id else UUID.randomUUID().toString(),
                    tool=if(isShape) InkTool.PEN else stroke.tool,
                    points=points))
            }
        }
        return output
    }

    private fun interpolate(a: InkPoint,b: InkPoint,t: Double): InkPoint {
        if(t<=0) return a
        if(t>=1) return b
        fun mix(x: Float,y: Float)=(x+(y-x)*t).toFloat()
        return InkPoint(mix(a.x,b.x),mix(a.y,b.y),mix(a.pressure,b.pressure),mix(a.tilt,b.tilt))
    }
    private fun circle(a: P,b: P,c: P,r: Double): Interval? {
        val v=b-a;val offset=a-c;val aa=v.dot(v);val cc=offset.dot(offset)-r*r
        if(aa<EPS*EPS) return if(cc<=0) Interval(0.0,1.0) else null
        val bb=2*offset.dot(v);val discriminant=bb*bb-4*aa*cc
        if(discriminant<=0) return null
        val root=sqrt(discriminant);val lo=max(0.0,(-bb-root)/(2*aa));val hi=min(1.0,(-bb+root)/(2*aa))
        return if(hi-lo>EPS) Interval(lo,hi) else null
    }
    private fun capsule(a: P,b: P,c: P,d: P,r: Double): List<Interval> {
        val result=ArrayList<Interval>()
        circle(a,b,c,r)?.let(result::add)
        val axis=d-c;val length=sqrt(axis.dot(axis))
        if(length<EPS) return result
        circle(a,b,d,r)?.let(result::add)
        val unit=P(axis.x/length,axis.y/length);val normal=P(-unit.y,unit.x)
        val offset=a-c;val delta=b-a
        var lo=0.0;var hi=1.0
        fun bound(value: Double,slope: Double,lower: Double,upper: Double): Boolean {
            if(abs(slope)<EPS) return value>=lower && value<=upper
            val p=(lower-value)/slope;val q=(upper-value)/slope
            lo=max(lo,min(p,q));hi=min(hi,max(p,q));return hi-lo>EPS
        }
        if(bound(offset.dot(unit),delta.dot(unit),0.0,length) && bound(offset.dot(normal),delta.dot(normal),-r,r)) result.add(Interval(lo,hi))
        return result
    }

    private fun <T> List<T>.distinctUntilChanged(): List<T> = filterIndexed {i,value -> i==0 || value!=this[i-1]}
}
