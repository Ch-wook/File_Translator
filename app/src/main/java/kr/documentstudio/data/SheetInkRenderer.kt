package kr.documentstudio.data

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kr.documentstudio.core.*

fun drawSheetInk(canvas: Canvas,grid: SheetInkGrid,cells: Map<String,String>,w: Float,h: Float) {
    val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        paint.textSize=w/50f
        for(row in grid.rows) for(col in grid.columns) {
            val address=columnName(col.index)+row.index;val r=grid.frame(address)!!
            val left=r.x*w;val top=r.y*h;val right=(r.x+r.width)*w;val bottom=(r.y+r.height)*h
            paint.color=0xFFD8DEDA.toInt();paint.style=Paint.Style.STROKE;paint.strokeWidth=w/1200f;canvas.drawRect(left,top,right,bottom,paint)
            paint.style=Paint.Style.FILL;paint.color=Color.BLACK
            canvas.save();canvas.clipRect(left+2,top,right-2,bottom);canvas.drawText(cells[address].orEmpty().take(100),left+w/200f,top+minOf(paint.textSize*1.3f,(bottom-top)*.8f),paint);canvas.restore()
            paint.color=0xFF61756A.toInt()
            if(row==grid.rows.first()) canvas.drawText(columnName(col.index),left+4,h*.04f,paint)
            if(col==grid.columns.first()) canvas.drawText(row.index.toString(),w*.01f,top+minOf(paint.textSize*1.3f,(bottom-top)*.8f),paint)
        }
}
