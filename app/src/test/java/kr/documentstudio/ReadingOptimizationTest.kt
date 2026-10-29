package kr.documentstudio

import kr.documentstudio.data.PdfRasterBudget
import kr.documentstudio.ui.LineNumberEditText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=StudioApplication::class)
class ReadingOptimizationTest {
    @Test fun rasterKeepsPageAspectAndNeverExceedsItsPixelBudget() {
        for((w,h) in listOf(595 to 842,842 to 595,100000 to 1,1 to 100000))for(request in listOf(128,1024,4096))for(budget in listOf(65536,2000000,4000000)) {
            val (x,y)=PdfRasterBudget.dimensions(w,h,request,budget)
            assertTrue(x>0 && y>0);assertTrue(x<=4096 && y<=4096)
            assertTrue(x.toLong()*y<=budget)
            if(x>1 && y>1)assertTrue(kotlin.math.abs(x.toDouble()/y-w.toDouble()/h)<w.toDouble()/h/y+1.0/y)
        }
        assertTrue(PdfRasterBudget.dimensions(595,842,1024).first<PdfRasterBudget.dimensions(595,842,2048).first)
    }
    @Test fun rasterBucketsRejectInvalidZoomAndStayBounded() {
        assertEquals(1024,PdfRasterBudget.requestedWidth(1000,1f))
        assertEquals(2048,PdfRasterBudget.requestedWidth(1000,2f))
        assertEquals(4096,PdfRasterBudget.requestedWidth(10000,3f))
        for(zoom in listOf(Float.NaN,Float.POSITIVE_INFINITY,0f,-1f))assertThrows(IllegalArgumentException::class.java){PdfRasterBudget.requestedWidth(1000,zoom)}
    }
    @Test fun logicalLineNumbersSurvivePartialNewlineInsertionDeletionAndReplacement() {
        val view=LineNumberEditText(RuntimeEnvironment.getApplication())
        fun check(expected: String) {
            assertEquals(expected,view.text.toString())
            val starts=listOf(0)+expected.indices.filter {expected[it]=='\n'}.map {it+1}
            for(i in 0..expected.length)assertEquals(if(i in starts)starts.indexOf(i)+1 else null,view.logicalLineNumberAt(i))
        }
        view.setText("가\n나\n다");check("가\n나\n다")
        view.text.insert(2,"앞\n");check("가\n앞\n나\n다")
        view.text.delete(1,4);check("가나\n다")
        view.text.replace(1,2,"\n가나다\n");check("가\n가나다\n\n다")
        view.setText("");check("")
    }
}
