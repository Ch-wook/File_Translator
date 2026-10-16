package kr.documentstudio.core

data class InkFrame(val x: Float,val y: Float,val width: Float,val height: Float) {
    init {require(listOf(x,y,width,height).all {it.isFinite()} && width>0f && height>0f)}
}
data class CellInkBinding(val address: String,val frame: InkFrame) {init {require(validAddress(address))}}
data class InkMeasure(val index: Int,val extent: Float)

/** A bounded viewport; measurements come from actual row/column size properties. */
data class SheetInkGrid(val columns: List<InkMeasure>,val rows: List<InkMeasure>) {
    init {require(columns.size in 1..6 && rows.size in 1..12 && (columns+rows).all {it.extent.isFinite() && it.extent>0})}
    fun frame(address: String): InkFrame? {
        if(!validAddress(address)) return null
        val c=columns.indexOfFirst {it.index==column(address)};val r=rows.indexOfFirst {it.index==address.dropWhile(Char::isLetter).toInt()}
        if(c<0 || r<0) return null
        val width=columns.sumOf {it.extent.toDouble()}.toFloat();val height=rows.sumOf {it.extent.toDouble()}.toFloat()
        return InkFrame(.08f+.9f*columns.take(c).sumOf {it.extent.toDouble()}.toFloat()/width,.06f+.92f*rows.take(r).sumOf {it.extent.toDouble()}.toFloat()/height,.9f*columns[c].extent/width,.92f*rows[r].extent/height)
    }
    fun addressAt(p: InkPoint): String? = rows.asSequence().flatMap {r->columns.asSequence().map {c->columnName(c.index)+r.index}}.firstOrNull {a->frame(a)!!.let {p.x>=it.x && p.x<=it.x+it.width && p.y>=it.y && p.y<=it.y+it.height}}
}
class InkProjection(val anchor: String,val grid: SheetInkGrid) {
    fun display(s: Stroke): Stroke? {
        if(s.anchor!=anchor || s.cell==null) return s
        val to=grid.frame(s.cell.address) ?: return null
        return map(s,s.cell.frame,to)
    }
    fun stored(s: Stroke): Stroke {
        if(s.anchor!=anchor) return s
        val binding=s.cell ?: return s;val from=grid.frame(binding.address) ?: error("화면에서 셀을 찾을 수 없습니다.")
        return map(s,from,binding.frame).also {out->require(out.points.all {it.x in -.00001f..1.00001f && it.y in -.00001f..1.00001f})}
            .let {out->out.copy(width=out.width.coerceIn(.0001f,.1f),points=out.points.map {it.copy(x=it.x.coerceIn(0f,1f),y=it.y.coerceIn(0f,1f))})}
    }
    /** A user move establishes a new frame while retaining the cell owner. */
    fun rebase(s: Stroke): Stroke=if(s.anchor!=anchor) s else s.cell?.let {s.copy(cell=it.copy(frame=grid.frame(it.address) ?: error("셀 범위 밖입니다.")))} ?: s
    private fun map(s: Stroke,from: InkFrame,to: InkFrame)=s.copy(width=s.width*to.width/from.width,points=s.points.map {p->p.copy(x=to.x+(p.x-from.x)*to.width/from.width,y=to.y+(p.y-from.y)*to.height/from.height)})
}
object InkAnchors {
    fun change(strokes: List<Stroke>,change: DocumentChange): List<Stroke> {
        if(change.type !in setOf("sheet.insertRows","sheet.deleteRows","sheet.insertColumns","sheet.deleteColumns")) return strokes
        val axis=if(change.type.endsWith("Rows")) SheetReferences.Axis.ROW else SheetReferences.Axis.COLUMN
        val shift=SheetReferences.Shift(axis,change.arguments.getValue("index").toInt(),change.arguments.getValue("count").toInt(),change.type.contains("delete"))
        return strokes.mapNotNull {s->
            if(s.anchor!=change.target || s.cell==null) s else {
                val address=s.cell.address;val col=column(address);val row=address.dropWhile(Char::isLetter).toInt()
                val moved=shift.point(if(axis==SheetReferences.Axis.ROW) row else col) ?: return@mapNotNull null
                require(moved<=shift.limit) {"필기 앵커가 시트 범위를 벗어납니다."}
                s.copy(cell=s.cell.copy(address=if(axis==SheetReferences.Axis.ROW) columnName(col)+moved else columnName(moved)+row))
            }
        }
    }
}
