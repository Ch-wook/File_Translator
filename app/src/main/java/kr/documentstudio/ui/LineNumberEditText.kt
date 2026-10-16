package kr.documentstudio.ui
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.widget.EditText

// The Compose host uses a platform theme; AppCompat is used only by the isolated Office activity.
@android.annotation.SuppressLint("AppCompatCustomView")
class LineNumberEditText(context: Context): EditText(context) {
    private var logicalStarts=intArrayOf(0)
    private val numberPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=android.graphics.Color.GRAY;textAlign=Paint.Align.RIGHT}
    override fun onTextChanged(text: CharSequence?,start: Int,lengthBefore: Int,lengthAfter: Int) {
        super.onTextChanged(text,start,lengthBefore,lengthAfter)
        if(text!=null) logicalStarts=(listOf(0)+text.indices.filter {text[it]=='\n'}.map {it+1}).toIntArray()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas);val current=layout ?: return;numberPaint.textSize=textSize*0.75f
        for(line in current.getLineForVertical(scrollY)..current.getLineForVertical(scrollY+height)) {
            val logical=logicalStarts.binarySearch(current.getLineStart(line))
            if(logical>=0) canvas.drawText((logical+1).toString(),scrollX+paddingLeft-10f,current.getLineBaseline(line)+totalPaddingTop.toFloat(),numberPaint)
        }
    }
}
