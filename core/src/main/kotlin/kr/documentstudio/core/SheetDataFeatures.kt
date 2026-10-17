package kr.documentstudio.core

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.util.UUID

object SheetDataFeatures {
    val operations=setOf("sheet.borders","sheet.conditional","sheet.validation","sheet.table")
    private val S=PackageDocument.S;private val R=PackageDocument.R
    private const val PR="http://schemas.openxmlformats.org/package/2006/relationships"
    private fun Document.node(name: String,block: Element.()->Unit = {})=createElementNS(S,name).apply(block)
    private fun hex(value: String)=value.uppercase().also {require(it.matches(Regex("[0-9A-F]{6}")))}
    private fun append(doc: Document,node: Element,before: Set<String>) {doc.documentElement.insertBefore(node,(0 until doc.documentElement.childNodes.length).mapNotNull {doc.documentElement.childNodes.item(it) as? Element}.firstOrNull {it.localName in before})}
    private fun cell(doc: Document,address: String): Element {
        val data=Xml.child(doc.documentElement,"sheetData") ?: error("시트 데이터가 없습니다.");val r=address.dropWhile(Char::isLetter).toInt()
        val row=Xml.elements(data,"row").firstOrNull {it.getAttribute("r")==r.toString()} ?: doc.node("row") {setAttribute("r",r.toString())}.also {data.insertBefore(it,Xml.elements(data,"row").firstOrNull {it.getAttribute("r").toInt()>r})}
        return Xml.elements(row,"c").firstOrNull {it.getAttribute("r")==address} ?: doc.node("c") {setAttribute("r",address)}.also {row.insertBefore(it,Xml.elements(row,"c").firstOrNull {column(it.getAttribute("r"))>column(address)})}
    }
    fun apply(parts: MutableMap<String,ByteArray>,xml: (String)->Document,change: DocumentChange) {
        val doc=xml(change.target)
        require(doc.documentElement.localName=="worksheet" && doc.documentElement.namespaceURI==S)
        require(Xml.elements(doc,"sheetProtection").isEmpty()) {"보호된 시트입니다."}
        val args=change.arguments;val range=CellRange.parse(args.getValue("range"));require(range.size<=10000) {"한 번에 10,000셀까지 적용합니다."}
        val ref=range.toString();val styles=xml("xl/styles.xml")
        when(change.type) {
            "sheet.borders" -> {
                val style=args["style"] ?: "thin";require(style in setOf("thin","medium","dashed","dotted","double","none"));val color=hex(args["color"] ?: "000000")
                val borders=Xml.elements(styles,"borders").single();val id=Xml.elements(borders,"border").size
                borders.appendChild(styles.node("border") {listOf("left","right","top","bottom","diagonal").forEach {name->appendChild(styles.node(name) {if(name!="diagonal" && style!="none") {setAttribute("style",style);appendChild(styles.node("color") {setAttribute("rgb","FF$color")})}})}});borders.setAttribute("count",(id+1).toString())
                val xfs=Xml.elements(styles,"cellXfs").single();val original=Xml.elements(xfs,"xf");val mappings=mutableMapOf<Int,Int>()
                for(r in range.top..range.bottom) for(c in range.left..range.right) {
                    val cell=cell(doc,columnName(c)+r);val old=cell.getAttribute("s").toIntOrNull() ?: 0
                    val next=mappings.getOrPut(old) {require(Xml.elements(xfs,"xf").size<65535);val xf=(original.getOrNull(old) ?: error("셀 서식 참조 오류")).cloneNode(true) as Element;xf.setAttribute("borderId",id.toString());xf.setAttribute("applyBorder","1");val n=Xml.elements(xfs,"xf").size;xfs.appendChild(xf);n}
                    cell.setAttribute("s",next.toString())
                };xfs.setAttribute("count",Xml.elements(xfs,"xf").size.toString())
            }
            "sheet.conditional" -> {
                val kind=args["kind"] ?: "greaterThan";require(kind in setOf("greaterThan","lessThan","equal","duplicateValues","clear"))
                if(kind=="clear") {Xml.elements(doc,"conditionalFormatting").filter {it.getAttribute("sqref")==ref}.forEach {it.parentNode.removeChild(it)};return}
                val existing=Xml.elements(doc,"cfRule");require(existing.size<1000)
                val dxfs=Xml.child(styles.documentElement,"dxfs") ?: styles.node("dxfs").also {append(styles,it,setOf("tableStyles","colors","extLst"))}
                val id=Xml.elements(dxfs,"dxf").size;val color=hex(args["color"] ?: "FFF2CC")
                dxfs.appendChild(styles.node("dxf") {appendChild(styles.node("fill") {appendChild(styles.node("patternFill") {setAttribute("patternType","solid");appendChild(styles.node("fgColor") {setAttribute("rgb","FF$color")});appendChild(styles.node("bgColor") {setAttribute("indexed","64")})})})});dxfs.setAttribute("count",(id+1).toString())
                existing.forEach {val n=it.getAttribute("priority").toInt();require(n in 1..1000000);it.setAttribute("priority",(n+1).toString())}
                val rule=doc.node("cfRule") {setAttribute("type",if(kind=="duplicateValues") "duplicateValues" else "cellIs");setAttribute("dxfId",id.toString());setAttribute("priority","1")
                    if(kind!="duplicateValues") {val value=args.getValue("value").toDouble();require(value.isFinite());setAttribute("operator",kind);appendChild(doc.node("formula") {textContent=value.toString()})}}
                append(doc,doc.node("conditionalFormatting") {setAttribute("sqref",ref);appendChild(rule)},setOf("dataValidations","hyperlinks","printOptions","pageMargins","pageSetup","headerFooter","drawing","legacyDrawing","tableParts","extLst"))
            }
            "sheet.validation" -> {
                val validations=Xml.child(doc.documentElement,"dataValidations") ?: doc.node("dataValidations").also {append(doc,it,setOf("hyperlinks","printOptions","pageMargins","pageSetup","headerFooter","drawing","legacyDrawing","tableParts","extLst"))}
                require(Xml.elements(validations,"dataValidation").size<1000)
                val overlapping=Xml.elements(validations,"dataValidation").filter {v->v.getAttribute("sqref").split(Regex("\\s+")).any {area->val other=CellRange.parse(area);other.left<=range.right && other.right>=range.left && other.top<=range.bottom && other.bottom>=range.top}}
                require(overlapping.all {it.getAttribute("sqref")==ref}) {"일부만 겹치는 기존 데이터 검증 범위가 있습니다."};overlapping.forEach {validations.removeChild(it)}
                val values=args.getValue("items").split('|').map {it.trim()};require(values.size in 1..50 && values.all {it.length in 1..60 && it.none {c->c in ",\"\r\n" || c.isISOControl()}} && values.distinct().size==values.size)
                val formula="\""+values.joinToString(",")+"\"";require(formula.length<=255) {"드롭다운 목록은 총 253자 이하로 입력하세요."}
                validations.appendChild(doc.node("dataValidation") {setAttribute("type","list");setAttribute("allowBlank","1");setAttribute("showDropDown","0");setAttribute("showErrorMessage","1");setAttribute("errorTitle","목록 값");setAttribute("error","목록에서 값을 선택하세요.");setAttribute("sqref",ref);appendChild(doc.node("formula1") {textContent=formula})})
                validations.setAttribute("count",Xml.elements(validations,"dataValidation").size.toString())
            }
            "sheet.table" -> {
                require(range.bottom>range.top && Xml.elements(doc,"sheetData").isNotEmpty())
                Xml.elements(doc,"mergeCell").forEach {require(!overlap(range,CellRange.parse(it.getAttribute("ref")))) {"병합 셀이 있는 범위에는 표를 만들지 않습니다."}}
                val ids=parts.keys.mapNotNull {Regex("xl/tables/table(\\d+)\\.xml").matchEntire(it)?.groupValues?.get(1)?.toInt()};require(ids.size<1000)
                val tablePaths=parts.keys.filter {it.startsWith("xl/tables/") && it.endsWith(".xml")}
                val tableIds=tablePaths.map {xml(it).documentElement.getAttribute("id").toInt()}
                val highest=maxOf(ids.maxOrNull() ?: 0,tableIds.maxOrNull() ?: 0)
                require(highest in 0 until Int.MAX_VALUE) {"표 ID 한도를 초과했습니다."}
                val id=highest+1;val path="xl/tables/table$id.xml"
                val relationshipsPath=change.target.substringBeforeLast('/')+"/_rels/"+change.target.substringAfterLast('/')+".rels"
                if(relationshipsPath !in parts) parts[relationshipsPath]="<Relationships xmlns=\"$PR\"/>".toByteArray()
                val relationships=xml(relationshipsPath)
                Xml.elements(relationships,"Relationship").filter {it.getAttribute("Type").endsWith("/table")}.forEach {r->
                    require(r.getAttribute("TargetMode")!="External") {"외부 표 관계는 보호합니다."}
                    val tablePath=java.net.URI("/"+change.target).resolve(r.getAttribute("Target")).normalize().path.removePrefix("/")
                    require(tablePath.startsWith("xl/tables/") && tablePath in parts)
                    require(!overlap(range,CellRange.parse(xml(tablePath).documentElement.getAttribute("ref")))) {"기존 표와 범위가 겹칩니다."}
                }
                val strings=if("xl/sharedStrings.xml" in parts) Xml.elements(xml("xl/sharedStrings.xml"),"si").map {Xml.elements(it,"t").joinToString("") {it.textContent}} else emptyList()
                val headings=(range.left..range.right).map {c->val cell=cell(doc,columnName(c)+range.top);require(Xml.child(cell,"f")==null);when(cell.getAttribute("t")) {"s"->strings.getOrNull(Xml.child(cell,"v")?.textContent?.toIntOrNull() ?: -1).orEmpty();"inlineStr"->Xml.elements(cell,"t").joinToString("") {it.textContent};else->Xml.child(cell,"v")?.textContent.orEmpty()}}
                require(headings.all {it.isNotBlank() && it.length<=255} && headings.map {it.lowercase()}.distinct().size==headings.size) {"첫 행에 비어 있지 않은 고유한 열 이름을 입력하세요."}
                val table=Xml.parse("<table xmlns=\"$S\"/>".toByteArray());table.documentElement.apply {setAttribute("id",id.toString());setAttribute("name","StudioTable$id");setAttribute("displayName","StudioTable$id");setAttribute("ref",ref);setAttribute("totalsRowShown","0");appendChild(table.node("autoFilter") {setAttribute("ref",ref)});appendChild(table.node("tableColumns") {setAttribute("count",headings.size.toString());headings.forEachIndexed {i,name->appendChild(table.node("tableColumn") {setAttribute("id",(i+1).toString());setAttribute("name",name)})}});appendChild(table.node("tableStyleInfo") {setAttribute("name","TableStyleMedium2");setAttribute("showFirstColumn","0");setAttribute("showLastColumn","0");setAttribute("showRowStripes","1");setAttribute("showColumnStripes","0")})}
                // Table names are workbook-wide, including tables created by other editors.
                val usedNames=parts.keys.filter {it.startsWith("xl/tables/") && it.endsWith(".xml")}.map {xml(it).documentElement.getAttribute("displayName").lowercase()}.toSet()
                var name="StudioTable$id";var suffix=1
                while(name.lowercase() in usedNames) {name="StudioTable${id}_${suffix++}"}
                table.documentElement.setAttribute("name",name);table.documentElement.setAttribute("displayName",name)
                parts[path]=Xml.encode(table)
                val rid="studioTable"+UUID.randomUUID().toString().replace("-","")
                relationships.documentElement.appendChild(relationships.createElementNS(PR,"Relationship").apply {setAttribute("Id",rid);setAttribute("Type","$R/table");setAttribute("Target","../tables/table$id.xml")})
                val tableParts=Xml.child(doc.documentElement,"tableParts") ?: doc.node("tableParts").also {append(doc,it,setOf("extLst"))}
                tableParts.appendChild(doc.node("tablePart") {setAttributeNS(R,"r:id",rid)});tableParts.setAttribute("count",Xml.elements(tableParts,"tablePart").size.toString())
                val ct=xml("[Content_Types].xml");ct.documentElement.appendChild(ct.createElementNS("http://schemas.openxmlformats.org/package/2006/content-types","Override").apply {setAttribute("PartName","/$path");setAttribute("ContentType","application/vnd.openxmlformats-officedocument.spreadsheetml.table+xml")})
            }
        }
    }
    private fun overlap(a: CellRange,b: CellRange)=a.left<=b.right && a.right>=b.left && a.top<=b.bottom && a.bottom>=b.top
}
