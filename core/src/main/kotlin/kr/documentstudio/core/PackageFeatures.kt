package kr.documentstudio.core

import java.io.File
import java.util.zip.*
import org.w3c.dom.*

/** Structural edits preserve all unrelated package parts and relationships. */
object PackageFeatures {
    private const val P="http://schemas.openxmlformats.org/presentationml/2006/main"
    private const val PR="http://schemas.openxmlformats.org/package/2006/relationships"
    private const val CT="http://schemas.openxmlformats.org/package/2006/content-types"
    private const val HP="http://www.hancom.co.kr/hwpml/2011/paragraph"
    private const val HH="http://www.hancom.co.kr/hwpml/2011/head"
    private val W=PackageDocument.W;private val A=PackageDocument.A;private val S=PackageDocument.S;private val R=PackageDocument.R
    fun apply(source: File,destination: File,styles: Map<String,Map<String,String>> = emptyMap(),change: DocumentChange? = null,attachment: File? = null) {
        require(source.canonicalPath!=destination.canonicalPath)
        val format=PackageDocument.open(source).format
        val parts=ZipFile(source).use {zip -> zip.entries().toList().associateTo(linkedMapOf()) {e -> e.name to zip.getInputStream(e).use {it.readBounded(e.size)} }}
        val docs=mutableMapOf<String,Document>()
        fun xml(path: String)=docs.getOrPut(path) {Xml.parse(parts[path] ?: error("파트 누락: $path"))}
        styles.forEach {(key,properties) ->
            val path=key.substringBefore('#'); val index=key.substringAfter('#')
            if(format==Format.XLSX) {
                val doc=xml(path);val cell=Xml.elements(doc,"c").firstOrNull {it.getAttribute("r")==index} ?: error("서식 적용 전에 셀에 값을 입력하세요.")
                sheetStyle(xml("xl/styles.xml"),cell,properties)
            } else if(format in listOf(Format.DOCX,Format.PPTX)) {
                val doc=xml(path);val texts=Xml.elements(doc,"t").filter {it.namespaceURI in listOf(W,A)}
                val text=texts.getOrNull(index.toInt()) ?: error("텍스트 런이 없습니다.")
                runStyle(doc,text,properties,format)
            } else if(format==Format.HWPX) {
                val doc=xml(path);val text=Xml.elements(doc,"t").filter {e->e.namespaceURI==HP && (0 until e.childNodes.length).none {e.childNodes.item(it) is Element}}.getOrNull(index.toInt()) ?: error("한글 런이 없습니다.")
                hangulStyle(xml("Contents/header.xml"),doc,text,properties)
            } else error("이 형식의 서식 편집은 지원하지 않습니다.")
        }
        if(change!=null) {
            val args=change.arguments
            when(change.type) {
                in SheetOperations.operations -> {require(format==Format.XLSX);SheetOperations.apply(parts,::xml,change)}
                in WordTables.operations -> {require(format==Format.DOCX);WordTables.apply(xml("word/document.xml"),change)}
                in OfficeObjects.operations -> {require(format==if(change.type.startsWith("word.")) Format.DOCX else Format.PPTX);OfficeObjects.apply(parts,::xml,change,attachment)}
                "hwpx.paragraph","hwpx.break","hwpx.layout" -> {
                    require(format==Format.HWPX);val doc=xml(change.target);val paragraphs=Xml.elements(doc,"p");val last=paragraphs.last()
                    if(change.type=="hwpx.layout") {
                        val page=Xml.elements(doc,"pagePr").firstOrNull() ?: error("이 구역에 편집 용지가 없습니다.")
                        val landscape=args["landscape"]=="true";page.setAttribute("width",if(landscape) "84188" else "59528");page.setAttribute("height",if(landscape) "59528" else "84188")
                        args["marginMm"]?.let {val mm=it.toDouble();require(mm in 0.0..60.0);val margin=Xml.child(page,"margin") ?: error("용지 여백 속성 누락");listOf("top","bottom","left","right").forEach {margin.setAttribute(it,(mm*7200/25.4).toInt().toString())}}
                    } else {
                        val id=(paragraphs.maxOfOrNull {it.getAttribute("id").toLongOrNull() ?: 0L} ?: 0L)+1
                        doc.documentElement.appendChild(doc.element(HP,"hp:p") {setAttribute("id",id.toString());setAttribute("paraPrIDRef",last.getAttribute("paraPrIDRef"));setAttribute("styleIDRef",last.getAttribute("styleIDRef"));setAttribute("pageBreak",if(change.type=="hwpx.break") "1" else "0");setAttribute("columnBreak","0");setAttribute("merged","0");appendChild(doc.element(HP,"hp:run") {setAttribute("charPrIDRef",Xml.elements(last,"run").first().getAttribute("charPrIDRef"));appendChild(doc.element(HP,"hp:t") {textContent=args["text"] ?: ""})})})
                    }
                    Xml.elements(doc,"linesegarray").forEach {it.parentNode.removeChild(it)}
                }
                "word.paragraph","word.table","word.break","word.layout" -> {
                    require(format==Format.DOCX);val doc=xml("word/document.xml");val body=Xml.elements(doc,"body").single()
                    val sect=Xml.child(body,"sectPr")
                    fun add(e: Element) {body.insertBefore(e,sect)}
                    when(change.type) {
                        "word.paragraph" -> add(doc.element(W,"w:p") {appendChild(doc.element(W,"w:r") {appendChild(doc.element(W,"w:t") {textContent=args["text"] ?: "새 문단"})})})
                        "word.break" -> add(doc.element(W,"w:p") {appendChild(doc.element(W,"w:r") {appendChild(doc.element(W,"w:br") {setAttributeNS(W,"w:type","page")})})})
                        "word.table" -> {
                            val rows=args.getValue("rows").toInt();val cols=args.getValue("columns").toInt();require(rows in 1..50 && cols in 1..20)
                            add(doc.element(W,"w:tbl") {
                                appendChild(doc.element(W,"w:tblPr") {appendChild(doc.element(W,"w:tblW") {setAttributeNS(W,"w:w","0");setAttributeNS(W,"w:type","auto")})})
                                appendChild(doc.element(W,"w:tblGrid") {repeat(cols) {appendChild(doc.element(W,"w:gridCol") {setAttributeNS(W,"w:w",(9000/cols).toString())})}})
                                repeat(rows) {appendChild(doc.element(W,"w:tr") {repeat(cols) {appendChild(doc.element(W,"w:tc") {appendChild(doc.element(W,"w:p") {appendChild(doc.element(W,"w:r") {appendChild(doc.element(W,"w:t"))})})})}})}
                            })
                        }
                        else -> {
                            val pr=sect ?: doc.element(W,"w:sectPr").also {body.appendChild(it)}
                            val size=Xml.child(pr,"pgSz") ?: doc.element(W,"w:pgSz").also {pr.appendChild(it)}
                            val landscape=args["landscape"]=="true";size.setAttributeNS(W,"w:w",if(landscape) "16838" else "11906");size.setAttributeNS(W,"w:h",if(landscape) "11906" else "16838");size.setAttributeNS(W,"w:orient",if(landscape) "landscape" else "portrait")
                            args["marginMm"]?.let {val mm=it.toDouble();require(mm in 0.0..60.0);val margin=Xml.child(pr,"pgMar") ?: doc.element(W,"w:pgMar").also {pr.appendChild(it)};listOf("top","bottom","left","right").forEach {side->margin.setAttributeNS(W,"w:$side",(mm*1440/25.4).toInt().toString())}}
                            args["columns"]?.let {val count=it.toInt();require(count in 1..4);val columns=Xml.child(pr,"cols") ?: doc.element(W,"w:cols").also(pr::appendChild);Xml.elements(columns,"col").forEach(columns::removeChild);columns.setAttributeNS(W,"w:num",count.toString());columns.setAttributeNS(W,"w:equalWidth","1");columns.setAttributeNS(W,"w:space","720")}
                            order(pr,listOf("headerReference","footerReference","footnotePr","endnotePr","type","pgSz","pgMar","paperSrc","pgBorders","lnNumType","pgNumType","cols","formProt","vAlign","noEndnote","titlePg","textDirection","bidi","rtlGutter","docGrid","printerSettings","sectPrChange"))
                        }
                    }
                }
                "sheet.rename","sheet.add","sheet.duplicate","sheet.delete" -> {
                    require(format==Format.XLSX);val wb=xml("xl/workbook.xml");val rels=xml("xl/_rels/workbook.xml.rels");val ct=xml("[Content_Types].xml")
                    val sheets=Xml.elements(wb,"sheets").single();val all=Xml.elements(sheets,"sheet")
                    val rel=Xml.elements(rels,"Relationship").firstOrNull {it.getAttribute("Target").removePrefix("/").let {t->t==change.target || "xl/$t"==change.target}}
                    val selected=all.firstOrNull {it.getAttributeNS(R,"id")==rel?.getAttribute("Id")}
                    when(change.type) {
                        "sheet.delete" -> {
                            require(all.size>1);require(selected!=null);val deletedIndex=all.indexOf(selected)
                            Xml.elements(wb,"definedName").forEach {name->name.getAttribute("localSheetId").toIntOrNull()?.let {i->if(i==deletedIndex) name.parentNode.removeChild(name) else if(i>deletedIndex) name.setAttribute("localSheetId",(i-1).toString())}}
                            sheets.removeChild(selected);Xml.elements(wb,"workbookView").forEach {view->view.setAttribute("activeTab","0")}
                        }
                        "sheet.rename" -> {
                            require(selected!=null);val name=args.getValue("name");validSheetName(name,all.filter {it!=selected});val old=selected.getAttribute("name")
                            parts.keys.filter {it.endsWith(".xml")}.forEach {path->val doc=xml(path);(Xml.elements(doc,"f")+Xml.elements(doc,"definedName")).forEach {f->f.textContent=renameReferences(f.textContent,old,name)};Xml.elements(doc,"hyperlink").forEach {link->if(link.hasAttribute("location")) link.setAttribute("location",renameReferences(link.getAttribute("location"),old,name))}}
                            selected.setAttribute("name",name)
                        }
                        else -> {
                            val name=args["name"] ?: "시트${all.size+1}";validSheetName(name,all)
                            val n=(all.maxOf {it.getAttribute("sheetId").toInt()}+1);var part="xl/worksheets/studio$n.xml";var suffix=n
                            while(part in parts) {suffix++;part="xl/worksheets/studio$suffix.xml"}
                            val id="studio"+java.util.UUID.randomUUID().toString().replace("-","")
                            val new=wb.element(S,"sheet") {setAttribute("name",name);setAttribute("sheetId",n.toString());setAttributeNS(R,"r:id",id)};sheets.appendChild(new)
                            rels.documentElement.appendChild(rels.element(PR,"Relationship") {setAttribute("Id",id);setAttribute("Type","$R/worksheet");setAttribute("Target",part.removePrefix("xl/"))})
                            ct.documentElement.appendChild(ct.element(CT,"Override") {setAttribute("PartName","/$part");setAttribute("ContentType","application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml")})
                            if(change.type=="sheet.duplicate") {
                                require(change.target in parts);parts[part]=parts.getValue(change.target)
                                val original=Xml.parse(parts.getValue(change.target))
                                require(listOf("drawing","legacyDrawing","tableParts","oleObjects","pivotTableParts").none {Xml.elements(original,it).isNotEmpty()}) {"표·차트·그림·피벗 개체가 있는 시트 복제는 지원하지 않습니다. 원본 관계를 보호합니다."}
                                val oldRel=change.target.substringBeforeLast('/')+"/_rels/"+change.target.substringAfterLast('/')+".rels"
                                parts[oldRel]?.let {parts[part.substringBeforeLast('/')+"/_rels/"+part.substringAfterLast('/')+".rels"]=it}
                            } else parts[part]="<worksheet xmlns='$S'><sheetData/></worksheet>".toByteArray()
                        }
                    }
                    invalidateCalc(wb)
                }
                "sheet.merge","sheet.unmerge","sheet.width","sheet.height","sheet.freeze","sheet.filter" -> {
                    require(format==Format.XLSX);val doc=xml(change.target);val root=doc.documentElement
                    when(change.type) {
                        "sheet.merge","sheet.unmerge" -> {
                            val range=args.getValue("range").uppercase();val addresses=range.split(':');require(addresses.size==2 && addresses.all(::validAddress))
                            val start=addresses[0];val end=addresses[1];require(column(start)<=column(end) && row(start)<=row(end))
                            val merges=Xml.elements(doc,"mergeCells").firstOrNull() ?: doc.element(S,"mergeCells").also {root.insertBefore(it,Xml.elements(doc,"hyperlinks").firstOrNull() ?: Xml.elements(doc,"pageMargins").firstOrNull())}
                            if(change.type=="sheet.unmerge") Xml.elements(merges,"mergeCell").filter {it.getAttribute("ref")==range}.forEach {merges.removeChild(it)}
                            else {
                                require(Xml.elements(merges,"mergeCell").none {m -> val a=m.getAttribute("ref").split(':');a.size==2 && column(a[0])<=column(end) && column(a[1])>=column(start) && row(a[0])<=row(end) && row(a[1])>=row(start)}) {"기존 병합 범위와 겹칩니다."}
                                require(Xml.elements(doc,"c").none {c -> val a=c.getAttribute("r");a!=start && column(a) in column(start)..column(end) && row(a) in row(start)..row(end) && (Xml.child(c,"v")!=null || Xml.child(c,"f")!=null || Xml.child(c,"is")!=null)}) {"병합할 다른 셀에 값이 있습니다. 데이터 보호를 위해 먼저 비워 주세요."}
                                merges.appendChild(doc.element(S,"mergeCell") {setAttribute("ref",range)})
                            };merges.setAttribute("count",Xml.elements(merges,"mergeCell").size.toString())
                        }
                        "sheet.width" -> {
                            val col=args.getValue("column").toInt();val width=args.getValue("width").toDouble();require(col in 1..16384 && width in 1.0..255.0)
                            val cols=Xml.elements(doc,"cols").firstOrNull() ?: doc.element(S,"cols").also {root.insertBefore(it,Xml.elements(doc,"sheetData").single())}
                            require(Xml.elements(cols,"col").none {col in it.getAttribute("min").toInt()..it.getAttribute("max").toInt() && it.getAttribute("min")!=it.getAttribute("max")}) {"기존 열 그룹 폭은 개별 수정할 수 없습니다."}
                            val c=Xml.elements(cols,"col").firstOrNull {it.getAttribute("min")==col.toString()} ?: doc.element(S,"col").also {cols.insertBefore(it,Xml.elements(cols,"col").firstOrNull {it.getAttribute("min").toInt()>col})}
                            c.setAttribute("min",col.toString());c.setAttribute("max",col.toString());c.setAttribute("width",width.toString());c.setAttribute("customWidth","1")
                        }
                        "sheet.height" -> {
                            val num=args.getValue("row").toInt();val height=args.getValue("height").toDouble();require(num in 1..1048576 && height in 1.0..409.0)
                            val data=Xml.elements(doc,"sheetData").single();val r=Xml.elements(data,"row").firstOrNull {it.getAttribute("r")==num.toString()} ?: doc.element(S,"row").also {it.setAttribute("r",num.toString());data.insertBefore(it,Xml.elements(data,"row").firstOrNull {it.getAttribute("r").toInt()>num})}
                            r.setAttribute("ht",height.toString());r.setAttribute("customHeight","1")
                        }
                        "sheet.freeze" -> {
                            val views=Xml.elements(doc,"sheetViews").firstOrNull() ?: doc.element(S,"sheetViews").also {root.insertBefore(it,root.firstChild)}
                            val view=Xml.child(views,"sheetView") ?: doc.element(S,"sheetView").also {it.setAttribute("workbookViewId","0");views.appendChild(it)}
                            Xml.child(view,"pane")?.let {view.removeChild(it)}
                            val rows=args.getValue("rows").toInt();val cols=args.getValue("columns").toInt();require(rows in 0..1000 && cols in 0..1000)
                            if(rows+cols>0) view.insertBefore(doc.element(S,"pane") {setAttribute("xSplit",cols.toString());setAttribute("ySplit",rows.toString());setAttribute("topLeftCell",columnName(cols+1)+(rows+1));setAttribute("activePane",if(cols>0 && rows>0) "bottomRight" else if(cols>0) "topRight" else "bottomLeft");setAttribute("state","frozen")},view.firstChild)
                        }
                        else -> {val range=args.getValue("range").uppercase();require(range.split(':').all(::validAddress));Xml.elements(doc,"autoFilter").forEach {root.removeChild(it)};root.insertBefore(doc.element(S,"autoFilter") {setAttribute("ref",range)},Xml.elements(doc,"mergeCells").firstOrNull() ?: Xml.elements(doc,"pageMargins").firstOrNull())}
                    }
                }
                "slide.add","slide.duplicate","slide.delete","slide.move" -> {
                    require(format==Format.PPTX);val presentation=xml("ppt/presentation.xml");val rels=xml("ppt/_rels/presentation.xml.rels");val ct=xml("[Content_Types].xml")
                    val list=Xml.elements(presentation,"sldIdLst").single();val all=Xml.elements(list,"sldId")
                    val rel=Xml.elements(rels,"Relationship").firstOrNull {it.getAttribute("Target").let {t->t.removePrefix("/")==change.target || "ppt/$t"==change.target}}
                    val selected=all.firstOrNull {it.getAttributeNS(R,"id")==rel?.getAttribute("Id")}
                    when(change.type) {
                        "slide.delete" -> {require(all.size>1);require(selected!=null);list.removeChild(selected)}
                        "slide.move" -> {require(selected!=null);val index=args.getValue("index").toInt();require(index in all.indices);list.removeChild(selected);val rest=Xml.elements(list,"sldId");list.insertBefore(selected,rest.getOrNull(index))}
                        else -> {
                            val n=all.maxOf {it.getAttribute("id").toInt()}+1;var suffix=n;var part="ppt/slides/studio$suffix.xml";while(part in parts) {suffix++;part="ppt/slides/studio$suffix.xml"}
                            val id="studio"+java.util.UUID.randomUUID().toString().replace("-","")
                            list.appendChild(presentation.element(P,"p:sldId") {setAttribute("id",n.toString());setAttributeNS(R,"r:id",id)})
                            rels.documentElement.appendChild(rels.element(PR,"Relationship") {setAttribute("Id",id);setAttribute("Type","$R/slide");setAttribute("Target",part.removePrefix("ppt/"))})
                            ct.documentElement.appendChild(ct.element(CT,"Override") {setAttribute("PartName","/$part");setAttribute("ContentType","application/vnd.openxmlformats-officedocument.presentationml.slide+xml")})
                            val template=if(change.target in parts) change.target else PackageDocument.open(source).sections.first().id
                            val slide=Xml.parse(parts.getValue(template))
                            if(change.type=="slide.add") {val tree=Xml.elements(slide,"spTree").single();(0 until tree.childNodes.length).map {tree.childNodes.item(it)}.filterIsInstance<Element>().filter {it.localName !in listOf("nvGrpSpPr","grpSpPr")}.forEach {tree.removeChild(it)};Xml.elements(slide,"timing").forEach {it.parentNode.removeChild(it)}}
                            parts[part]=Xml.encode(slide)
                            val oldRel=template.substringBeforeLast('/')+"/_rels/"+template.substringAfterLast('/')+".rels"
                            parts[oldRel]?.let {bytes -> val rd=Xml.parse(bytes);require(change.type!="slide.duplicate" || Xml.elements(rd,"Relationship").none {it.getAttribute("Type")=="$R/notesSlide"}) {"발표자 노트가 있는 슬라이드의 복제는 지원하지 않습니다. 원본 노트 관계를 보호합니다."};if(change.type=="slide.add") Xml.elements(rd,"Relationship").filter {it.getAttribute("Type")!="$R/slideLayout"}.forEach {it.parentNode.removeChild(it)};parts[part.substringBeforeLast('/')+"/_rels/"+part.substringAfterLast('/')+".rels"]=Xml.encode(rd)}
                        }
                    }
                }
                "slide.text","slide.rectangle","slide.transform" -> {
                    require(format==Format.PPTX);val doc=xml(change.target);val tree=Xml.elements(doc,"spTree").single()
                    if(change.type=="slide.transform") {
                        val shape=if(args["shapeId"]!=null) Xml.elements(tree,"cNvPr").firstOrNull {it.getAttribute("id")==args["shapeId"]}?.parentNode?.parentNode as? Element else Xml.elements(tree,"sp").getOrNull(args.getValue("shape").toInt())
                        require(shape!=null) {"도형이 없습니다."}
                        val pr=Xml.child(shape,"spPr") ?: error("도형 속성이 없습니다.");val xfrm=Xml.child(pr,"xfrm") ?: doc.element(A,"a:xfrm").also {pr.insertBefore(it,pr.firstChild)}
                        transform(doc,xfrm,args)
                    } else {
                        val id=(Xml.elements(doc,"cNvPr").maxOfOrNull {it.getAttribute("id").toInt()} ?: 1)+1
                        tree.appendChild(doc.element(P,"p:sp") {
                            appendChild(doc.element(P,"p:nvSpPr") {appendChild(doc.element(P,"p:cNvPr") {setAttribute("id",id.toString());setAttribute("name","Studio $id")});appendChild(doc.element(P,"p:cNvSpPr") {setAttribute("txBox",if(change.type=="slide.text") "1" else "0")});appendChild(doc.element(P,"p:nvPr"))})
                            appendChild(doc.element(P,"p:spPr") {appendChild(doc.element(A,"a:xfrm") {transform(doc,this,args)});appendChild(doc.element(A,"a:prstGeom") {setAttribute("prst","rect");appendChild(doc.element(A,"a:avLst"))});appendChild(doc.element(A,if(change.type=="slide.text") "a:noFill" else "a:solidFill") {if(change.type!="slide.text") appendChild(doc.element(A,"a:srgbClr") {setAttribute("val",hex(args["color"] ?: "315D54"))})})})
                            appendChild(doc.element(P,"p:txBody") {appendChild(doc.element(A,"a:bodyPr"));appendChild(doc.element(A,"a:lstStyle"));appendChild(doc.element(A,"a:p") {appendChild(doc.element(A,"a:r") {appendChild(doc.element(A,"a:rPr") {setAttribute("lang","ko-KR");setAttribute("sz","2400")});appendChild(doc.element(A,"a:t") {textContent=args["text"] ?: ""})})})})
                        })
                    }
                }
                else -> error("지원하지 않는 구조 편집입니다.")
            }
        }
        if(format==Format.XLSX && change!=null && change.type.startsWith("sheet.")) {
            parts.keys.filter {it.startsWith("xl/worksheets/") && it.endsWith(".xml")}.forEach {path->val doc=xml(path);Xml.elements(doc,"c").filter {Xml.child(it,"f")!=null}.forEach {c->Xml.child(c,"v")?.let {c.removeChild(it)}}}
            invalidateCalc(xml("xl/workbook.xml"));parts.remove("xl/calcChain.xml")
            listOf("xl/_rels/workbook.xml.rels","[Content_Types].xml").forEach {path->val doc=xml(path);(Xml.elements(doc,"Relationship")+Xml.elements(doc,"Override")).filter {it.getAttribute("Type").endsWith("/calcChain") || it.getAttribute("PartName")=="/xl/calcChain.xml"}.forEach {it.parentNode.removeChild(it)}}
        }
        docs.forEach {(path,doc)->
            if(doc.documentElement.localName=="worksheet" && doc.documentElement.namespaceURI==S) {order(doc.documentElement,listOf("sheetPr","dimension","sheetViews","sheetFormatPr","cols","sheetData","sheetCalcPr","sheetProtection","protectedRanges","scenarios","autoFilter","sortState","dataConsolidate","customSheetViews","mergeCells","phoneticPr","conditionalFormatting","dataValidations","hyperlinks","printOptions","pageMargins","pageSetup","headerFooter","rowBreaks","colBreaks","customProperties","cellWatches","ignoredErrors","smartTags","drawing","legacyDrawing","legacyDrawingHF","picture","oleObjects","controls","webPublishItems","tableParts","extLst"))}
            parts[path]=Xml.encode(doc)
        }
        ZipOutputStream(destination.outputStream()).use {zip ->parts.forEach {(path,bytes)->zip.putNextEntry(ZipEntry(path).apply {if(path=="mimetype") {method=ZipEntry.STORED;size=bytes.size.toLong();crc=CRC32().apply {update(bytes)}.value}});zip.write(bytes);zip.closeEntry()}}
        PackageDocument.open(destination)
    }
    private fun row(a: String)=a.dropWhile {it.isLetter()}.toInt()
    private fun order(parent: Element,names: List<String>) {
        val children=(0 until parent.childNodes.length).map {parent.childNodes.item(it)}.filterIsInstance<Element>()
        children.forEach {parent.removeChild(it)}
        children.sortedBy {names.indexOf(it.localName).let {i->if(i<0) names.size else i}}.forEach {parent.appendChild(it)}
    }
    private fun renameReferences(formula: String,old: String,name: String): String {
        val tokens=Regex("\"(?:[^\"]|\"\")*\"|'(?:[^']|'')+'!|[\\p{L}_][\\p{L}\\p{N}_.]*!")
        return tokens.replace(formula) {m->val raw=m.value;if(raw.startsWith('"') || m.range.first>0 && formula[m.range.first-1]==']') raw else {val sheet=raw.dropLast(1).removeSurrounding("'").replace("''","'");if(sheet.equals(old,true)) "'"+name.replace("'","''")+"'!" else raw}}
    }
    private fun validSheetName(name: String,others: List<Element>) {require(name.isNotBlank() && name.length<=31 && name.none {it in "[]:*?/\\"} && !name.startsWith('\'') && !name.endsWith('\'') && others.none {it.getAttribute("name").equals(name,true)}) {"시트 이름이 잘못되었거나 중복됩니다."}}
    private fun invalidateCalc(wb: Document) {val calc=Xml.elements(wb,"calcPr").firstOrNull() ?: wb.element(S,"calcPr").also {wb.documentElement.appendChild(it)};calc.setAttribute("fullCalcOnLoad","1");calc.setAttribute("forceFullCalc","1")}
    private fun Document.element(ns: String,name: String,configure: Element.()->Unit = {})=createElementNS(ns,name).apply(configure)
    private fun hex(s: String)=s.removePrefix("#").uppercase().also {require(it.matches(Regex("[0-9A-F]{6}"))) {"색상은 RRGGBB로 입력하세요."}}
    private fun transform(doc: Document,xfrm: Element,args: Map<String,String>) {
        val x=args["x"]?.toDouble() ?: 1.0;val y=args["y"]?.toDouble() ?: 1.0;val w=args["width"]?.toDouble() ?: 12.0;val h=args["height"]?.toDouble() ?: 3.0
        require(listOf(x,y,w,h).all {it.isFinite() && it in 0.0..100.0} && w>0 && h>0)
        listOf("off","ext").forEach {Xml.child(xfrm,it)?.let {e->xfrm.removeChild(e)}}
        xfrm.insertBefore(doc.element(A,"a:off") {setAttribute("x",(x*360000).toLong().toString());setAttribute("y",(y*360000).toLong().toString())},xfrm.firstChild)
        xfrm.appendChild(doc.element(A,"a:ext") {setAttribute("cx",(w*360000).toLong().toString());setAttribute("cy",(h*360000).toLong().toString())})
        args["rotation"]?.let {val degrees=it.toDouble();require(degrees.isFinite());xfrm.setAttribute("rot",(degrees*60000).toLong().toString())}
    }
    private fun runStyle(doc: Document,text: Element,props: Map<String,String>,format: Format) {
        val ns=if(format==Format.DOCX) W else A;val prefix=if(format==Format.DOCX) "w:" else "a:"
        val run=text.parentNode as Element;val pr=Xml.child(run,"rPr") ?: doc.element(ns,prefix+"rPr").also {run.insertBefore(it,run.firstChild)}
        fun property(name: String,value: String) {val e=Xml.child(pr,name) ?: doc.element(ns,prefix+name).also {pr.appendChild(it)};e.setAttributeNS(ns,prefix+"val",value)}
        if(format==Format.DOCX) {
            listOf("italic" to "i","underline" to "u","strike" to "strike").forEach {(key,name)->props[key]?.let {property(name,if(name=="u") if(it=="true") "single" else "none" else if(it=="true") "1" else "0")}}
            props["size"]?.let {val size=it.toDouble();require(size in 1.0..200.0);property("sz",(size*2).toInt().toString());property("szCs",(size*2).toInt().toString())}
            props["color"]?.let {property("color",hex(it))}
            props["highlight"]?.let {require(it in listOf("yellow","green","cyan","magenta","none"));property("highlight",it)}
            props["position"]?.let {require(it in listOf("superscript","subscript","baseline"));property("vertAlign",it)}
            props["spacing"]?.let {val n=it.toDouble();require(n in -20.0..100.0);property("spacing",(n*20).toInt().toString())}
            props["scale"]?.let {val n=it.toInt();require(n in 20..400);property("w",n.toString())}
            props["font"]?.let {font->require(font.isNotBlank() && font.length<=80);val e=Xml.child(pr,"rFonts") ?: doc.element(W,"w:rFonts").also {pr.insertBefore(it,pr.firstChild)};listOf("ascii","hAnsi","eastAsia","cs").forEach {e.setAttributeNS(W,"w:$it",font)}}
            var paragraph: Node?=run;while(paragraph!=null && (paragraph as? Element)?.localName!="p") paragraph=paragraph.parentNode
            if(paragraph is Element && props.keys.any {it in listOf("align","lineSpacing","style","beforePt","afterPt","indentMm","firstLineMm")}) {
                val ppr=Xml.child(paragraph,"pPr") ?: doc.element(W,"w:pPr").also {paragraph.insertBefore(it,paragraph.firstChild)}
                props["align"]?.let {require(it in listOf("left","center","right","both"));val jc=Xml.child(ppr,"jc") ?: doc.element(W,"w:jc").also {ppr.appendChild(it)};jc.setAttributeNS(W,"w:val",it)}
                props["lineSpacing"]?.let {val spacing=it.toDouble();require(spacing in 0.5..5.0);val e=Xml.child(ppr,"spacing") ?: doc.element(W,"w:spacing").also {ppr.appendChild(it)};e.setAttributeNS(W,"w:line",(spacing*240).toInt().toString());e.setAttributeNS(W,"w:lineRule","auto")}
                props["style"]?.let {val e=Xml.child(ppr,"pStyle") ?: doc.element(W,"w:pStyle").also {ppr.insertBefore(it,ppr.firstChild)};e.setAttributeNS(W,"w:val",it)}
                listOf("beforePt" to "before","afterPt" to "after").forEach {(key,name)->props[key]?.let {val n=it.toDouble();require(n in 0.0..200.0);val e=Xml.child(ppr,"spacing") ?: doc.element(W,"w:spacing").also(ppr::appendChild);e.setAttributeNS(W,"w:$name",(n*20).toInt().toString())}}
                props["indentMm"]?.let {val n=it.toDouble();require(n in 0.0..100.0);val e=Xml.child(ppr,"ind") ?: doc.element(W,"w:ind").also(ppr::appendChild);e.setAttributeNS(W,"w:left",(n*1440/25.4).toInt().toString())}
                props["firstLineMm"]?.let {val n=it.toDouble();require(n in -100.0..100.0);val e=Xml.child(ppr,"ind") ?: doc.element(W,"w:ind").also(ppr::appendChild);e.removeAttributeNS(W,"firstLine");e.removeAttributeNS(W,"hanging");e.setAttributeNS(W,if(n<0) "w:hanging" else "w:firstLine",(kotlin.math.abs(n)*1440/25.4).toInt().toString())}
                order(ppr,listOf("pStyle","keepNext","keepLines","pageBreakBefore","framePr","widowControl","numPr","suppressLineNumbers","pBdr","shd","tabs","suppressAutoHyphens","kinsoku","wordWrap","overflowPunct","topLinePunct","autoSpaceDE","autoSpaceDN","bidi","adjustRightInd","snapToGrid","spacing","ind","contextualSpacing","mirrorIndents","suppressOverlap","jc","textDirection","textAlignment","textboxTightWrap","outlineLvl","divId","cnfStyle","rPr","sectPr","pPrChange"))
            }
        } else {
            listOf("italic" to "i","bold" to "b").forEach {(key,name)->props[key]?.let {pr.setAttribute(name,if(it=="true") "1" else "0")}}
            props["underline"]?.let {pr.setAttribute("u",if(it=="true") "sng" else "none")};props["strike"]?.let {pr.setAttribute("strike",if(it=="true") "sngStrike" else "noStrike")}
            props["size"]?.let {val size=it.toDouble();require(size in 1.0..200.0);pr.setAttribute("sz",(size*100).toInt().toString())}
            props["color"]?.let {color->listOf("solidFill","noFill","gradFill","pattFill").forEach {Xml.child(pr,it)?.let {e->pr.removeChild(e)}};pr.insertBefore(doc.element(A,"a:solidFill") {appendChild(doc.element(A,"a:srgbClr") {setAttribute("val",hex(color))})},pr.firstChild)}
            props["font"]?.let {font->require(font.isNotBlank() && font.length<=80);listOf("latin","ea","cs").forEach {name-> val e=Xml.child(pr,name) ?: doc.element(A,"a:$name").also {pr.appendChild(it)};e.setAttribute("typeface",font)}}
        }
        if(format==Format.DOCX) order(pr,listOf("rStyle","rFonts","b","bCs","i","iCs","caps","smallCaps","strike","dstrike","outline","shadow","emboss","imprint","noProof","snapToGrid","vanish","webHidden","color","spacing","w","kern","position","sz","szCs","highlight","u","effect","bdr","shd","fitText","vertAlign","rtl","cs","em","lang","eastAsianLayout","specVanish","oMath","rPrChange"))
        else order(pr,listOf("ln","noFill","solidFill","gradFill","blipFill","pattFill","grpFill","effectLst","effectDag","highlight","uLnTx","uLn","uFillTx","uFill","latin","ea","cs","sym","hlinkClick","hlinkMouseOver","rtl","extLst"))
    }
    private fun sheetStyle(styles: Document,cell: Element,props: Map<String,String>) {
        props["size"]?.let {require(it.toDoubleOrNull()?.let {n->n in 1.0..200.0}==true) {"글자 크기는 1~200 pt입니다."}}
        props["font"]?.let {require(it.isNotBlank() && it.length<=80)}
        val xfs=Xml.elements(styles,"cellXfs").single();val oldIndex=cell.getAttribute("s").toIntOrNull() ?: 0
        val xf=Xml.elements(xfs,"xf").getOrNull(oldIndex)?.cloneNode(true) as? Element ?: error("셀 서식 참조 오류")
        if(props.keys.any {it in listOf("bold","italic","size","font","color")}) {
            val fonts=Xml.elements(styles,"fonts").single();val oldFont=xf.getAttribute("fontId").toIntOrNull() ?: 0
            val font=Xml.elements(fonts,"font")[oldFont].cloneNode(true) as Element
            listOf("bold" to "b","italic" to "i","size" to "sz","font" to "name","color" to "color").forEach {(key,name)->props[key]?.let {v->Xml.child(font,name)?.let {font.removeChild(it)};font.appendChild(styles.element(S,name) {if(name=="color") setAttribute("rgb","FF"+hex(v)) else setAttribute("val",if(name in listOf("b","i")) if(v=="true") "1" else "0" else v)})}}
            xf.setAttribute("fontId",Xml.elements(fonts,"font").size.toString());xf.setAttribute("applyFont","1");fonts.appendChild(font);fonts.setAttribute("count",Xml.elements(fonts,"font").size.toString())
        }
        props["background"]?.let {color->val fills=Xml.elements(styles,"fills").single();xf.setAttribute("fillId",Xml.elements(fills,"fill").size.toString());xf.setAttribute("applyFill","1");fills.appendChild(styles.element(S,"fill") {appendChild(styles.element(S,"patternFill") {setAttribute("patternType","solid");appendChild(styles.element(S,"fgColor") {setAttribute("rgb","FF"+hex(color))});appendChild(styles.element(S,"bgColor") {setAttribute("indexed","64")})})});fills.setAttribute("count",Xml.elements(fills,"fill").size.toString())}
        props["numberFormat"]?.let {code->require(code.length<=120);val formats=Xml.elements(styles,"numFmts").firstOrNull() ?: styles.element(S,"numFmts").also {styles.documentElement.insertBefore(it,styles.documentElement.firstChild)};val id=(Xml.elements(formats,"numFmt").maxOfOrNull {it.getAttribute("numFmtId").toInt()} ?: 163)+1;formats.appendChild(styles.element(S,"numFmt") {setAttribute("numFmtId",id.toString());setAttribute("formatCode",code)});formats.setAttribute("count",Xml.elements(formats,"numFmt").size.toString());xf.setAttribute("numFmtId",id.toString());xf.setAttribute("applyNumberFormat","1")}
        props["align"]?.let {require(it in listOf("left","center","right"));val alignment=Xml.child(xf,"alignment") ?: styles.element(S,"alignment").also {xf.appendChild(it)};alignment.setAttribute("horizontal",it);xf.setAttribute("applyAlignment","1")}
        order(xf,listOf("alignment","protection","extLst"))
        val index=Xml.elements(xfs,"xf").size;xfs.appendChild(xf);xfs.setAttribute("count",(index+1).toString());cell.setAttribute("s",index.toString())
    }
    private fun hangulStyle(header: Document,doc: Document,text: Element,props: Map<String,String>) {
        val run=text.parentNode as Element;val chars=Xml.elements(header,"charProperties").single();val old=Xml.elements(chars,"charPr").first {it.getAttribute("id")==run.getAttribute("charPrIDRef")};val char=old.cloneNode(true) as Element
        val id=Xml.elements(chars,"charPr").maxOf {it.getAttribute("id").toInt()}+1;char.setAttribute("id",id.toString())
        listOf("bold","italic").forEach {key->props[key]?.let {value->Xml.child(char,key)?.let {char.removeChild(it)};if(value=="true") char.appendChild(header.element(HH,"hh:$key"))}}
        props["size"]?.let {val size=it.toDouble();require(size in 1.0..200.0);char.setAttribute("height",(size*100).toInt().toString())}
        props["color"]?.let {char.setAttribute("textColor","#"+hex(it))}
        props["underline"]?.let {val e=Xml.child(char,"underline") ?: header.element(HH,"hh:underline").also {char.appendChild(it)};e.setAttribute("type",if(it=="true") "BOTTOM" else "NONE");e.setAttribute("shape","SOLID");e.setAttribute("color","#000000")}
        props["strike"]?.let {val e=Xml.child(char,"strikeout") ?: header.element(HH,"hh:strikeout").also {char.appendChild(it)};e.setAttribute("shape",if(it=="true") "SOLID" else "NONE");e.setAttribute("color","#000000")}
        props["font"]?.let {name->require(name.isNotBlank() && name.length<=80);val ref=Xml.child(char,"fontRef") ?: error("글꼴 참조 누락");Xml.elements(header,"fontface").forEach {face ->val all=Xml.elements(face,"font");val font=all.first().cloneNode(true) as Element;val fid=all.maxOf {it.getAttribute("id").toInt()}+1;font.setAttribute("id",fid.toString());font.setAttribute("face",name);face.appendChild(font);face.setAttribute("fontCnt",(all.size+1).toString());val key=face.getAttribute("lang").lowercase();if(key in listOf("hangul","latin","hanja","japanese","other","symbol","user")) ref.setAttribute(key,fid.toString())}}
        order(char,listOf("fontRef","ratio","spacing","relSz","offset","bold","italic","underline","strikeout","outline","shadow","emboss","engrave","supscript","subscript"))
        chars.appendChild(char);chars.setAttribute("itemCnt",Xml.elements(chars,"charPr").size.toString());run.setAttribute("charPrIDRef",id.toString())
        val paragraph=run.parentNode as Element
        if(props.keys.any {it in listOf("align","lineSpacing")}) {
            val paras=Xml.elements(header,"paraProperties").single();val original=Xml.elements(paras,"paraPr").first {it.getAttribute("id")==paragraph.getAttribute("paraPrIDRef")};val para=original.cloneNode(true) as Element;val pid=Xml.elements(paras,"paraPr").maxOf {it.getAttribute("id").toInt()}+1;para.setAttribute("id",pid.toString())
            props["align"]?.let {val align=when(it) {"left"->"LEFT";"center"->"CENTER";"right"->"RIGHT";"both"->"JUSTIFY";else->error("문단 정렬 값 오류")};(Xml.child(para,"align") ?: error("문단 정렬 속성 누락")).setAttribute("horizontal",align)}
            props["lineSpacing"]?.let {val spacing=it.toDouble();require(spacing in 0.5..5.0);val e=Xml.elements(para,"lineSpacing").firstOrNull() ?: error("줄 간격 속성 누락");e.setAttribute("type","PERCENT");e.setAttribute("value",(spacing*100).toInt().toString())}
            paras.appendChild(para);paras.setAttribute("itemCnt",Xml.elements(paras,"paraPr").size.toString());paragraph.setAttribute("paraPrIDRef",pid.toString())
        }
        Xml.elements(doc,"linesegarray").forEach {it.parentNode.removeChild(it)}
    }
}
