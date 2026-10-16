package kr.documentstudio.core

import org.w3c.dom.Document
import org.w3c.dom.Element

/** Conservative OWPML table edits. Nested/protected/irregular tables stay intact. */
object HangulTables {
    private const val HP="http://www.hancom.co.kr/hwpml/2011/paragraph"
    private const val HH="http://www.hancom.co.kr/hwpml/2011/head"
    private const val HC="http://www.hancom.co.kr/hwpml/2011/core"
    val operations=setOf("hwpx.table","hwpx.tableAddRow","hwpx.tableDeleteRow","hwpx.tableAddColumn","hwpx.tableDeleteColumn","hwpx.tableMerge","hwpx.tableSplit","hwpx.tableCellStyle","hwpx.tableSize","hwpx.tableAlign")
    private fun children(e: Element,name: String)= (0 until e.childNodes.length).mapNotNull {e.childNodes.item(it) as? Element}.filter {it.localName==name && it.namespaceURI==HP}
    private fun child(e: Element,name: String)=children(e,name).singleOrNull() ?: error("한글 표의 $name 속성이 올바르지 않습니다.")
    private fun Element.number(name: String)=getAttribute(name).toIntOrNull() ?: error("한글 표의 $name 값이 올바르지 않습니다.")
    private fun Document.node(name: String,vararg attrs: Pair<String,String>)=createElementNS(HP,"hp:$name").apply {attrs.forEach {(k,v)->setAttribute(k,v)}}
    private fun units(mm: String)=mm.toDouble().let {require(it.isFinite() && it in 2.0..250.0);(it*7200/25.4).toInt()}
    private fun nextId(doc: Document)=((Xml.elements(doc,"p")+Xml.elements(doc,"tbl")).maxOfOrNull {it.getAttribute("id").toLongOrNull() ?: 0L} ?: 0L)+1L
    private fun paragraph(doc: Document,model: Element,id: Long): Element = doc.node("p","id" to id.toString(),"paraPrIDRef" to model.getAttribute("paraPrIDRef"),"styleIDRef" to model.getAttribute("styleIDRef"),"pageBreak" to "0","columnBreak" to "0","merged" to "0").apply {
        appendChild(doc.node("run","charPrIDRef" to (Xml.elements(model,"run").firstOrNull()?.getAttribute("charPrIDRef") ?: "0")).apply {appendChild(doc.node("t"))})
    }
    private fun border(header: Document,source: String?,background: String?,color: String): String {
        require(color.matches(Regex("[0-9A-Fa-f]{6}")) && (background==null || background.matches(Regex("[0-9A-Fa-f]{6}")))) {"색상은 RRGGBB 형식이어야 합니다."}
        val fills=Xml.elements(header,"borderFills").single()
        val old=Xml.elements(fills,"borderFill").firstOrNull {it.getAttribute("id")==source} ?: Xml.elements(fills,"borderFill").first()
        val next=(Xml.elements(fills,"borderFill").maxOf {it.number("id")}+1).toString()
        val fill=(old.cloneNode(true) as Element).apply {setAttribute("id",next)}
        listOf("leftBorder","rightBorder","topBorder","bottomBorder").forEach {name ->
            val side=Xml.child(fill,name) ?: header.createElementNS(HH,"hh:$name").also {fill.appendChild(it)}
            side.setAttribute("type","SOLID");side.setAttribute("width","0.12 mm");side.setAttribute("color","#$color")
        }
        if(background!=null) {
            Xml.elements(fill,"fillBrush").forEach {it.parentNode.removeChild(it)}
            fill.appendChild(header.createElementNS(HC,"hc:fillBrush").apply {appendChild(header.createElementNS(HC,"hc:winBrush").apply {setAttribute("faceColor","#$background");setAttribute("hatchColor","#000000");setAttribute("alpha","0")})})
        }
        fills.appendChild(fill);fills.setAttribute("itemCnt",Xml.elements(fills,"borderFill").size.toString());return next
    }
    fun apply(doc: Document,header: Document,change: DocumentChange) {
        val args=change.arguments
        val model=children(doc.documentElement,"p").first()
        var id=nextId(doc)
        fun freshCell(row: Int,col: Int,width: Int,height: Int,style: String,para: Element=model): Element = doc.node("tc","name" to "","header" to "0","hasMargin" to "1","protect" to "0","editable" to "0","dirty" to "1","borderFillIDRef" to style).apply {
            appendChild(doc.node("subList","id" to "","textDirection" to "HORIZONTAL","lineWrap" to "BREAK","vertAlign" to "CENTER","linkListIDRef" to "0","linkListNextIDRef" to "0","textWidth" to "0","textHeight" to "0","hasTextRef" to "0","hasNumRef" to "0").apply {appendChild(paragraph(doc,para,id++))})
            appendChild(doc.node("cellAddr","colAddr" to col.toString(),"rowAddr" to row.toString()))
            appendChild(doc.node("cellSpan","colSpan" to "1","rowSpan" to "1"))
            appendChild(doc.node("cellSz","width" to width.toString(),"height" to height.toString()))
            appendChild(doc.node("cellMargin","left" to "141","right" to "141","top" to "141","bottom" to "141"))
        }
        if(change.type=="hwpx.table") {
            val rows=args.getValue("rows").toInt();val cols=args.getValue("columns").toInt()
            require(rows in 1..50 && cols in 1..20) {"표는 1–50행, 1–20열까지 생성합니다."}
            val width=units(args["widthMm"] ?: "160");val height=units("8")*rows
            val style=border(header,null,null,"000000")
            val table=doc.node("tbl","id" to (id++).toString(),"zOrder" to "0","numberingType" to "TABLE","textWrap" to "TOP_AND_BOTTOM","textFlow" to "BOTH_SIDES","lock" to "0","dropcapstyle" to "None","pageBreak" to "CELL","repeatHeader" to "1","rowCnt" to rows.toString(),"colCnt" to cols.toString(),"cellSpacing" to "0","borderFillIDRef" to style,"noAdjust" to "0")
            table.appendChild(doc.node("sz","width" to width.toString(),"height" to height.toString(),"widthRelTo" to "ABSOLUTE","heightRelTo" to "ABSOLUTE","protect" to "0"))
            table.appendChild(doc.node("pos","treatAsChar" to "1","affectLSpacing" to "0","flowWithText" to "1","allowOverlap" to "0","holdAnchorAndSO" to "0","vertRelTo" to "PARA","horzRelTo" to "COLUMN","vertAlign" to "TOP","horzAlign" to "LEFT","vertOffset" to "0","horzOffset" to "0"))
            for(name in listOf("outMargin","inMargin")) table.appendChild(doc.node(name,"left" to "0","right" to "0","top" to "0","bottom" to "0"))
            repeat(rows) {r->table.appendChild(doc.node("tr").apply {repeat(cols) {c->appendChild(freshCell(r,c,width*(c+1)/cols-width*c/cols,height/rows,style))}})}
            val p=paragraph(doc,model,id++);child(p,"run").insertBefore(table,child(child(p,"run"),"t"));doc.documentElement.appendChild(p)
        } else {
            val tables=Xml.elements(doc,"tbl").filter {e->generateSequence(e.parentNode) {it.parentNode}.none {it is Element && it.localName=="tbl"}}
            val table=tables.getOrNull((args["table"] ?: "1").toInt()-1) ?: error("표 번호가 올바르지 않습니다.")
            require(table.getAttribute("lock")!="1" && Xml.elements(table,"tbl").isEmpty()) {"잠기거나 중첩된 표는 보호합니다."}
            val rows=children(table,"tr");val cells=rows.flatMap {children(it,"tc")}
            val rowCount=table.number("rowCnt");val colCount=table.number("colCnt")
            require(rowCount in 1..50 && colCount in 1..20 && rows.size==rowCount)
            require(cells.none {it.getAttribute("protect")=="1"}) {"보호된 셀이 있는 표는 수정하지 않습니다."}
            val occupied=mutableSetOf<Pair<Int,Int>>()
            cells.forEach {cell ->
                val a=child(cell,"cellAddr");val span=child(cell,"cellSpan");val r=a.number("rowAddr");val c=a.number("colAddr");val rs=span.number("rowSpan");val cs=span.number("colSpan")
                require(r==rows.indexOf(cell.parentNode) && rs>0 && cs>0 && r+rs<=rowCount && c+cs<=colCount && c>=0) {"표 셀의 위치가 올바르지 않습니다."}
                for(y in r until r+rs) for(x in c until c+cs) require(occupied.add(y to x)) {"표 셀 범위가 겹칩니다."}
            }
            require(occupied.size==rowCount*colCount) {"비어 있는 셀 위치가 있는 표는 보호합니다."}
            fun cell(r: Int,c: Int)=cells.singleOrNull {child(it,"cellAddr").let {a->a.number("rowAddr")==r && a.number("colAddr")==c}} ?: error("병합 시작 셀을 선택하세요.")
            val row=(args["row"] ?: "1").toInt()-1;val col=(args["column"] ?: "1").toInt()-1
            fun unmerged() {require(cells.all {child(it,"cellSpan").let {s->s.number("rowSpan")==1 && s.number("colSpan")==1}}) {"행·열·크기 변경 전에 병합을 해제하세요."}}
            when(change.type) {
                "hwpx.tableAlign" -> {
                    val align=args.getValue("align").uppercase();require(align in setOf("LEFT","CENTER","RIGHT"))
                    child(table,"pos").apply {setAttribute("treatAsChar","0");setAttribute("horzRelTo","COLUMN");setAttribute("horzAlign",align);setAttribute("horzOffset","0")}
                }
                "hwpx.tableCellStyle" -> {
                    val selected=cell(row,col);val vertical=args["vertical"] ?: "CENTER";require(vertical.uppercase() in listOf("TOP","CENTER","BOTTOM"))
                    selected.setAttribute("borderFillIDRef",border(header,selected.getAttribute("borderFillIDRef"),args["background"],args["border"] ?: "000000"))
                    child(selected,"subList").setAttribute("vertAlign",vertical.uppercase())
                }
                "hwpx.tableSize" -> {
                    unmerged();val width=units(args.getValue("widthMm"));val height=units(args.getValue("heightMm"));require(height/rowCount>=284 && width/colCount>=284) {"셀 크기는 최소 1 mm가 필요합니다."}
                    child(table,"sz").apply {setAttribute("width",width.toString());setAttribute("height",height.toString())}
                    cells.forEach {c->val a=child(c,"cellAddr");val x=a.number("colAddr");val y=a.number("rowAddr");child(c,"cellSz").apply {setAttribute("width",(width*(x+1)/colCount-width*x/colCount).toString());setAttribute("height",(height*(y+1)/rowCount-height*y/rowCount).toString())}}
                }
                "hwpx.tableMerge" -> {
                    val bottom=args.getValue("lastRow").toInt()-1;val right=args.getValue("lastColumn").toInt()-1
                    require(row in 0 until rowCount && col in 0 until colCount && bottom in row until rowCount && right in col until colCount && (row!=bottom || col!=right))
                    val selected=cells.filter {c->val a=child(c,"cellAddr");a.number("rowAddr") in row..bottom && a.number("colAddr") in col..right}
                    require(selected.size==(bottom-row+1)*(right-col+1) && selected.all {child(it,"cellSpan").let {s->s.number("rowSpan")==1 && s.number("colSpan")==1}}) {"기존 병합과 겹치는 범위는 병합할 수 없습니다."}
                    require(selected.all {Xml.elements(it,"run").all {run->(0 until run.childNodes.length).mapNotNull {run.childNodes.item(it) as? Element}.all {it.localName=="t" && it.namespaceURI==HP && (0 until it.childNodes.length).none {n->it.childNodes.item(n) is Element}}}}) {"개체·제어 문자를 포함한 셀은 병합하지 않습니다."}
                    val first=cell(row,col);val list=child(first,"subList")
                    selected.filter {it!=first}.forEach {c->children(child(c,"subList"),"p").toList().forEach {p->list.appendChild(p)};c.parentNode.removeChild(c)}
                    child(first,"cellSpan").apply {setAttribute("rowSpan",(bottom-row+1).toString());setAttribute("colSpan",(right-col+1).toString())}
                    child(first,"cellSz").apply {setAttribute("width",selected.filter {child(it,"cellAddr").number("rowAddr")==row}.sumOf {child(it,"cellSz").number("width")}.toString());setAttribute("height",selected.filter {child(it,"cellAddr").number("colAddr")==col}.sumOf {child(it,"cellSz").number("height")}.toString())}
                }
                "hwpx.tableSplit" -> {
                    val selected=cell(row,col);val span=child(selected,"cellSpan");val rs=span.number("rowSpan");val cs=span.number("colSpan");require(rs>1 || cs>1) {"병합된 셀이 아닙니다."}
                    val size=child(selected,"cellSz");val width=size.number("width");val height=size.number("height")
                    for(y in row until row+rs) for(x in col until col+cs) if(y!=row || x!=col) {
                        val fresh=freshCell(y,x,width*(x-col+1)/cs-width*(x-col)/cs,height*(y-row+1)/rs-height*(y-row)/rs,selected.getAttribute("borderFillIDRef"),children(child(selected,"subList"),"p").first())
                        rows[y].insertBefore(fresh,children(rows[y],"tc").firstOrNull {child(it,"cellAddr").number("colAddr")>x})
                    }
                    size.setAttribute("width",(width/cs).toString());size.setAttribute("height",(height/rs).toString());span.setAttribute("colSpan","1");span.setAttribute("rowSpan","1")
                }
                else -> {
                    unmerged();val isRow=change.type.endsWith("Row");val adding=change.type.contains("Add");val index=if(isRow) row else col;val count=if(isRow) rowCount else colCount
                    require(index in 0 until count+(if(adding) 1 else 0)) {"삽입·삭제 위치가 범위를 벗어났습니다."}
                    require(if(adding) count<(if(isRow) 50 else 20) else count>1) {"표의 최소·최대 행열 수를 벗어납니다."}
                    if(isRow) {
                        if(adding) {
                            val ref=rows[minOf(index,rowCount-1)];val fresh=doc.node("tr")
                            children(ref,"tc").forEach {old->val a=child(old,"cellAddr");val z=child(old,"cellSz");fresh.appendChild(freshCell(index,a.number("colAddr"),z.number("width"),z.number("height"),old.getAttribute("borderFillIDRef"),children(child(old,"subList"),"p").first()))}
                            table.insertBefore(fresh,rows.getOrNull(index))
                        } else table.removeChild(rows[index])
                    } else rows.forEachIndexed {r,tr->
                        if(adding) {val ref=cell(r,minOf(index,colCount-1));val z=child(ref,"cellSz");tr.insertBefore(freshCell(r,index,z.number("width"),z.number("height"),ref.getAttribute("borderFillIDRef"),children(child(ref,"subList"),"p").first()),children(tr,"tc").getOrNull(index))}
                        else tr.removeChild(cell(r,index))
                    }
                    table.setAttribute(if(isRow) "rowCnt" else "colCnt",(count+(if(adding) 1 else -1)).toString())
                    children(table,"tr").forEachIndexed {r,tr->children(tr,"tc").forEachIndexed {c,tc->child(tc,"cellAddr").apply {setAttribute("rowAddr",r.toString());setAttribute("colAddr",c.toString())}}}
                    val newRows=children(table,"tr");child(table,"sz").apply {setAttribute("width",children(newRows.first(),"tc").sumOf {child(it,"cellSz").number("width")}.toString());setAttribute("height",newRows.sumOf {child(children(it,"tc").first(),"cellSz").number("height")}.toString())}
                }
            }
        }
        Xml.elements(doc,"linesegarray").forEach {it.parentNode.removeChild(it)}
    }
}
