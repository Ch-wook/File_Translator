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
    var documentReadOnly: Boolean=true
    var onSelectionUpdate: ((Int,Int)->Unit)?=null
    override fun onCreateInputConnection(info: android.view.inputmethod.EditorInfo): android.view.inputmethod.InputConnection? =
        if(documentReadOnly) null else super.onCreateInputConnection(info)
    override fun onTextContextMenuItem(id: Int): Boolean {
        if(documentReadOnly && id in listOf(android.R.id.cut,android.R.id.paste,android.R.id.pasteAsPlainText))return false
        return super.onTextContextMenuItem(id)
    }
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
            fun upperBound(value: Int): Int {var lo=0;var hi=previous.size;while(lo<hi){val mid=(lo+hi) ushr 1;if(previous[mid]<=value)lo=mid+1 else hi=mid};return lo}
            val prefix=upperBound(start);val suffix=upperBound(start+lengthBefore)
            val next=IntArray(prefix+inserted.size+previous.size-suffix)
            System.arraycopy(previous,0,next,0,prefix)
            for(i in inserted.indices)next[prefix+i]=inserted[i]
            val delta=lengthAfter-lengthBefore
            for(i in suffix until previous.size)next[prefix+inserted.size+i-suffix]=previous[i]+delta
            logicalStarts=next
        }
    }
    internal fun logicalLineNumberAt(offset: Int): Int? = (logicalStarts ?: intArrayOf(0)).binarySearch(offset).takeIf {it>=0}?.plus(1)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas);if(!showLineNumbers)return;val current=layout ?: return;numberPaint.textSize=textSize*0.75f
        for(line in current.getLineForVertical(scrollY)..current.getLineForVertical(scrollY+height)) {
            val logical=logicalLineNumberAt(current.getLineStart(line))
            if(logical!=null) canvas.drawText(logical.toString(),scrollX+paddingLeft-10f,current.getLineBaseline(line)+totalPaddingTop.toFloat(),numberPaint)
        }
    }
}
