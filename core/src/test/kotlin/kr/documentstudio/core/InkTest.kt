package kr.documentstudio.core

import org.junit.Test
import org.junit.Assert.*

class InkTest {
    private val stroke=Stroke(anchor="page",tool=InkTool.PEN,color=0xff000000.toInt(),width=0.004f,points=listOf(InkPoint(0.2f,0.3f,0.5f),InkPoint(0.4f,0.3f,1f)))
    @Test fun lassoSelectsCrossingSegmentsAndHonorsAnchor() {
        val polygon=listOf(InkPoint(0.25f,0.25f),InkPoint(0.35f,0.25f),InkPoint(0.35f,0.35f),InkPoint(0.25f,0.35f))
        assertEquals(setOf(stroke.id),InkGeometry.select(listOf(stroke),"page",polygon));assertTrue(InkGeometry.select(listOf(stroke),"other",polygon).isEmpty())
    }
    @Test fun rotationCopyPressureAndBounds() {
        val result=InkGeometry.transform(listOf(stroke),setOf(stroke.id),dx=0.1f,scale=2f,degrees=90f,duplicate=true)
        assertEquals(2,result.size);assertNotEquals(stroke.id,result.last().id);assertEquals(0.5f,result.last().points[0].pressure);assertEquals(0.4f,result.last().points[0].x,0.001f);assertEquals(0.1f,result.last().points[0].y,0.001f)
        assertThrows(IllegalArgumentException::class.java) {InkGeometry.transform(listOf(stroke),setOf(stroke.id),dx=1f)}
    }
}
