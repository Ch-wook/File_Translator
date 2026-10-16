package kr.documentstudio.core

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.util.Locale

data class CellRange(val left: Int,val top: Int,val right: Int,val bottom: Int) {
    val size get()=(right-left+1).toLong()*(bottom-top+1)
    fun contains(address: String)=column(address) in left..right && address.dropWhile {it.isLetter()}.toInt() in top..bottom
    fun overlaps(other: CellRange)=left<=other.right && right>=other.left && top<=other.bottom && bottom>=other.top
    override fun toString()=columnName(left)+top+":"+columnName(right)+bottom
    companion object {
        fun parse(text: String): CellRange {val ends=text.uppercase(Locale.ROOT).split(':');require(ends.size in 1..2 && ends.all(::validAddress)) {"올바른 셀 범위를 입력하세요 (A1:D20)."};val a=ends.first();val b=ends.last();return CellRange(column(a),a.dropWhile {it.isLetter()}.toInt(),column(b),b.dropWhile {it.isLetter()}.toInt()).also {require(it.left<=it.right && it.top<=it.bottom) {"범위의 시작과 끝 순서를 확인하세요."}}}
    }
}

object SheetOperations {
    val operations=setOf("sheet.copyRange","sheet.fill","sheet.clearRange","sheet.paste","sheet.sort","sheet.insertRows","sheet.deleteRows","sheet.insertColumns","sheet.deleteColumns")
    private val S=PackageDocument.S
    fun apply(parts: Map<String,ByteArray>,xml: (String)->Document,change: DocumentChange) {
        val doc=xml(change.target);require(doc.documentElement.localName=="worksheet")
        require(Xml.elements(doc,"sheetProtection").isEmpty()) {"보호된 시트는 구조·범위 편집을 지원하지 않습니다."}
        val args=change.arguments
        if(change.type in setOf("sheet.insertRows","sheet.deleteRows","sheet.insertColumns","sheet.deleteColumns")) {
            val shift=SheetReferences.Shift(if(change.type.endsWith("Rows")) SheetReferences.Axis.ROW else SheetReferences.Axis.COLUMN,args.getValue("index").toInt(),args.getValue("count").toInt(),change.type.contains("delete"))
            structural(parts,xml,change.target,shift);return
        }
        val range=CellRange.parse(args["range"] ?: args["source"] ?: "A1");require(range.size<=10000) {"한 번에 최대 10,000셀까지 편집합니다."}
        when(change.type) {
            "sheet.copyRange","sheet.fill" -> {
                val target=if(change.type=="sheet.fill") CellRange.parse(args.getValue("destination")) else CellRange.parse(args.getValue("destination")).let {CellRange(it.left,it.top,it.left+range.right-range.left,it.top+range.bottom-range.top)}
                require(target.size<=10000 && target.right<=16384 && target.bottom<=1048576) {"복사할 범위가 시트 또는 10,000셀 제한을 벗어납니다."}
                editable(doc,range);editable(doc,target)
                val source=Xml.elements(doc,"c").filter {range.contains(it.getAttribute("r"))}.associate {it.getAttribute("r") to (it.cloneNode(true) as Element)}
                val fill=change.type=="sheet.fill"
                if(fill) require(target.left==range.left && target.top==range.top && target.right>=range.right && target.bottom>=range.bottom) {"자동 채우기 전체 범위는 원본 범위를 포함하고 같은 셀에서 시작해야 합니다."}
                val vertical=fill && range.left==range.right && range.bottom-range.top==1
                val horizontal=fill && range.top==range.bottom && range.right-range.left==1
                fun numeric(a: String)=source[a]?.takeIf {Xml.child(it,"f")==null && it.getAttribute("t") in listOf("","n")}?.let {Xml.child(it,"v")?.textContent?.toDoubleOrNull()?.takeIf(Double::isFinite)}
                val first=numeric(columnName(range.left)+range.top)
                val second=if(vertical) numeric(columnName(range.left)+(range.top+1)) else if(horizontal) numeric(columnName(range.left+1)+range.top) else null
                clear(doc,target,true)
                for(r in target.top..target.bottom) for(c in target.left..target.right) {
                    val sr=range.top+(r-target.top)%(range.bottom-range.top+1);val sc=range.left+(c-target.left)%(range.right-range.left+1)
                    val original=source[columnName(sc)+sr] ?: continue
                    val address=columnName(c)+r;val copy=original.cloneNode(true) as Element;copy.setAttribute("r",address)
                    if(first!=null && second!=null && (vertical || horizontal)) {val number=first+(second-first)*if(vertical) r-target.top else c-target.left;require(number.isFinite()) {"자동 채우기 결과가 숫자 범위를 벗어납니다."};copy.removeAttribute("t");listOf("v","f","is").forEach {Xml.child(copy,it)?.let(copy::removeChild)};copy.appendChild(doc.element("v") {textContent=number.toString()})}
                    else Xml.child(copy,"f")?.let {it.textContent=SheetReferences.copy(it.textContent,r-sr,c-sc);Xml.child(copy,"v")?.let(copy::removeChild)}
                    put(doc,copy)
                }
            }
            "sheet.clearRange" -> {editable(doc,range);clear(doc,range,args["format"]=="true")}
            "sheet.paste" -> {
                val text=args.getValue("text");require(text.length<=2_000_000)
                val rows=TabularText.decode(text)
                val target=CellRange(range.left,range.top,range.left+rows.maxOf {it.size}-1,range.top+rows.size-1)
                require(target.size<=10000 && target.right<=16384 && target.bottom<=1048576) {"붙여넣기 범위가 제한을 벗어납니다."};editable(doc,target)
                val old=Xml.elements(doc,"c").associateBy {it.getAttribute("r")}
                rows.forEachIndexed {r,cells->cells.forEachIndexed {c,value->
                    require(value.length<=32767);val address=columnName(target.left+c)+(target.top+r);val cell=old[address]?.cloneNode(true) as? Element ?: doc.element("c");cell.setAttribute("r",address)
                    listOf("v","f","is").forEach {Xml.child(cell,it)?.let(cell::removeChild)};cell.removeAttribute("t")
                    when {value.startsWith('=')->{require(value.length>1);cell.appendChild(doc.element("f") {textContent=value.drop(1)})}
                        value.isNotEmpty() && value.toDoubleOrNull()?.isFinite()==true->cell.appendChild(doc.element("v") {textContent=value})
                        else->{cell.setAttribute("t","inlineStr");cell.appendChild(doc.element("is") {appendChild(doc.element("t") {textContent=if(value.startsWith('\'')) value.drop(1) else value;setAttributeNS("http://www.w3.org/XML/1998/namespace","xml:space","preserve")})})}}
                    put(doc,cell)
                }}
            }
            "sheet.sort" -> {
                editable(doc,range);val key=args.getValue("column").toInt();require(key in range.left..range.right) {"정렬 열이 범위 안에 있어야 합니다."}
                val first=range.top+if(args["header"]!="false") 1 else 0
                val cells=Xml.elements(doc,"c").filter {range.contains(it.getAttribute("r"))}.associate {it.getAttribute("r") to (it.cloneNode(true) as Element)}
                val strings=parts["xl/sharedStrings.xml"]?.let {Xml.elements(Xml.parse(it),"si").map {si->Xml.elements(si,"t").joinToString("") {it.textContent}}} ?: emptyList()
                fun value(row: Int): String {val cell=cells[columnName(key)+row] ?: return "";require(Xml.child(cell,"f")==null) {"수식 열 정렬은 계산 결과의 정확성을 위해 지원하지 않습니다."};return when(cell.getAttribute("t")) {"s"->strings.getOrNull(Xml.child(cell,"v")?.textContent?.toIntOrNull() ?: -1) ?: "";"inlineStr"->Xml.elements(cell,"t").joinToString("") {it.textContent};else->Xml.child(cell,"v")?.textContent ?: ""}}
                val values=(first..range.bottom).associateWith(::value)
                val order=(first..range.bottom).sortedWith {a,b ->val x=values.getValue(a);val y=values.getValue(b);val nx=x.toDoubleOrNull();val ny=y.toDoubleOrNull();val compare=when {x.isEmpty() && y.isNotEmpty()->1;y.isEmpty() && x.isNotEmpty()->-1;nx!=null && ny!=null->nx.compareTo(ny);else->x.compareTo(y,true)};if(args["descending"]=="true" && x.isNotEmpty() && y.isNotEmpty()) -compare else compare}
                if(first<=range.bottom) clear(doc,range.copy(top=first),true)
                order.forEachIndexed {index,oldRow->val row=first+index;for(c in range.left..range.right) {val cell=cells[columnName(c)+oldRow]?.cloneNode(true) as? Element ?: continue;cell.setAttribute("r",columnName(c)+row);Xml.child(cell,"f")?.let {it.textContent=SheetReferences.copy(it.textContent,row-oldRow,0);Xml.child(cell,"v")?.let(cell::removeChild)};put(doc,cell)}}
            }
        }
        Xml.elements(doc,"dimension").forEach {it.parentNode.removeChild(it)}
    }
    private fun editable(doc: Document,range: CellRange) {
        require(Xml.elements(doc,"mergeCell").none {range.overlaps(CellRange.parse(it.getAttribute("ref")))}) {"병합 셀이 겹치는 범위는 먼저 병합을 해제하세요."}
        Xml.elements(doc,"c").forEach {c->val f=Xml.child(c,"f") ?: return@forEach;if(f.getAttribute("t") in listOf("array","shared","dataTable")) require(!range.contains(c.getAttribute("r")) && (f.getAttribute("ref").isBlank() || !range.overlaps(CellRange.parse(f.getAttribute("ref"))))) {"배열·공유·데이터 테이블 수식이 겹치는 범위는 편집할 수 없습니다."}}
    }
    private fun clear(doc: Document,range: CellRange,format: Boolean) {Xml.elements(doc,"c").filter {range.contains(it.getAttribute("r"))}.forEach {c->if(format) c.parentNode.removeChild(c) else {listOf("f","v","is").forEach {Xml.child(c,it)?.let(c::removeChild)};c.removeAttribute("t")}}}
    private fun put(doc: Document,cell: Element) {
        val address=cell.getAttribute("r");val number=address.dropWhile {it.isLetter()}.toInt();val data=Xml.elements(doc,"sheetData").single()
        val row=Xml.elements(data,"row").firstOrNull {it.getAttribute("r").toInt()==number} ?: doc.element("row") {setAttribute("r",number.toString())}.also {data.insertBefore(it,Xml.elements(data,"row").firstOrNull {r->r.getAttribute("r").toInt()>number})}
        Xml.elements(row,"c").firstOrNull {it.getAttribute("r")==address}?.let(row::removeChild)
        row.insertBefore(cell,Xml.elements(row,"c").firstOrNull {column(it.getAttribute("r"))>column(address)})
    }
    private fun structural(parts: Map<String,ByteArray>,xml: (String)->Document,target: String,shift: SheetReferences.Shift) {
        val doc=xml(target)
        require(listOf("drawing","legacyDrawing","tableParts","oleObjects","pivotTableParts","extLst","conditionalFormatting","dataValidations","autoFilter").none {Xml.elements(doc,it).isNotEmpty()}) {"그림·표·필터·조건부 서식·유효성·확장 개체가 있는 시트의 행·열 변경은 관계 보호를 위해 지원하지 않습니다."}
        require(parts.keys.filter {it.startsWith("xl/worksheets/") && it.endsWith(".xml")}.all {path->Xml.elements(xml(path),"f").none {it.getAttribute("t") in listOf("array","shared","dataTable")}}) {"배열·공유 수식이 있는 통합 문서의 구조 변경은 지원하지 않습니다."}
        val wb=xml("xl/workbook.xml");val rels=Xml.elements(xml("xl/_rels/workbook.xml.rels"),"Relationship").associate {it.getAttribute("Id") to it.getAttribute("Target").let {t->java.net.URI(if(t.startsWith('/')) t else "/xl/$t").normalize().path.removePrefix("/")}}
        val sheets=Xml.elements(wb,"sheet");val names=sheets.associate {rels[it.getAttributeNS(PackageDocument.R,"id")] to it.getAttribute("name")};val name=names[target] ?: error("시트 관계를 찾을 수 없습니다.")
        parts.keys.filter {it.endsWith(".xml")}.forEach {path->val part=xml(path)
            listOf("f","formula","formula1","formula2","calculatedColumnFormula","totalsRowFormula").flatMap {Xml.elements(part,it)}.filter {it.namespaceURI in listOf(S,"http://schemas.openxmlformats.org/drawingml/2006/chart")}.forEach {f->f.textContent=SheetReferences.structural(f.textContent,names[path],name,shift)}
            Xml.elements(part,"definedName").forEach {n->val local=n.getAttribute("localSheetId").toIntOrNull()?.let {sheets.getOrNull(it)?.getAttribute("name")};n.textContent=SheetReferences.structural(n.textContent,local,name,shift)}
            Xml.elements(part,"hyperlink").forEach {if(it.hasAttribute("location")) it.setAttribute("location",SheetReferences.structural(it.getAttribute("location"),names[path],name,shift))}
        }
        Xml.elements(doc,"row").forEach {row->
            if(shift.axis==SheetReferences.Axis.ROW) {val moved=shift.point(row.getAttribute("r").toInt());if(moved==null) {row.parentNode.removeChild(row);return@forEach};require(moved<=shift.limit) {"이동할 행이 시트 범위를 벗어납니다."};row.setAttribute("r",moved.toString())}
            row.removeAttribute("spans")
            Xml.elements(row,"c").forEach {cell->val old=cell.getAttribute("r");val r=old.dropWhile {it.isLetter()}.toInt();val c=column(old);val moved=shift.point(if(shift.axis==SheetReferences.Axis.ROW) r else c);if(moved==null) row.removeChild(cell) else {require(moved<=shift.limit) {"이동할 셀이 시트 범위를 벗어납니다."};cell.setAttribute("r",if(shift.axis==SheetReferences.Axis.ROW) columnName(c)+moved else columnName(moved)+r)}}
        }
        if(shift.axis==SheetReferences.Axis.COLUMN) Xml.elements(doc,"col").forEach {col->val interval=shift.interval(col.getAttribute("min").toInt(),col.getAttribute("max").toInt());if(interval==null) col.parentNode.removeChild(col) else {require(interval.second<=16384);col.setAttribute("min",interval.first.toString());col.setAttribute("max",interval.second.toString())}}
        listOf("mergeCell","autoFilter","hyperlink","sortState","sortCondition","protectedRange","ignoredError").forEach {tag->Xml.elements(doc,tag).forEach {e->if(e.hasAttribute("ref")) {val ref=SheetReferences.structural(e.getAttribute("ref"),name,name,shift);if(ref.contains("#REF!")) e.parentNode.removeChild(e) else e.setAttribute("ref",ref)}}}
        Xml.elements(doc,"mergeCells").forEach {it.setAttribute("count",Xml.elements(it,"mergeCell").size.toString())}
        listOf("conditionalFormatting","dataValidation","selection").forEach {tag->Xml.elements(doc,tag).forEach {e->if(e.hasAttribute("sqref")) {val refs=e.getAttribute("sqref").split(Regex("\\s+")).map {SheetReferences.structural(it,name,name,shift)}.filterNot {it.contains("#REF!")};if(refs.isEmpty()) e.parentNode.removeChild(e) else e.setAttribute("sqref",refs.joinToString(" "))};if(e.hasAttribute("activeCell")) e.setAttribute("activeCell",SheetReferences.structural(e.getAttribute("activeCell"),name,name,shift).takeUnless {it.contains("#REF!")} ?: "A1")}}
        Xml.elements(doc,"dataValidations").forEach {it.setAttribute("count",Xml.elements(it,"dataValidation").size.toString())}
        (Xml.elements(doc,"pane")+Xml.elements(doc,"sheetView")).forEach {e->if(e.hasAttribute("topLeftCell")) e.setAttribute("topLeftCell",SheetReferences.structural(e.getAttribute("topLeftCell"),name,name,shift).takeUnless {it.contains("#REF!")} ?: "A1");if(e.localName=="pane" && e.getAttribute("state").startsWith("frozen")) {val key=if(shift.axis==SheetReferences.Axis.ROW) "ySplit" else "xSplit";e.getAttribute(key).toDoubleOrNull()?.toInt()?.let {old->val next=shift.interval(1,old)?.second ?: 0;e.setAttribute(key,next.toString())}}}
        (Xml.elements(doc,"dimension")+Xml.elements(doc,if(shift.axis==SheetReferences.Axis.ROW) "rowBreaks" else "colBreaks")).forEach {it.parentNode.removeChild(it)}
    }
    private fun Document.element(name: String,configure: Element.()->Unit = {})=createElementNS(S,name).apply(configure)
}
