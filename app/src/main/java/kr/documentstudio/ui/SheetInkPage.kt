package kr.documentstudio.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kr.documentstudio.core.*

class SheetInkPage(context: Context): View(context) {
    var grid: SheetInkGrid?=null
    var cells: Map<String,String> = emptyMap()
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas);val grid=grid ?: return
        kr.documentstudio.data.drawSheetInk(canvas,grid,cells,width.toFloat(),height.toFloat())
    }
}
