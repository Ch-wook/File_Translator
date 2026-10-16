package kr.documentstudio

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kr.documentstudio.core.*
import kr.documentstudio.data.StrokePainter
import kr.documentstudio.ui.InkView
import org.junit.Assert.*
import org.junit.Test

class InkInputTest {
    @Test fun batchedStylusTiltIsCapturedAndCompletedStrokeOwnsItsBuffer() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context=ApplicationProvider.getApplicationContext<Context>();val view=InkView(context)
            FrameLayout(context).addView(view);view.layout(0,0,1000,1000)
            val strokes=mutableListOf<Stroke>();view.onStroke={strokes.add(it)}
            val pointer=MotionEvent.PointerProperties().apply {id=0;toolType=MotionEvent.TOOL_TYPE_STYLUS}
            fun coords(x: Float,tilt: Float)=MotionEvent.PointerCoords().apply {this.x=x;this.y=x;pressure=0.8f;size=0.1f;setAxisValue(MotionEvent.AXIS_TILT,tilt)}
            fun event(action: Int,time: Long,x: Float,tilt: Float)=MotionEvent.obtain(1,time,action,1,arrayOf(pointer),arrayOf(coords(x,tilt)),0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_STYLUS,0)
            fun send(e: MotionEvent) {try {assertTrue(view.onTouchEvent(e))} finally {e.recycle()}}
            send(event(MotionEvent.ACTION_DOWN,1,100f,0.1f))
            val move=event(MotionEvent.ACTION_MOVE,2,200f,0.2f);move.addBatch(3,arrayOf(coords(600f,0.6f)),0);send(move)
            send(event(MotionEvent.ACTION_UP,4,900f,0.9f))
            assertEquals(1,strokes.size);val first=strokes.first().points.toList()
            assertEquals(listOf(0.1f,0.2f,0.6f,0.9f),first.map {it.tilt})
            send(event(MotionEvent.ACTION_DOWN,5,300f,0.3f));send(event(MotionEvent.ACTION_UP,6,400f,0.4f))
            assertEquals(first,strokes.first().points);assertEquals(2,strokes.size)
        }
    }
    @Test fun reusablePainterPreservesPressureOpacityDotsAndToolState() {
        val painter=StrokePainter();val bitmap=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap)
        try {
            canvas.drawColor(Color.WHITE)
            val line=Stroke(anchor="text",tool=InkTool.BRUSH,color=Color.BLUE,width=0.05f,points=listOf(InkPoint(0.1f,0.25f,0.2f),InkPoint(0.9f,0.25f,1.5f)))
            painter.draw(canvas,line,200f,200f)
            painter.draw(canvas,line.copy(tool=InkTool.HIGHLIGHTER,color=Color.RED,points=listOf(InkPoint(0.1f,0.5f),InkPoint(0.9f,0.5f))),200f,200f)
            painter.draw(canvas,line.copy(tool=InkTool.PEN,color=Color.BLACK,points=listOf(InkPoint(0.5f,0.75f))),200f,200f)
            assertEquals(Color.BLUE,bitmap.getPixel(100,50));assertEquals(Color.BLACK,bitmap.getPixel(100,150))
            val highlight=bitmap.getPixel(100,100);assertEquals(255,Color.red(highlight));assertTrue(Color.green(highlight) in 170..200)
            painter.draw(canvas,line.copy(color=Color.GREEN),200f,200f);assertEquals(Color.GREEN,bitmap.getPixel(100,50))
        } finally {bitmap.recycle()}
    }
}
