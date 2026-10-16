package kr.documentstudio.core

import org.junit.Assert.*
import org.junit.Test

class InkErasureTest {
    private val line=Stroke(id="original",anchor="pdf:0",tool=InkTool.BRUSH,color=0xff315fbb.toInt(),width=0.004f,
        opacity=0.7f,points=listOf(InkPoint(0.1f,0.5f,0.5f,0.2f),InkPoint(0.9f,0.5f,1.5f,0.8f)))
    private fun erase(strokes: List<Stroke> = listOf(line),trace: List<InkPoint> = listOf(InkPoint(0.5f,0.5f)),
                      aspect: Float=1f,partial: Boolean=true) = InkErasure.erase(strokes,"pdf:0",trace,0.04f,aspect,partial)

    @Test fun sparseStrokeSplitsWithInterpolatedPressureAndTilt() {
        val pieces=erase()
        assertEquals(2,pieces.size);assertEquals(line.id,pieces[0].id);assertNotEquals(line.id,pieces[1].id)
        assertEquals(line.points.first(),pieces[0].points.first());assertEquals(line.points.last(),pieces[1].points.last())
        val a=pieces[0].points.last();val b=pieces[1].points.first()
        assertTrue(a.x<0.46f && b.x>0.54f)
        assertEquals(0.5f+(a.x-0.1f)/0.8f,a.pressure,0.00001f)
        assertEquals(0.2f+(b.x-0.1f)/0.8f*0.6f,b.tilt,0.00001f)
        pieces.forEach {assertEquals(line.tool,it.tool);assertEquals(line.width,it.width);assertEquals(line.color,it.color);assertEquals(line.opacity,it.opacity);assertEquals(line.anchor,it.anchor)}
    }
    @Test fun fastEraserSweepCutsBetweenItsTwoSamples() {
        val trace=listOf(InkPoint(0.5f,0.1f),InkPoint(0.5f,0.9f))
        assertEquals(2,erase(trace=trace).size)
        assertTrue(erase(trace=trace,partial=false).isEmpty())
    }
    @Test fun pageAspectKeepsCircularEraserInDisplayCoordinates() {
        val below=line.copy(tool=InkTool.PEN,points=listOf(InkPoint(0.1f,0.53f),InkPoint(0.9f,0.53f)))
        assertEquals(2,erase(listOf(below),aspect=1f).size)
        assertSame(below,erase(listOf(below),aspect=2f).single())
    }
    @Test fun wholeStrokeAndUnrelatedAnchorsStayIndependent() {
        val other=line.copy(id="other",anchor="pdf:1")
        val result=erase(listOf(line,other),partial=false)
        assertEquals(listOf(other),result);assertSame(other,result.single())
        assertSame(line,erase(trace=listOf(InkPoint(0f,0f))).single())
    }
    @Test fun endpointsDotsAndCompleteCoverageDoNotLeavePhantomFragments() {
        val dot=line.copy(points=listOf(InkPoint(0.5f,0.5f)))
        assertTrue(erase(listOf(dot)).isEmpty())
        val end=erase(trace=listOf(InkPoint(0.1f,0.5f))).single()
        assertTrue(end.points.first().x>0.14f);assertEquals(line.points.last(),end.points.last())
        assertTrue(erase(trace=listOf(InkPoint(0f,0.5f),InkPoint(1f,0.5f))).isEmpty())
    }
    @Test fun disjointCutsCreateThreePiecesWithoutReconnecting() {
        val trace=listOf(InkPoint(0.3f,0.5f),InkPoint(0.3f,0.8f),InkPoint(0.7f,0.8f),InkPoint(0.7f,0.5f))
        val pieces=erase(trace=trace)
        assertEquals(3,pieces.size);assertEquals(3,pieces.map {it.id}.toSet().size)
        assertTrue(pieces[0].points.last().x<0.3f)
        assertTrue(pieces[1].points.first().x>0.3f && pieces[1].points.last().x<0.7f)
        assertTrue(pieces[2].points.first().x>0.7f)
    }
    @Test fun cutShapesBecomeOpenPathsAndUncutShapesKeepTheirType() {
        val rectangle=line.copy(tool=InkTool.RECTANGLE,points=listOf(InkPoint(0.2f,0.2f,0.1f),InkPoint(0.8f,0.8f,2f)))
        val pieces=erase(listOf(rectangle),listOf(InkPoint(0.5f,0.2f)))
        assertEquals(2,pieces.size);assertTrue(pieces.all {it.tool==InkTool.PEN})
        assertTrue(pieces.flatMap {it.points}.all {it.pressure==1f && it.tilt==0f})
        assertSame(rectangle,erase(listOf(rectangle)).single())
    }
    @Test fun emptyGesturesAndLimitsPreserveOriginalData() {
        val original=listOf(line)
        assertSame(original,erase(original,emptyList()))
        assertThrows(IllegalArgumentException::class.java) {InkErasure.erase(original,"pdf:0",listOf(InkPoint(Float.NaN,0f)),0.04f,1f,true)}
        assertThrows(IllegalArgumentException::class.java) {InkErasure.erase(original,"pdf:0",List(10001) {InkPoint(0.5f,0.5f)},0.04f,1f,true)}
        assertEquals(2,line.points.size)
    }
}
