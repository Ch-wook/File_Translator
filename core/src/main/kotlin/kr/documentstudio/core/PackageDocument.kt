package kr.documentstudio.core

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

data class Field(val id: String, val label: String, val text: String, val bold: Boolean = false,val styles: Map<String,String> = emptyMap())
data class Section(val id: String, val title: String, val fields: List<Field>, val cells: Map<String, String> = emptyMap(),val cellStyles: Map<String,Map<String,String>> = emptyMap(),val sourceName: String=title,val date1904: Boolean=false)

object Xml {
    fun parse(bytes: ByteArray): Document {
        require(bytes.size <= 8 * 1024 * 1024) { "XML 파트 크기 제한(8 MiB)을 초과했습니다." }
        val scan = bytes.toString(Charsets.ISO_8859_1).replace("\u0000", "").lowercase()
        require(!scan.contains("<!doctype") && !scan.contains("<!entity")) { "외부 엔티티와 DTD가 있는 문서는 허용하지 않습니다." }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        return factory.newDocumentBuilder().apply { setEntityResolver { _, _ -> error("외부 엔티티 차단") } }
            .parse(ByteArrayInputStream(bytes))
    }
    fun encode(doc: Document): ByteArray = ByteArrayOutputStream().also { out ->
        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.INDENT, "no")
        }.transform(DOMSource(doc), StreamResult(out))
    }.toByteArray()
    fun elements(root: Node, name: String): List<Element> {
        val list = if (root is Document) root.getElementsByTagNameNS("*", name) else (root as Element).getElementsByTagNameNS("*", name)
        return (0 until list.length).map { list.item(it) as Element }
    }
    fun child(root: Element, name: String) = (0 until root.childNodes.length).map { root.childNodes.item(it) }
        .filterIsInstance<Element>().firstOrNull { it.localName == name }
}

