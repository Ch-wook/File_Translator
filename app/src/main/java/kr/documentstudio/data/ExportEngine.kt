package kr.documentstudio.data

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kr.documentstudio.core.*
import java.io.File

object ExportEngine {
    fun blank(destination: File) {
        val doc=PdfDocument()
        try { val page=doc.startPage(PdfDocument.PageInfo.Builder(595,842,1).create()); page.canvas.drawColor(Color.WHITE); doc.finishPage(page); destination.outputStream().use { doc.writeTo(it) } } finally {doc.close()}
    }
    fun document(s: Session,destination: File) {
        if(s.format==Format.PPTX) {SlideEngine.export(s,destination);PdfEngine.inspect(destination);return}
        val sections = if(s.sections.isNotEmpty()) s.sections.map { section ->
            val content = if(s.format in listOf(Format.XLSX,Format.XLS)) {
                val cells=section.cells + s.edits.values.filterKeys { it.startsWith(section.id+"#") }.mapKeys { it.key.substringAfter('#') }
                val formula=s.calculator(section)
                cells.entries.sortedWith(compareBy({ it.key.dropWhile { c -> c.isLetter() }.toIntOrNull() ?: 0 },{ column(it.key) })).joinToString("\n") { "${it.key}    ${CellFormatting.format(formula.display(it.key),(s.edits.styles[section.id+"#"+it.key] ?: section.cellStyles[it.key])?.get("numberFormat"),section.date1904)}" }
            } else section.fields.joinToString("\n") { s.edits.values[it.id] ?: it.text }
            Triple(section.title,content,section.id)
        } else listOf(Triple(s.name,if(s.format==Format.MD) MarkdownRender.render(s.edits.text) else s.edits.text,"text"))
        val pdf=PdfDocument()
        try {
            var number=0
            sections.forEach { (title,content,anchor) ->
                val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK; textSize=12f }
                val layout=StaticLayout.Builder.obtain(content,0,content.length,paint,511).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(4f,1f).build()
                val height=layout.height.coerceAtLeast(1)
                var offset=0
                while(offset<height) {
                    val page=pdf.startPage(PdfDocument.PageInfo.Builder(595,842,++number).create())
                    page.canvas.drawColor(Color.WHITE)
                    paint.textSize=10f; page.canvas.drawText(title,42f,30f,paint); paint.textSize=12f
                    page.canvas.save(); page.canvas.clipRect(42f,48f,553f,796f); page.canvas.translate(42f,48f-offset); layout.draw(page.canvas); page.canvas.restore()
                    // Semantic text/cell export: coordinates refer to the fixed section note page, not Office pagination.
                    if(offset==0) s.edits.strokes.filter { it.anchor==anchor }.forEach { drawStroke(page.canvas,it,595f,if(s.format==Format.PPTX) 595f*9/16 else 842f) }
                    pdf.finishPage(page); offset+=748
                }
            }
            destination.outputStream().use { pdf.writeTo(it) }
        } finally {pdf.close()}
        PdfEngine.inspect(destination)
    }
}

fun drawStroke(canvas: android.graphics.Canvas,s: Stroke,w: Float,h: Float) {
    val points=shapePoints(s); if(points.isEmpty()) return
    val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=s.color; alpha=(s.opacity*if(s.tool==InkTool.HIGHLIGHTER) 72 else if(s.tool==InkTool.PENCIL) 150 else 255).toInt().coerceIn(0,255); style=Paint.Style.STROKE; strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND; strokeWidth=(s.width*w).coerceAtLeast(0.5f) }
    if(points.size==1) { canvas.drawCircle(points[0].x*w,points[0].y*h,paint.strokeWidth/2,Paint(paint).apply { style=Paint.Style.FILL }); return }
    if(s.tool in listOf(InkTool.PEN,InkTool.FOUNTAIN,InkTool.PENCIL,InkTool.BRUSH)) points.zipWithNext().forEach { (a,b) ->
        val pressure=(a.pressure+b.pressure)/2
        val response=when(s.tool) {InkTool.FOUNTAIN->0.3f+pressure*(1f+0.3f*kotlin.math.sin(a.tilt));InkTool.BRUSH->0.2f+pressure*pressure;InkTool.PENCIL->0.6f+pressure*0.3f;else->0.5f+pressure/2}
        paint.strokeWidth=(s.width*w*response).coerceAtLeast(0.5f)
        canvas.drawLine(a.x*w,a.y*h,b.x*w,b.y*h,paint)
    } else {
        val path=Path().apply {moveTo(points[0].x*w,points[0].y*h);points.drop(1).forEach {lineTo(it.x*w,it.y*h)}}
        canvas.drawPath(path,paint)
    }
}
