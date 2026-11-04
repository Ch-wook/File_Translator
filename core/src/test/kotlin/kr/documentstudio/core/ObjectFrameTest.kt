package kr.documentstudio.core

import org.junit.Assert.*
import org.junit.Test

class ObjectFrameTest {
    @Test fun rotatedHitTestUsesTheVisibleObject() {
        val f=ObjectFrame(100f,100f,200f,40f,90f)
        assertTrue(f.contains(200f,200f));assertFalse(f.contains(110f,110f))
    }
    @Test fun resizingBottomRightKeepsOppositeCornerAndMinimumSize() {
        val f=ObjectFrame(10f,20f,100f,80f)
        val larger=f.resize(160f,140f,false,false,12f)
        assertEquals(10f,larger.x,0.001f);assertEquals(20f,larger.y,0.001f)
        assertEquals(150f,larger.width,0.001f);assertEquals(120f,larger.height,0.001f)
        val smaller=f.resize(0f,0f,false,false,12f)
        assertEquals(12f,smaller.width,0.001f);assertEquals(12f,smaller.height,0.001f)
    }
    @Test fun rotatedResizeKeepsTheOppositeCornerInDocumentCoordinates() {
        val f=ObjectFrame(100f,100f,100f,60f,90f)
        // Top-left is (180,80). Bottom-right starts at (120,180).
        val next=f.resize(100f,220f,false,false,12f)
        assertEquals(140f,next.width,0.001f);assertEquals(80f,next.height,0.001f)
        val (x,y)=next.local(180f,80f)
        assertEquals(0f,x,0.001f);assertEquals(0f,y,0.001f)
    }
}