/** Bounded package editor. It exposes text runs/cells, not a paginated Office rendering engine. */
class PackageDocument private constructor(val source: File, val format: Format, val sections: List<Section>) {
    companion object {
        const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        const val S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
        const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        fun open(file: File): PackageDocument = ZipFile(file).use { zip ->
            val entries = zip.entries().toList()
            require(entries.size <= 5000) { "ZIP 파트 수 제한(5,000)을 초과했습니다." }
            require(entries.map { it.name }.distinct().size == entries.size) { "중복 ZIP 파트가 있습니다." }
            require(entries.none { it.name.startsWith('/') || it.name.split('/').contains("..") || it.name.contains('\\') }) { "안전하지 않은 ZIP 경로입니다." }
            var total = 0L
            entries.filterNot { it.isDirectory }.forEach { e ->
                require(e.size in 0..(48L * 1024 * 1024)) { "ZIP 파트 크기가 잘못되었습니다." }
                total += e.size
                require(total <= 48L * 1024 * 1024) { "압축 해제 용량 제한(48 MiB)을 초과했습니다." }
                require(e.size < 1024 * 1024 || e.compressedSize > 0 && e.size / e.compressedSize < 300) { "과도하게 압축된 문서는 열 수 없습니다." }
            }
            val names = entries.map { it.name }.toSet()
            require(names.none { it.startsWith("_xmlsignatures/") || it.endsWith("vbaProject.bin") }) { "서명 또는 매크로 문서는 편집하지 않습니다." }
            fun read(path: String): ByteArray = zip.getEntry(path)?.let { entry ->
                require(entry.size <= 8L * 1024 * 1024) { "XML 크기 제한 초과" }
                zip.getInputStream(entry).use { it.readBounded(8L * 1024 * 1024) }
            } ?: error("필수 파트 누락: $path")
            val candidates = listOf("word/document.xml", "xl/workbook.xml", "ppt/presentation.xml").filter { it in names }
            val hwpx = "Contents/content.hpf" in names && "Contents/header.xml" in names &&
                names.any { it.matches(Regex("Contents/section\\d+\\.xml")) }
            require(candidates.size + (if (hwpx) 1 else 0) == 1) { "알 수 없거나 모호한 ZIP 문서입니다." }
            if (hwpx) {
                require("mimetype" in names && read("mimetype").toString(Charsets.UTF_8).trim() == "application/hwp+zip") { "HWPX MIME이 올바르지 않습니다." }
            } else {
                require("[Content_Types].xml" in names && "_rels/.rels" in names) { "Office 패키지 메타데이터가 누락되었습니다." }
                Xml.parse(read("[Content_Types].xml"))
            }
            val format = when { hwpx -> Format.HWPX; candidates[0].startsWith("word") -> Format.DOCX; candidates[0].startsWith("xl") -> Format.XLSX; else -> Format.PPTX }
            val header=if(hwpx) Xml.parse(read("Contents/header.xml")) else null
            fun textSection(path: String, title: String): Section {
                val doc = Xml.parse(read(path))
                val fields = Xml.elements(doc, "t").filter { e ->
                    e.namespaceURI in listOf(W, A, "http://www.hancom.co.kr/hwpml/2011/paragraph") &&
                        (0 until e.childNodes.length).none { e.childNodes.item(it) is Element }
                }.mapIndexed { i, e ->
                    val bold = if (format == Format.DOCX) Xml.child(e.parentNode as Element, "rPr")?.let { pr ->
                        Xml.child(pr, "b")?.getAttributeNS(W, "val")?.let { it !in listOf("0", "false", "off") } ?: false
                    } ?: false else false
                    val pr=Xml.child(e.parentNode as Element,"rPr")
                    val props=buildMap<String,String> {
                        if(format==Format.DOCX && pr!=null) {
                            fun valOf(name: String)=Xml.child(pr,name)?.getAttributeNS(W,"val")?.takeIf {it.isNotEmpty()}
                            valOf("sz")?.toDoubleOrNull()?.let {put("size",(it/2).toString())};valOf("color")?.takeIf {it!="auto"}?.let {put("color",it)}
                            Xml.child(pr,"rFonts")?.getAttributeNS(W,"eastAsia")?.takeIf {it.isNotBlank()}?.let {put("font",it)}
                            listOf("italic" to "i","strike" to "strike").forEach {(key,name)->if(Xml.child(pr,name)!=null) put(key,(valOf(name) !in listOf("0","false","off")).toString())}
                            valOf("u")?.let {put("underline",(it!="none").toString())}
                        } else if(format==Format.PPTX && pr!=null) {
                            pr.getAttribute("sz").toDoubleOrNull()?.let {put("size",(it/100).toString())};listOf("bold" to "b","italic" to "i").forEach {(key,name)->if(pr.hasAttribute(name)) put(key,(pr.getAttribute(name)=="1").toString())}
                            Xml.child(pr,"solidFill")?.let {Xml.child(it,"srgbClr")?.getAttribute("val")?.let {color->put("color",color)}}
                        } else if(format==Format.HWPX && header!=null) {
                            val ref=(e.parentNode as Element).getAttribute("charPrIDRef");val char=Xml.elements(header,"charPr").firstOrNull {it.getAttribute("id")==ref}
                            if(char!=null) {char.getAttribute("height").toDoubleOrNull()?.let {put("size",(it/100).toString())};char.getAttribute("textColor").takeIf {it.isNotBlank()}?.let {put("color",it.removePrefix("#"))};listOf("bold","italic").forEach {put(it,(Xml.child(char,it)!=null).toString())};Xml.child(char,"underline")?.let {put("underline",(it.getAttribute("type")!="NONE").toString())}}
                        }
                    }
                    Field("$path#$i", "텍스트 ${i + 1}", e.textContent, bold,props)
                }
                require(fields.size <= 10000) { "편집 가능한 텍스트 수 제한을 초과했습니다." }
                return Section(path, title, fields)
            }
            val sections = when (format) {
                Format.DOCX -> listOf(textSection("word/document.xml", "본문 (텍스트 런 편집)"))
                Format.HWPX -> names.filter { it.matches(Regex("Contents/section\\d+\\.xml")) }
                    .sortedBy { it.substringAfter("section").substringBefore('.').toInt() }
                    .mapIndexed { i, path -> textSection(path, "구역 ${i + 1}") }
                Format.PPTX -> {
                    val rels = Xml.elements(Xml.parse(read("ppt/_rels/presentation.xml.rels")), "Relationship").associate {
                        it.getAttribute("Id") to resolve("ppt", it.getAttribute("Target"))
                    }
                    Xml.elements(Xml.parse(read("ppt/presentation.xml")), "sldId").mapIndexed { i, e ->
                        textSection(rels[e.getAttributeNS(R, "id")] ?: error("슬라이드 관계 누락"), "슬라이드 ${i + 1} (텍스트 편집)")
                    }
                }
                Format.XLSX -> {
                    val styles=if("xl/styles.xml" in names) Xml.parse(read("xl/styles.xml")) else null
                    val fonts=styles?.let {Xml.elements(it,"font")} ?: emptyList();val fills=styles?.let {Xml.elements(it,"fill")} ?: emptyList()
                    val formats=styles?.let {Xml.elements(it,"numFmt").associate {n->n.getAttribute("numFmtId") to n.getAttribute("formatCode")}} ?: emptyMap()
                    val xfs=styles?.let {Xml.child(it.documentElement,"cellXfs")?.let {xf->Xml.elements(xf,"xf")}} ?: emptyList()
                    val parsedStyles=xfs.map {xf->buildMap<String,String> {
                        fonts.getOrNull(xf.getAttribute("fontId").toIntOrNull() ?: 0)?.let {font->listOf("bold" to "b","italic" to "i").forEach {(key,name)->Xml.child(font,name)?.let {put(key,(it.getAttribute("val") !in listOf("0","false")).toString())}};Xml.child(font,"sz")?.getAttribute("val")?.let {put("size",it)};Xml.child(font,"color")?.getAttribute("rgb")?.takeIf {it.length==8}?.let {put("color",it.takeLast(6))}}
                        fills.getOrNull(xf.getAttribute("fillId").toIntOrNull() ?: 0)?.let {fill->Xml.elements(fill,"fgColor").firstOrNull()?.getAttribute("rgb")?.takeIf {it.length==8}?.let {put("background",it.takeLast(6))}}
                        (formats[xf.getAttribute("numFmtId")] ?: CellFormatting.builtins[xf.getAttribute("numFmtId")])?.let {put("numberFormat",it)};Xml.child(xf,"alignment")?.getAttribute("horizontal")?.let {put("align",it)}
                    }}
                    val strings = if ("xl/sharedStrings.xml" in names) Xml.elements(Xml.parse(read("xl/sharedStrings.xml")), "si").map {
                        Xml.elements(it, "t").joinToString("") { t -> t.textContent }
                    } else emptyList()
                    val rels = Xml.elements(Xml.parse(read("xl/_rels/workbook.xml.rels")), "Relationship").associate {
                        it.getAttribute("Id") to resolve("xl", it.getAttribute("Target"))
                    }
                    val workbook=Xml.parse(read("xl/workbook.xml"));val date1904=Xml.elements(workbook,"workbookPr").firstOrNull()?.getAttribute("date1904") in listOf("1","true")
                    Xml.elements(workbook, "sheet").map { e ->
                        val path = rels[e.getAttributeNS(R, "id")] ?: error("시트 관계 누락")
                        val cellNodes=Xml.elements(Xml.parse(read(path)), "c")
                        val cells = cellNodes.associate { cell ->
                            val value = Xml.child(cell, "v")?.textContent ?: ""
                            val text = Xml.child(cell, "f")?.let { "=" + it.textContent } ?: when (cell.getAttribute("t")) {
                                "s" -> strings.getOrNull(value.toIntOrNull() ?: -1) ?: error("공유 문자열 인덱스 오류")
                                "inlineStr" -> Xml.elements(cell, "t").joinToString("") { it.textContent }
                                "b" -> if (value == "1") "TRUE" else "FALSE"
                                else -> value
                            }
                            cell.getAttribute("r") to text
                        }
                        Section(path, e.getAttribute("name") + if (e.getAttribute("state") in listOf("hidden", "veryHidden")) " (숨김)" else "", emptyList(), cells,cellNodes.associate {c->c.getAttribute("r") to (parsedStyles.getOrNull(c.getAttribute("s").toIntOrNull() ?: 0) ?: emptyMap())},e.getAttribute("name"),date1904)
                    }
                }
                else -> error("지원하지 않는 패키지")
            }
            require(sections.isNotEmpty()) { "문서 내용이 없습니다." }
            PackageDocument(file, format, sections)
        }
        private fun resolve(base: String, target: String): String {
            require(!target.contains(':') && !target.contains('\\')) { "외부 문서 파트는 열 수 없습니다." }
            val pieces = ArrayDeque<String>()
            (if (target.startsWith('/')) target else "$base/$target").split('/').forEach { p ->
                when (p) { "", "." -> Unit; ".." -> { require(pieces.isNotEmpty()); pieces.removeLast() }; else -> pieces.add(p) }
            }
            return pieces.joinToString("/")
        }
    }

