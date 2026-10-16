package kr.documentstudio.core

import org.junit.Assert.*
import org.junit.Test

class InkAdvancedTest {
    private fun stroke(points: List<InkPoint>)=Stroke(anchor="text",tool=InkTool.PEN,color=-16777216,width=0.004f,points=points)
    @Test fun smoothingKeepsRawSamplesAndEraseUsesVisiblePath() {
        val samples=listOf(InkPoint(.1f,.2f,.2f,.3f),InkPoint(.3f,.4f,.6f,.7f),InkPoint(.5f,.2f,1.5f,.9f))
        val s=stroke(samples).copy(smoothing=1f,pressureCurve=2f)
        val path=InkGeometry.path(s)
        assertEquals(.3f,path[1].y,.00001f);assertSame(samples,s.points)
        assertEquals(samples[1].pressure,path[1].pressure);assertEquals(samples[1].tilt,path[1].tilt)
        assertEquals(.25f,InkGeometry.pressure(.5f,2f),.00001f)
        val fragments=InkErasure.erase(listOf(s),"text",listOf(InkPoint(.3f,.3f)),.01f,1f,true)
        assertEquals(2,fragments.size);assertTrue(fragments.all {it.smoothing==0f && it.pressureCurve==2f})
    }
    @Test fun snapRecognizesShapesWithoutReplacingCapturedSamples() {
        val line=stroke((0..20).map {InkPoint(.1f+it*.02f,.2f+(it%2)*.001f,it*.05f,.3f)})
        assertEquals(InkTool.LINE,InkGeometry.snap(line).tool);assertSame(line.points,InkGeometry.snap(line).points)
        val square=stroke(listOf(InkPoint(.2f,.2f),InkPoint(.4f,.2f),InkPoint(.4f,.4f),InkPoint(.2f,.4f),InkPoint(.2f,.2f)))
        val snapped=InkGeometry.snap(square);assertEquals(InkTool.RECTANGLE,snapped.tool);assertTrue(snapped.snapped);assertSame(square.points,snapped.points)
        assertEquals(InkPoint(.4f,.4f),InkGeometry.path(snapped)[2])
        assertEquals(InkTool.PEN,InkGeometry.snap(stroke(listOf(InkPoint(.1f,.1f),InkPoint(.9f,.5f),InkPoint(.1f,.9f),InkPoint(.5f,.1f)))).tool)
    }
    @Test fun hiddenAndLockedLayersRetainDataAndRejectOrphans() {
        val layers=listOf(InkLayer(),InkLayer("review","검토",false,true))
        val a=stroke(listOf(InkPoint(.1f,.1f)));val b=a.copy(id="second",layerId="review")
        val e=Edits(strokes=listOf(b,a),layers=layers)
        assertEquals(listOf(a),InkLayers.ordered(e));assertEquals(listOf(a,b),InkLayers.ordered(e,true));assertEquals(setOf("default"),InkLayers.editable(e))
        assertThrows(IllegalArgumentException::class.java) {InkLayers.update(e,listOf(InkLayer()))}
        assertThrows(IllegalArgumentException::class.java) {InkLayers.update(e,listOf(InkLayer(),InkLayer()))}
    }
}
