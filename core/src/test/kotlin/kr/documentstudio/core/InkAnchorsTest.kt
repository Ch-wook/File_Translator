package kr.documentstudio.core

import org.junit.Assert.*
import org.junit.Test

class InkAnchorsTest {
    private val grid=SheetInkGrid((1..6).map {InkMeasure(it,64f)},(1..12).map {InkMeasure(it,20f)})
    private fun stroke(a: String="B3"): Stroke {val f=grid.frame(a)!!;return Stroke(anchor="sheet",tool=InkTool.PEN,color=-1,width=.004f,points=listOf(InkPoint(f.x+f.width*.2f,f.y+f.height*.3f,.5f,.2f),InkPoint(f.x+f.width*.7f,f.y+f.height*.8f,1.5f,.7f)),cell=CellInkBinding(a,f))}
    @Test fun projectionTracksMeasuredCellsAndViewportWithoutMutatingRawPoints() {
        val s=stroke();val wider=grid.copy(columns=grid.columns.map {if(it.index==2) it.copy(extent=128f) else it},rows=grid.rows.map {if(it.index==3) it.copy(extent=60f) else it})
        val p=InkProjection("sheet",wider);val display=p.display(s)!!;val frame=wider.frame("B3")!!
        assertEquals(frame.x+frame.width*.2f,display.points[0].x,.00001f);assertEquals(frame.y+frame.height*.3f,display.points[0].y,.00001f)
        val back=p.stored(display);assertEquals(s.width,back.width,.00001f);s.points.zip(back.points).forEach {(a,b)->assertEquals(a.x,b.x,.00001f);assertEquals(a.y,b.y,.00001f);assertEquals(a.pressure,b.pressure);assertEquals(a.tilt,b.tilt)}
        assertEquals("B3",wider.addressAt(display.points.first()));assertNull(InkProjection("sheet",grid.copy(rows=(20..31).map {InkMeasure(it,20f)})).display(s))
    }
    @Test fun structuralEditsMoveOnlyOwnedCellAnchorsAndDeleteAtomically() {
        val original=stroke();val foreign=original.copy(anchor="other");val fixed=original.copy(cell=null)
        val inserted=InkAnchors.change(listOf(original,foreign,fixed),DocumentChange("sheet.insertRows","sheet",mapOf("index" to "2","count" to "3")))
        assertEquals("B6",inserted[0].cell!!.address);assertEquals("B3",inserted[1].cell!!.address);assertSame(original.points,inserted[0].points)
        val shifted=InkAnchors.change(inserted,DocumentChange("sheet.insertColumns","sheet",mapOf("index" to "2","count" to "1")));assertEquals("C6",shifted[0].cell!!.address)
        val removed=InkAnchors.change(shifted,DocumentChange("sheet.deleteRows","sheet",mapOf("index" to "6","count" to "1")));assertEquals(listOf(foreign,fixed),removed)
    }
    @Test fun erasingProjectedStrokeRestoresCellCoordinatesAndStyle() {
        val s=stroke();val shifted=grid.copy(columns=grid.columns.map {it.copy(extent=if(it.index==2) 128f else 64f)})
        val projection=InkProjection("sheet",shifted);val shown=projection.display(s)!!
        val p=InkPoint((shown.points[0].x+shown.points[1].x)/2,(shown.points[0].y+shown.points[1].y)/2)
        val pieces=InkErasure.erase(listOf(shown),"sheet",listOf(p),.005f,1f,true).map {projection.stored(it)}
        assertEquals(2,pieces.size);assertTrue(pieces.all {it.cell==s.cell && it.points.all {p->p.x in 0f..1f && p.y in 0f..1f}})
    }
}