    fun write(destination: File, edits: Edits) {
        require(destination.canonicalPath != source.canonicalPath) { "임시 파일로만 저장할 수 있습니다." }
        val replacements = mutableMapOf<String, ByteArray>()
        ZipFile(source).use { zip ->
            sections.forEach { section ->
                val relevant = edits.values.filterKeys { it.startsWith(section.id + "#") }
                val bold = edits.bold.filterKeys { it.startsWith(section.id + "#") }
                if (relevant.isEmpty() && bold.isEmpty()) return@forEach
                val doc = Xml.parse(zip.getInputStream(zip.getEntry(section.id)).use { it.readBounded(8L * 1024 * 1024) })
                if (format == Format.XLSX) {
                    relevant.forEach { (key, value) -> putCell(doc, key.substringAfter('#'), value) }
                    Xml.elements(doc, "dimension").forEach { it.parentNode.removeChild(it) }
                } else {
                    val nodes = Xml.elements(doc, "t").filter { e -> e.namespaceURI in listOf(W, A, "http://www.hancom.co.kr/hwpml/2011/paragraph") &&
                        (0 until e.childNodes.length).none { e.childNodes.item(it) is Element } }
                    nodes.forEachIndexed { i, node ->
                        val key = "${section.id}#$i"
                        relevant[key]?.let { value ->
                            require(!value.contains('\n') && !value.contains('\r')) { "텍스트 런 안의 줄바꿈 편집은 지원하지 않습니다." }
                            node.textContent = value
                            node.setAttributeNS("http://www.w3.org/XML/1998/namespace", "xml:space", "preserve")
                        }
                        if (format == Format.DOCX) bold[key]?.let { enabled ->
                            val run = node.parentNode as Element
                            val pr = Xml.child(run, "rPr") ?: doc.createElementNS(W, "w:rPr").also { run.insertBefore(it, run.firstChild) }
                            val b = Xml.child(pr, "b") ?: doc.createElementNS(W, "w:b").also { pr.appendChild(it) }
                            b.setAttributeNS(W, "w:val", if (enabled) "1" else "0")
                        }
                    }
                    if (format == Format.HWPX) Xml.elements(doc, "linesegarray").forEach { it.parentNode.removeChild(it) }
                }
                replacements[section.id] = Xml.encode(doc)
            }
            if (format == Format.XLSX && edits.values.isNotEmpty()) {
                // Cached formula results must never masquerade as recalculated results after a change.
                sections.forEach { section ->
                    val doc = Xml.parse(replacements[section.id] ?: zip.getInputStream(zip.getEntry(section.id)).use { it.readBounded(8L * 1024 * 1024) })
                    var changed = false
                    Xml.elements(doc, "c").filter { Xml.child(it, "f") != null }.forEach { cell ->
                        Xml.child(cell, "v")?.let { cell.removeChild(it); changed = true }
                    }
                    if (changed) replacements[section.id] = Xml.encode(doc)
                }
                val wb = Xml.parse(zip.getInputStream(zip.getEntry("xl/workbook.xml")).use { it.readBounded(8L * 1024 * 1024) })
                val calc = Xml.elements(wb, "calcPr").firstOrNull() ?: wb.createElementNS(S, "calcPr").also { wb.documentElement.appendChild(it) }
                calc.setAttribute("fullCalcOnLoad", "1"); calc.setAttribute("forceFullCalc", "1")
                replacements["xl/workbook.xml"] = Xml.encode(wb)
                if (zip.getEntry("xl/calcChain.xml") != null) {
                    listOf("xl/_rels/workbook.xml.rels", "[Content_Types].xml").forEach { path ->
                        val doc = Xml.parse(zip.getInputStream(zip.getEntry(path)).use { it.readBounded(8L * 1024 * 1024) })
                        (Xml.elements(doc, "Relationship") + Xml.elements(doc, "Override")).filter {
                            it.getAttribute("Type").endsWith("/calcChain") || it.getAttribute("PartName") == "/xl/calcChain.xml"
                        }.forEach { it.parentNode.removeChild(it) }
                        replacements[path] = Xml.encode(doc)
                    }
                }
            }
            ZipOutputStream(destination.outputStream()).use { out ->
                zip.entries().toList().forEach { old ->
                    if (format == Format.XLSX && edits.values.isNotEmpty() && old.name == "xl/calcChain.xml") return@forEach
                    val bytes = replacements[old.name]
                    val entry = ZipEntry(old.name).apply {
                        time = old.time
                        comment = old.comment
                        if (old.name == "mimetype" || old.method == ZipEntry.STORED) {
                            method = ZipEntry.STORED
                            if (bytes != null) { size = bytes.size.toLong(); crc = CRC32().apply { update(bytes) }.value }
                            else { size = old.size; crc = old.crc }
                        }
                    }
                    out.putNextEntry(entry)
                    if (bytes != null) out.write(bytes) else zip.getInputStream(old).use { input ->
                        require(input.copyBounded(out,old.size)==old.size) {"ZIP 실제 크기와 메타데이터가 일치하지 않습니다."}
                    }
                    out.closeEntry()
                }
            }
        }
        if(edits.styles.isNotEmpty()) {
            val styled=File(destination.parentFile,"styled-${java.util.UUID.randomUUID()}.bin")
            try {PackageFeatures.apply(destination,styled,edits.styles);styled.copyTo(destination,true)} finally {styled.delete()}
        }
        val verified = open(destination)
        require(verified.format == format && verified.sections.map { it.id } == sections.map { it.id }) { "저장 검증 실패" }
    }

