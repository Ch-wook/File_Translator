package kr.documentstudio

import android.text.Layout
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kr.documentstudio.data.SlideScene
import kr.documentstudio.data.SlideShape
import kr.documentstudio.ui.SlideView
import org.junit.Assert.*
import org.junit.Test

class SlideObjectGestureTest {
    private fun main(block: ()->Unit)=InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    @Test fun selectDragResizeRotateAndCancelOperateOnTheObject() {
        ActivityScenario.launch(MainActivity::class.java).use {activity->
            lateinit var view: SlideView
            val updates=mutableListOf<SlideShape>()
            val shape=SlideShape("42",100f,200f,200f,120f,0f,"rect",0xff2858a5.toInt(),"",Layout.Alignment.ALIGN_NORMAL)
            main {activity.onActivity {host->
                view=SlideView(host).apply {
                    scene=SlideScene(1000f,1000f,listOf(shape),0);editable=true
                    onTransform={s->updates+=s;scene=scene!!.copy(shapes=listOf(s))}
                }
                host.setContentView(FrameLayout(host).apply {addView(view,FrameLayout.LayoutParams(1000,1000))})
                view.layout(0,0,1000,1000)
            }}
            fun touch(action: Int,x: Float,y: Float) {main {val now=android.os.SystemClock.uptimeMillis();val e=MotionEvent.obtain(now,now,action,x,y,0);view.onTouchEvent(e);e.recycle()}}
            fun tap(x: Float,y: Float) {touch(MotionEvent.ACTION_DOWN,x,y);touch(MotionEvent.ACTION_UP,x,y)}
            tap(200f,260f);assertEquals("42",view.selectedId)
            touch(MotionEvent.ACTION_DOWN,200f,260f);touch(MotionEvent.ACTION_MOVE,260f,300f);touch(MotionEvent.ACTION_UP,260f,300f)
            assertEquals(1,updates.size);assertEquals(160f,updates.last().x,0.1f);assertEquals(240f,updates.last().y,0.1f)
            touch(MotionEvent.ACTION_DOWN,360f,360f);touch(MotionEvent.ACTION_MOVE,440f,420f);touch(MotionEvent.ACTION_UP,440f,420f)
            assertEquals(2,updates.size);assertEquals(280f,updates.last().width,0.1f);assertEquals(180f,updates.last().height,0.1f)
            val before=updates.last()
            touch(MotionEvent.ACTION_DOWN,300f,330f);touch(MotionEvent.ACTION_MOVE,400f,430f);touch(MotionEvent.ACTION_CANCEL,400f,430f)
            assertEquals(2,updates.size);assertEquals(before,view.scene!!.shapes.single())
            val rotationY=before.y-26f*view.resources.displayMetrics.density
            touch(MotionEvent.ACTION_DOWN,before.x+before.width/2,rotationY)
            touch(MotionEvent.ACTION_MOVE,before.x+before.width+100f,before.y+before.height/2)
            touch(MotionEvent.ACTION_UP,before.x+before.width+100f,before.y+before.height/2)
            assertEquals(3,updates.size);assertEquals(90f,updates.last().rotation,0.1f)
        }
    }
}
