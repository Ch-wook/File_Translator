package kr.documentstudio.ui
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.widget.EditText

// The Compose host uses a platform theme; AppCompat is used only by the isolated Office activity.
@android.annotation.SuppressLint("AppCompatCustomView")
class LineNumberEditText(context: Context): EditText(context) {
    var showLineNumbers: Boolean=true
    var wrapLines: Boolean=true
    var darkPage: Boolean=false
    var onSelectionUpdate: ((Int,Int)->Unit)?=null
    override fun onSelectionChanged(start: Int,end: Int) {
        super.onSelectionChanged(start,end)
        if(start>=0 && end>=0)onSelectionUpdate?.invoke(start,end)
    }
    private var logicalStarts: IntArray?=null
    private val numberPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=android.graphics.Color.GRAY;textAlign=Paint.Align.RIGHT}
    override fun onTextChanged(text: CharSequence?,start: Int,lengthBefore: Int,lengthAfter: Int) {
        super.onTextChanged(text,start,lengthBefore,lengthAfter)
        if(text!=null) {
            val previous=logicalStarts ?: intArrayOf(0)
            val inserted=ArrayList<Int>()
            for(i in start until (start+lengthAfter).coerceAtMost(text.length)) if(text[i]=='\n') inserted.add(i+1)
            logicalStarts=(previous.takeWhile {it<=start}+inserted+previous.dropWhile {it<=start+lengthBefore}.map {it+lengthAfter-lengthBefore}).toIntArray()
        }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas);if(!showLineNumbers)return;val current=layout ?: return;numberPaint.textSize=textSize*0.75f
        for(line in current.getLineForVertical(scrollY)..current.getLineForVertical(scrollY+height)) {
            val logical=(logicalStarts ?: intArrayOf(0)).binarySearch(current.getLineStart(line))
            if(logical>=0) canvas.drawText((logical+1).toString(),scrollX+paddingLeft-10f,current.getLineBaseline(line)+totalPaddingTop.toFloat(),numberPaint)
        }
    }
}