    private fun putCell(doc: Document, address: String, input: String) {
        require(validAddress(address)) { "올바른 셀 주소를 입력하세요 (A1–XFD1048576)." }
        require(input.length<=32767) {"셀 문자열은 32,767자 이하만 지원합니다."}
        val data = Xml.elements(doc, "sheetData").firstOrNull() ?: error("시트 데이터가 없습니다.")
        val rowNum = address.dropWhile { it.isLetter() }.toInt()
        val row = Xml.elements(data, "row").firstOrNull { it.getAttribute("r") == rowNum.toString() } ?: doc.createElementNS(S, "row").also { r ->
            r.setAttribute("r", rowNum.toString())
            data.insertBefore(r, Xml.elements(data, "row").firstOrNull { it.getAttribute("r").toInt() > rowNum })
        }
        val cell = Xml.elements(row, "c").firstOrNull { it.getAttribute("r") == address } ?: doc.createElementNS(S, "c").also { c ->
            c.setAttribute("r", address)
            row.insertBefore(c, Xml.elements(row, "c").firstOrNull { column(it.getAttribute("r")) > column(address) })
        }
        require(Xml.child(cell, "f")?.getAttribute("t") !in listOf("array", "shared", "dataTable")) { "배열·공유·데이터 테이블 수식은 수정할 수 없습니다." }
        listOf("v", "f", "is").forEach { n -> Xml.child(cell, n)?.let { cell.removeChild(it) } }
        cell.removeAttribute("t")
        when {
            input.startsWith('=') -> { require(input.length > 1); cell.appendChild(doc.createElementNS(S, "f").apply { textContent = input.drop(1) }) }
            input.isNotEmpty() && input.toDoubleOrNull()?.isFinite() == true -> cell.appendChild(doc.createElementNS(S, "v").apply { textContent = input })
            else -> {
                cell.setAttribute("t", "inlineStr")
                cell.appendChild(doc.createElementNS(S, "is").apply { appendChild(doc.createElementNS(S, "t").apply {
                    textContent = if (input.startsWith('\'')) input.drop(1) else input
                    setAttributeNS("http://www.w3.org/XML/1998/namespace", "xml:space", "preserve")
                }) })
            }
        }
    }
}

fun column(address: String): Int = address.takeWhile { it.isLetter() }.fold(0) { n, c -> n * 26 + (c.uppercaseChar() - 'A' + 1) }
fun validAddress(address: String) = address.matches(Regex("[A-Z]{1,3}[1-9][0-9]{0,6}")) && column(address) <= 16384 && (address.dropWhile { it.isLetter() }.toIntOrNull() ?: Int.MAX_VALUE) <= 1048576
fun columnName(number: Int): String { var n = number; var result = ""; while (n > 0) { n--; result = ('A' + n % 26) + result; n /= 26 }; return result }

internal fun java.io.InputStream.readBounded(limit: Long): ByteArray = ByteArrayOutputStream().also {copyBounded(it,limit)}.toByteArray()
internal fun java.io.InputStream.copyBounded(out: java.io.OutputStream,limit: Long): Long {
    val buffer=ByteArray(65536);var count=0L
    while(true) {val n=read(buffer);if(n<0) break;count+=n;require(count<=limit) {"ZIP 실제 해제 크기 제한을 초과했습니다."};out.write(buffer,0,n)}
    return count
}
