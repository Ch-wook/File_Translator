package kr.documentstudio.core

import org.w3c.dom.Document
import org.w3c.dom.Element

object WordTables {
    val operations=setOf("word.tableAddRow","word.tableDeleteRow","word.tableAddColumn","word.tableDeleteColumn","word.tableMerge","word.tableSplit","word.tableCellStyle")
    private val W=PackageDocument.W
    private fun children(e: Element,name: String)=(0 until e.childNodes.length).map {e.childNodes.item(it)}.filterIsInstance<Element>().filter {it.localName==name && it.namespaceURI==W}
    fun apply(doc: Document,change: DocumentChange) {
        val a=change.arguments;val table=Xml.elements(doc,"tbl").getOrNull((a["table"] ?: "1").toInt()-1) ?: error("표 번호를 확인하세요.")
        val rows=children(table,"tr");require(rows.isNotEmpty());val row=(a["row"] ?: "1").toInt()-1;val column=(a["column"] ?: "1").toInt()-1
        require(Xml.elements(table,"gridBefore").isEmpty() && Xml.elements(table,"gridAfter").isEmpty()) {"행 시작·끝 격자가 생략된 복잡한 표는 구조 편집을 지원하지 않습니다."}
        fun cells(r: Element)=children(r,"tc")
        fun property(cell: Element)=Xml.child(cell,"tcPr") ?: doc.element("tcPr").also {cell.insertBefore(it,cell.firstChild)}
        fun span(cell: Element)=Xml.child(cell,"tcPr")?.let {Xml.child(it,"gridSpan")?.getAttributeNS(W,"val")?.toIntOrNull()} ?: 1
        fun cellAt(r: Int,c: Int): Element {require(r in rows.indices);var position=0;for(cell in cells(rows[r])) {if(position==c) return cell;position+=span(cell)};error("병합 시작 셀의 행·열을 입력하세요.")}
        if(change.type=="word.tableCellStyle") {
            val cell=cellAt(row,column);val pr=property(cell)
            a["background"]?.let {require(it.matches(Regex("[0-9A-Fa-f]{6}")));val shd=Xml.child(pr,"shd") ?: doc.element("shd").also(pr::appendChild);shd.setAttributeNS(W,"w:val","clear");shd.setAttributeNS(W,"w:fill",it.uppercase())}
            a["vertical"]?.let {require(it in listOf("top","center","bottom"));val e=Xml.child(pr,"vAlign") ?: doc.element("vAlign").also(pr::appendChild);e.setAttributeNS(W,"w:val",it)}
            a["border"]?.let {require(it.matches(Regex("[0-9A-Fa-f]{6}")));val borders=Xml.child(pr,"tcBorders") ?: doc.element("tcBorders").also(pr::appendChild);for(edge in listOf("top","left","bottom","right")) {val e=Xml.child(borders,edge) ?: doc.element(edge).also(borders::appendChild);e.setAttributeNS(W,"w:val","single");e.setAttributeNS(W,"w:sz","8");e.setAttributeNS(W,"w:color",it.uppercase())}}
        } else if(change.type=="word.tableSplit") {
            val first=cellAt(row,column);val width=span(first);val merge=Xml.child(property(first),"vMerge");require(width>1 || merge?.getAttributeNS(W,"val")=="restart") {"선택한 셀은 병합 시작 셀이 아닙니다."}
            var last=row
            if(merge!=null) {require(merge.getAttributeNS(W,"val")=="restart");for(r in row+1 until rows.size) {val cell=cellAt(r,column);val continuation=Xml.child(property(cell),"vMerge");if(continuation==null || continuation.getAttributeNS(W,"val")=="restart") break;require(span(cell)==width);last=r}}
            for(r in row..last) {val cell=cellAt(r,column);val pr=property(cell);listOf("gridSpan","vMerge").forEach {Xml.child(pr,it)?.let(pr::removeChild)}
                val total=Xml.child(pr,"tcW")?.getAttributeNS(W,"w")?.toIntOrNull();if(total!=null) Xml.child(pr,"tcW")!!.setAttributeNS(W,"w:w",(total/width).toString())
                repeat(width-1) {val blank=cell.cloneNode(true) as Element;clear(blank);rows[r].insertBefore(blank,cell.nextSibling)}
            }
        } else {
            require(Xml.elements(table,"vMerge").isEmpty() && Xml.elements(table,"gridSpan").none {it.getAttributeNS(W,"val") !in listOf("","1")}) {"행·열 변경이나 새 병합 전에 기존 병합 셀을 분할하세요."}
            val count=cells(rows.first()).size;require(count>0 && rows.all {cells(it).size==count}) {"행마다 열 수가 다른 표는 지원하지 않습니다."}
            when(change.type) {
                "word.tableAddRow" -> {require(rows.size<500 && row in 0..rows.size);val template=rows[row.coerceAtMost(rows.lastIndex)];val added=template.cloneNode(true) as Element;children(added,"tc").forEach(::clear);Xml.elements(added,"tblHeader").forEach {it.parentNode.removeChild(it)};table.insertBefore(added,rows.getOrNull(row))}
                "word.tableDeleteRow" -> {require(rows.size>1 && row in rows.indices);table.removeChild(rows[row])}
                "word.tableAddColumn","word.tableDeleteColumn" -> {
                    val add=change.type=="word.tableAddColumn";require(if(add) count<50 && column in 0..count else count>1 && column in 0 until count)
                    rows.forEach {r->val all=cells(r);if(add) {val blank=all[column.coerceAtMost(count-1)].cloneNode(true) as Element;clear(blank);r.insertBefore(blank,all.getOrNull(column))} else r.removeChild(all[column])}
                    val grid=Xml.child(table,"tblGrid");if(grid!=null) {val columns=children(grid,"gridCol");require(columns.size==count);if(add) grid.insertBefore(columns[column.coerceAtMost(count-1)].cloneNode(true),columns.getOrNull(column)) else grid.removeChild(columns[column])}
                }
                "word.tableMerge" -> {
                    val lastRow=a.getValue("lastRow").toInt()-1;val lastCol=a.getValue("lastColumn").toInt()-1
                    require(row in rows.indices && lastRow in row until rows.size && column in 0 until count && lastCol in column until count && (lastRow>row || lastCol>column))
                    val top=cells(rows[row])[column]
                    for(r in row..lastRow) {
                        val all=cells(rows[r]);val anchor=all[column];val pr=property(anchor)
                        for(c in column..lastCol) {val cell=all[c];if(cell!==top) {(0 until cell.childNodes.length).map {cell.childNodes.item(it)}.filter {it !is Element || it.localName!="tcPr"}.forEach {top.appendChild(it.cloneNode(true))}}}
                        val width=(column..lastCol).mapNotNull {c->Xml.child(property(all[c]),"tcW")?.getAttributeNS(W,"w")?.toIntOrNull()}.takeIf {it.size==lastCol-column+1}?.sum()
                        if(width!=null) Xml.child(pr,"tcW")?.setAttributeNS(W,"w:w",width.toString())
                        if(lastCol>column) pr.appendChild(doc.element("gridSpan") {setAttributeNS(W,"w:val",(lastCol-column+1).toString())})
                        if(lastRow>row) pr.appendChild(doc.element("vMerge") {if(r==row) setAttributeNS(W,"w:val","restart")})
                        if(r>row) clear(anchor)
                        for(c in column+1..lastCol) rows[r].removeChild(all[c])
                    }
                }
            }
        }
        // CT_TcPr has ordered properties; unknown properties stay after the known group.
        Xml.elements(table,"tcPr").forEach {pr->val order=listOf("cnfStyle","tcW","gridSpan","hMerge","vMerge","tcBorders","shd","noWrap","tcMar","textDirection","tcFitText","vAlign","hideMark","headers","cellIns","cellDel","cellMerge","tcPrChange");val all=(0 until pr.childNodes.length).map {pr.childNodes.item(it)}.filterIsInstance<Element>();all.forEach(pr::removeChild);all.sortedBy {order.indexOf(it.localName).let {i->if(i<0) order.size else i}}.forEach(pr::appendChild)}
    }
    private fun clear(cell: Element) {
        (0 until cell.childNodes.length).map {cell.childNodes.item(it)}.filter {it !is Element || it.localName!="tcPr"}.forEach(cell::removeChild)
        val doc=cell.ownerDocument;cell.appendChild(doc.element("p") {appendChild(doc.element("r") {appendChild(doc.element("t"))})})
    }
    private fun Document.element(name: String,configure: Element.()->Unit = {})=createElementNS(W,"w:$name").apply(configure)
}
