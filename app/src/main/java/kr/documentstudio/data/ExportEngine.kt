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
                    drawPaper(page.canvas,s.edits.paper,595f,842f)
                    paint.textSize=10f; page.canvas.drawText(title,42f,30f,paint); paint.textSize=12f
                    page.canvas.save(); page.canvas.clipRect(42f,48f,553f,796f); page.canvas.translate(42f,48f-offset); layout.draw(page.canvas); page.canvas.restore()
                    // Semantic text/cell export: coordinates refer to the fixed section note page, not Office pagination.
                    if(offset==0) InkLayers.ordered(s.edits).filter { it.anchor==anchor && it.cell==null }.forEach { drawStroke(page.canvas,it,595f,if(s.format==Format.PPTX) 595f*9/16 else 842f) }
                    pdf.finishPage(page); offset+=748
                }
                if(s.format in listOf(Format.XLSX,Format.XLS)) {
                    val section=s.sections.first {it.id==anchor}
                    val ink=InkLayers.ordered(s.edits).filter {it.anchor==anchor && it.cell!=null}
                    val windows=ink.map {stroke->val a=stroke.cell!!.address;((a.dropWhile(Char::isLetter).toInt()-1)/12*12+1) to ((column(a)-1)/6*6+1)}.distinct()
                    require(windows.size<=100 && number+windows.size<=2000) {"셀 필기 PDF 페이지 제한을 초과했습니다. 범위를 나누어 주세요."}
                    val formula=s.calculator(section)
                    for((row,col) in windows) {
                        val grid=SheetInkMeasurements.read(s,section,row,col);val projection=InkProjection(anchor,grid)
                        val values=grid.rows.flatMap {r->grid.columns.map {c->columnName(c.index)+r.index}}.associateWith {formula.display(it)}
                        val page=pdf.startPage(PdfDocument.PageInfo.Builder(595,842,++number).create())
                        page.canvas.drawColor(Color.WHITE);paint.textSize=10f;page.canvas.drawText("$title · 셀 필기 ${columnName(col)}$row",20f,24f,paint)
                        page.canvas.save();page.canvas.translate(0f,36f);drawSheetInk(page.canvas,grid,values,595f,790f)
                        ink.mapNotNull {projection.display(it)}.forEach {drawStroke(page.canvas,it,595f,790f)}
                        page.canvas.restore();pdf.finishPage(page)
                    }
                }
            }
            destination.outputStream().use { pdf.writeTo(it) }
        } finally {pdf.close()}
        PdfEngine.inspect(destination)
    }
}

fun drawStroke(canvas: android.graphics.Canvas,s: Stroke,w: Float,h: Float) {
    StrokePainter().draw(canvas,s,w,h)
}

/** Reuse on a single rendering thread. Input paths allocate no per-segment pairs. */
class StrokePainter {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
    private val path=Path()
    private val cached=object: LinkedHashMap<String,Pair<Stroke,List<InkPoint>>>(32,0.75f,true) {}
    private var cachedPoints=0
    private var previewId=""
    private var previewAmount=-1f
    private val previewPoints=ArrayList<InkPoint>()
    fun draw(canvas: android.graphics.Canvas,s: Stroke,w: Float,h: Float,cache: Boolean=true) {
        val hit=if(cache) cached[s.id]?.takeIf {it.first===s} else null
        val points=if(!cache && s.smoothing>0f && s.tool in setOf(InkTool.PEN,InkTool.PENCIL,InkTool.BRUSH,InkTool.FOUNTAIN,InkTool.HIGHLIGHTER)) {
            if(previewId!=s.id || previewAmount!=s.smoothing || previewPoints.size>s.points.size) {previewPoints.clear();previewId=s.id;previewAmount=s.smoothing}
            // Only the formerly last point changes when a new sample arrives.
            val start=(previewPoints.size-1).coerceAtLeast(0)
            for(i in start until s.points.size) {val p=InkGeometry.smoothPoint(s.points,i,s.smoothing);if(i<previewPoints.size) previewPoints[i]=p else previewPoints.add(p)}
            previewPoints
        } else hit?.second ?: shapePoints(s).also {points -> if(cache && s.smoothing>0f) {
            cached.remove(s.id)?.let {cachedPoints-=it.second.size};cached[s.id]=s to points;cachedPoints+=points.size
            while(cached.size>128 || cachedPoints>200000) {val first=cached.entries.first();cachedPoints-=first.value.second.size;cached.remove(first.key)}
        }};if(points.isEmpty()) return
        paint.color=s.color;paint.alpha=(s.opacity*if(s.tool==InkTool.HIGHLIGHTER) 72 else if(s.tool==InkTool.PENCIL) 150 else 255).toInt().coerceIn(0,255)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=(s.width*w).coerceAtLeast(0.5f)
        if(points.size==1) {paint.style=Paint.Style.FILL;canvas.drawCircle(points[0].x*w,points[0].y*h,paint.strokeWidth*InkGeometry.widthResponse(s.tool,points[0].pressure,s.pressureCurve,points[0].tilt)/2,paint);return}
        if(s.tool==InkTool.PEN || s.tool==InkTool.FOUNTAIN || s.tool==InkTool.PENCIL || s.tool==InkTool.BRUSH) {
            for(i in 1 until points.size) {
                val a=points[i-1];val b=points[i];val response=InkGeometry.widthResponse(s.tool,(a.pressure+b.pressure)/2,s.pressureCurve,a.tilt)
                paint.strokeWidth=(s.width*w*response).coerceAtLeast(0.5f);canvas.drawLine(a.x*w,a.y*h,b.x*w,b.y*h,paint)
            }
        } else {
            path.rewind();path.moveTo(points[0].x*w,points[0].y*h)
            for(i in 1 until points.size) path.lineTo(points[i].x*w,points[i].y*h)
            canvas.drawPath(path,paint)
        }
    }
}

fun drawPaper(canvas: android.graphics.Canvas,paper: Paper,w: Float,h: Float) {
    if(paper==Paper.BLANK) return
    val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=0xFFDCE4EA.toInt();strokeWidth=w/1000f}
    val step=w/24
    var y=step
    while(y<h) {if(paper!=Paper.DOTS) canvas.drawLine(0f,y,w,y,paint) else {var x=step;while(x<w) {canvas.drawCircle(x,y,w/700,paint);x+=step}};y+=step}
    if(paper==Paper.GRID) {var x=step;while(x<w) {canvas.drawLine(x,0f,x,h,paint);x+=step}}
}
