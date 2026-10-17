package kr.documentstudio.core

import kr.dogfoot.hwp2hwpx.Hwp2Hwpx
import kr.dogfoot.hwplib.`object`.HWPFile
import kr.dogfoot.hwplib.`object`.bodytext.control.*
import kr.dogfoot.hwplib.`object`.bodytext.control.gso.GsoControl
import kr.dogfoot.hwplib.`object`.bodytext.control.gso.GsoControlType
import kr.dogfoot.hwplib.`object`.bodytext.control.gso.ControlPicture
import kr.dogfoot.hwplib.`object`.bodytext.paragraph.Paragraph
import kr.dogfoot.hwplib.`object`.bodytext.paragraph.text.HWPCharType
import kr.dogfoot.hwplib.`object`.docinfo.bindata.BinDataType
import kr.dogfoot.hwplib.reader.HWPReader
import kr.dogfoot.hwpxlib.writer.HWPXWriter
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

data class HangulConversionReport(val sections: Int, val paragraphs: Int, val tables: Int, val cells: Int,
    val pictures: Int, val binaries: Int, val headers: Int, val footers: Int, val footnotes: Int,
    val endnotes: Int, val equations: Int, val sourceSha256: String, val resultSha256: String) {
    fun description() = "구역 $sections · 문단 $paragraphs · 표 $tables · 셀 $cells\n" +
        "그림 $pictures · 첨부 자원 $binaries · 머리말 $headers · 꼬리말 $footers\n" +
        "각주 $footnotes · 미주 $endnotes · 수식 $equations\n\n" + LIMITATIONS
    companion object {
        const val LIMITATIONS = "문단 텍스트와 표/셀·개체 수, 첨부 자원의 일치를 점검했습니다. 글꼴·쪽 배치·줄바꿈의 시각적 동일성은 검증하지 않습니다. 원본 HWP와 점검 결과는 이 작업의 ZIP/암호 백업에 함께 보존됩니다."
    }
}

/** One-way, bounded conversion. Unsupported controls fail before output is written. */
object HangulConversion {
    private const val HP = "http://www.hancom.co.kr/hwpml/2011/paragraph"
    private val controlNames = mapOf(ControlType.Table to "tbl", ControlType.Gso to "pic",
        ControlType.Equation to "equation", ControlType.SectionDefine to "secPr", ControlType.ColumnDefine to "colPr",
        ControlType.Header to "header", ControlType.Footer to "footer", ControlType.Footnote to "footNote",
        ControlType.Endnote to "endNote", ControlType.AutoNumber to "autoNum", ControlType.NewNumber to "newNum",
        ControlType.PageHide to "pageHiding", ControlType.PageNumberPosition to "pageNum", ControlType.Bookmark to "bookmark",
        ControlType.HiddenComment to "hiddenComment")
    private val nestedNames = setOf("header", "footer", "footNote", "endNote", "hiddenComment")

    fun convert(source: File, destination: File): HangulConversionReport {
        require(source.canonicalFile != destination.canonicalFile) { "변환 결과는 원본과 다른 파일이어야 합니다." }
        // The existing detector bounds OLE streams and inflation, and rejects encryption/macros/signatures.
        require(LegacyDocument.detect(source) == Format.HWP) { "HWP 5 문서만 변환할 수 있습니다." }
        checkStreams(source)
        val hwp = HWPReader.fromFile(source) ?: error("HWP를 읽지 못했습니다.")
        val before = sourceInventory(hwp)
        val model = Hwp2Hwpx.toHWPX(hwp)
        model.versionXMLFile().applicationAnd("Document Studio").osAnd("0").appVersion("0.5.0 Android")
        val stage = File.createTempFile("hangul-conversion-", ".hwpx", destination.absoluteFile.parentFile)
        try {
            stage.outputStream().use { HWPXWriter.toStream(model, it) }
            val report = verifyInventory(before, source, stage)
            // Only publish a fully checked file. Existing destinations are never truncated by failed conversion.
            java.nio.file.Files.move(stage.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            return report
        } finally { stage.delete() }
    }

    /** Re-check an exported candidate against the HWP; also useful for independent regression probes. */
    fun verify(source: File, result: File): HangulConversionReport {
        require(LegacyDocument.detect(source) == Format.HWP)
        checkStreams(source)
        return verifyInventory(sourceInventory(HWPReader.fromFile(source)), source, result)
    }

    private class Inventory(val sections: Int) {
        val paragraphs = mutableMapOf<String, Int>()
        val controls = mutableMapOf<String, Int>()
        val tables = mutableListOf<String>()
        val binaries = mutableListOf<String>()
        val payloads = mutableMapOf<String,String>()
        val sequence = mutableMapOf<String, Int>()
        var cells = 0
        var characters = 0
        var masters = 0
        fun next(scope: String, name: String): String {
            val key = "$scope/$name"; val n = sequence[key] ?: 0; sequence[key] = n + 1
            controls[name] = (controls[name] ?: 0) + 1
            require(controls.values.sum() <= 10000) { "변환할 개체 수가 10,000개를 초과했습니다." }
            return "$key$n"
        }
        fun paragraph(scope: String, text: String) {
            characters += text.length
            require(characters <= 500000 && paragraphs.values.sum() < 10000) { "변환 범위(문단 10,000개/텍스트 500,000자)를 초과했습니다." }
            require(Charsets.UTF_8.newEncoder().canEncode(text)) { "짝이 맞지 않는 유니코드 문자가 있습니다." }
            val key = "$scope:${sha(text.toByteArray())}"
            paragraphs[key] = (paragraphs[key] ?: 0) + 1
        }
    }

    private fun sourceInventory(hwp: HWPFile): Inventory {
        val sections = hwp.bodyText.sectionList
        require(sections.size in 1..256) { "구역 수는 1~256개여야 합니다." }
        val inventory = Inventory(sections.size)
        val embedded = hwp.binData.embeddedBinaryDataList
        fun binaryId(name: String) = Regex("(?i)^bin([0-9a-f]{4,})\\.[a-z0-9]+$").matchEntire(name)?.groupValues?.get(1)?.toIntOrNull(16)
        val resources=embedded.associate { (binaryId(it.name) ?: error("첨부 자원 이름이 올바르지 않습니다.")) to sha(it.data) }
        require(resources.size==embedded.size) {"중복 첨부 자원 ID가 있습니다."}
        fun paragraphs(list: Iterable<Paragraph>, scope: String, depth: Int) {
            require(depth <= 32) { "중첩 문단 깊이가 32를 초과했습니다." }
            list.forEach { para ->
                val chars = para.text?.charList.orEmpty()
                val controls = para.controlList.orEmpty()
                require(chars.count { it.type == HWPCharType.ControlExtend } == controls.size) { "제어 문자와 개체의 연결이 올바르지 않습니다." }
                require(para.charShape?.positonShapeIdPairList?.isNotEmpty() == true) { "문단 글자 모양 정보가 없습니다." }
                val text = buildString {
                    chars.forEach { c -> when(c.type) {
                        HWPCharType.Normal -> append(c.code.toInt().toChar())
                        HWPCharType.ControlChar -> append(when(c.code.toInt()) {
                            13 -> ""; 10 -> "\n"; 24 -> "\u00ad"; 30 -> "\u00a0"; 31 -> "\u2007"
                            else -> error("지원하지 않는 HWP 문자 제어: ${c.code}")
                        })
                        HWPCharType.ControlInline -> { require(c.code.toInt() == 9) { "필드/인라인 개체는 변환하지 않습니다." }; append('\t') }
                        HWPCharType.ControlExtend -> Unit
                    } }
                }
                inventory.paragraph(scope, text)
                controls.forEach { control ->
                    val name = controlNames[control.type] ?: error("지원하지 않는 HWP 제어: ${control.type}. 원본을 보존했습니다.")
                    val child = inventory.next(scope, name)
                    when(control) {
                        is ControlTable -> {
                            val cells = control.rowList.flatMap { it.cellList }
                            require(cells.size <= 10000 && control.table.rowCount in 1..1000 && control.table.columnCount in 1..1000)
                            inventory.cells += cells.size
                            require(inventory.cells<=10000) {"변환할 셀 수가 10,000개를 초과했습니다."}
                            inventory.tables += "$child:${control.table.rowCount},${control.table.columnCount}:" + cells.map { c ->
                                val h = c.listHeader; "${h.rowIndex},${h.colIndex},${h.rowSpan},${h.colSpan}"
                            }.sorted().joinToString(";")
                            cells.forEach { c -> val h = c.listHeader; paragraphs(c.paragraphList, "$child/r${h.rowIndex}c${h.colIndex}", depth+1) }
                            control.caption?.let { paragraphs(it.paragraphList, "$child/caption", depth+1) }
                        }
                        is GsoControl -> {
                            require(control.gsoType == GsoControlType.Picture) { "그림 이외의 그리기/OLE/차트 개체는 아직 변환하지 않습니다." }
                            val ref=(control as ControlPicture).shapeComponentPicture.pictureInfo.binItemID
                            val bin=hwp.docInfo.binDataList.getOrNull(ref-1) ?: error("그림 자원 참조가 올바르지 않습니다.")
                            inventory.payloads[child]=resources[bin.binDataID] ?: error("그림 자원이 없습니다.")
                            control.caption?.let { paragraphs(it.paragraphList, "$child/caption", depth+1) }
                        }
                        is ControlEquation -> {
                            inventory.payloads[child]=sha(control.eqEdit.script.toUTF16LEString().toByteArray())
                            control.caption?.let { paragraphs(it.paragraphList, "$child/caption", depth+1) }
                        }
                        is ControlHeader -> paragraphs(control.paragraphList, child, depth+1)
                        is ControlFooter -> paragraphs(control.paragraphList, child, depth+1)
                        is ControlFootnote -> paragraphs(control.paragraphList, child, depth+1)
                        is ControlEndnote -> paragraphs(control.paragraphList, child, depth+1)
                        is ControlHiddenComment -> paragraphs(control.paragraphList, child, depth+1)
                        is ControlSectionDefine -> control.batangPageInfoList.forEach { paragraphs(it.paragraphList, "masterpage${inventory.masters++}", depth+1) }
                    }
                }
            }
        }
        sections.forEachIndexed { i, section -> paragraphs(section, "section$i", 0) }
        require(embedded.size <= 256 && hwp.docInfo.binDataList.size <= 256) { "첨부 자원 수 제한을 초과했습니다." }
        hwp.docInfo.binDataList.forEach { b ->
            require(b.property.type == BinDataType.Embedding && b.extensionForEmbedding.lowercase() in setOf("png","jpg","gif","tif","wmf","emf","svg")) { "외부 연결/알 수 없는 첨부 자원은 변환하지 않습니다." }
            require(b.binDataID in resources) { "첨부 자원이 누락되었습니다." }
        }
        embedded.forEach { inventory.binaries += sha(it.data) }
        return inventory
    }

    private fun resultInventory(file: File): Inventory {
        val pack = PackageDocument.open(file)
        require(pack.format == Format.HWPX)
        val inventory = Inventory(pack.sections.size)
        val resources=ZipFile(file).use {zip ->
            Xml.elements(Xml.parse(zip.getInputStream(zip.getEntry("Contents/content.hpf")).use {it.readBytes()}),"item").mapNotNull {item ->
                val path=item.getAttribute("href")
                if(!path.startsWith("BinData/")) null else item.getAttribute("id") to sha(zip.getInputStream(zip.getEntry(path) ?: error("그림 자원 파트 누락")).use {it.readBytes()})
            }.toMap()
        }
        fun children(e: Element) = (0 until e.childNodes.length).map { e.childNodes.item(it) }.filterIsInstance<Element>()
        fun nearestParagraph(node: Node) = generateSequence(node.parentNode) { it.parentNode }.filterIsInstance<Element>().firstOrNull { it.localName == "p" && it.namespaceURI == HP }
        fun ownText(p: Element) = buildString {
            Xml.elements(p, "t").filter { it.namespaceURI == HP && nearestParagraph(it) === p }.forEach { t ->
                (0 until t.childNodes.length).map { t.childNodes.item(it) }.forEach { n ->
                    if(n.nodeType == Node.TEXT_NODE || n.nodeType == Node.CDATA_SECTION_NODE) append(n.nodeValue)
                    else if(n is Element) append(when(n.localName) {
                        "lineBreak" -> "\n"; "hyphen" -> "\u00ad"; "nbSpace" -> "\u00a0"; "fwSpace" -> "\u2007"; "tab" -> "\t"
                        else -> error("알 수 없는 변환 텍스트 개체: ${n.localName}")
                    })
                }
            }
        }
        fun scan(e: Element, scope: String, depth: Int) {
            require(depth <= 128) { "변환 XML 중첩 제한을 초과했습니다." }
            if(e.namespaceURI == HP && e.localName == "p") inventory.paragraph(scope, ownText(e))
            if(e.localName in controlNames.values) {
                val name = e.localName
                val child = inventory.next(scope, name)
                when {
                    name == "tbl" -> {
                        val cells = children(e).filter { it.localName == "tr" }.flatMap { children(it).filter { c -> c.localName == "tc" } }
                        inventory.cells += cells.size
                        inventory.tables += "$child:${e.getAttribute("rowCnt")},${e.getAttribute("colCnt")}:" + cells.map { c ->
                            val addr = Xml.child(c,"cellAddr")!!; val span = Xml.child(c,"cellSpan")!!
                            "${addr.getAttribute("rowAddr")},${addr.getAttribute("colAddr")},${span.getAttribute("rowSpan")},${span.getAttribute("colSpan")}"
                        }.sorted().joinToString(";")
                        cells.forEach { c -> val a=Xml.child(c,"cellAddr")!!; scan(c, "$child/r${a.getAttribute("rowAddr")}c${a.getAttribute("colAddr")}", depth+1) }
                        children(e).filter { it.localName == "caption" }.forEach { scan(it, "$child/caption", depth+1) }
                        return
                    }
                    name in nestedNames -> { children(e).forEach { scan(it, child, depth+1) }; return }
                    name == "pic" || name == "equation" -> {
                        inventory.payloads[child]=if(name=="equation") sha(Xml.child(e,"script")!!.textContent.toByteArray())
                            else resources[Xml.child(e,"img")!!.getAttribute("binaryItemIDRef")] ?: error("변환 그림 참조 누락")
                        children(e).filter { it.localName == "caption" }.forEach { scan(it,"$child/caption",depth+1) }
                        return
                    }
                }
            }
            children(e).forEach { scan(it, scope, depth+1) }
        }
        ZipFile(file).use { zip ->
            val names = zip.entries().toList().map { it.name }
            pack.sections.forEachIndexed { i, section -> scan(Xml.parse(zip.getInputStream(zip.getEntry(section.id)).use { it.readBytes() }).documentElement,"section$i",0) }
            names.filter { it.matches(Regex("Contents/masterpage\\d+\\.xml")) }.sortedBy { it.substringAfter("masterpage").substringBefore('.').toInt() }.forEachIndexed { i, path ->
                scan(Xml.parse(zip.getInputStream(zip.getEntry(path)).use { it.readBytes() }).documentElement,"masterpage$i",0); inventory.masters++
            }
            names.filter { it.startsWith("BinData/") && !it.endsWith('/') }.forEach { inventory.binaries += sha(zip.getInputStream(zip.getEntry(it)).use { s -> s.readBytes() }) }
        }
        return inventory
    }

    private fun verifyInventory(before: Inventory, source: File, result: File): HangulConversionReport {
        val after = resultInventory(result)
        require(before.sections == after.sections && before.masters == after.masters) { "변환 중 구역/바탕쪽 수가 달라졌습니다." }
        require(before.paragraphs == after.paragraphs) { "변환 중 문단/셀/주석 텍스트가 달라졌습니다. 결과를 적용하지 않았습니다." }
        require(before.controls == after.controls && before.tables.sorted() == after.tables.sorted() && before.cells == after.cells) { "변환 중 표/셀/개체 구조가 달라졌습니다." }
        require(before.binaries.sorted() == after.binaries.sorted()) { "변환 중 첨부 자원이 달라졌습니다." }
        require(before.payloads==after.payloads) {"변환 중 그림 참조/수식 내용이 달라졌습니다."}
        fun count(name: String) = after.controls[name] ?: 0
        return HangulConversionReport(after.sections, after.paragraphs.values.sum(), count("tbl"), after.cells,
            count("pic"), after.binaries.size, count("header"), count("footer"), count("footNote"), count("endNote"), count("equation"), sha(source.readBytes()), sha(result.readBytes()))
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // Bound decompression before hwplib allocates arrays for embedded resources. Record levels also
    // bound recursive control parsing; merely checking the compressed OLE file's size is insufficient.
    private fun checkStreams(file: File) {
        org.apache.poi.poifs.filesystem.POIFSFileSystem(file,true).use {fs ->
            var total=0L
            fun read(entry: org.apache.poi.poifs.filesystem.DocumentEntry,compressed: Boolean): ByteArray {
                val raw=org.apache.poi.poifs.filesystem.DocumentInputStream(entry)
                val input=if(compressed) java.util.zip.InflaterInputStream(raw,java.util.zip.Inflater(true)) else raw
                return input.use {s -> val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(65536)
                    while(true) {val n=s.read(buffer);if(n<0) break;total+=n;require(total<=48L*1024*1024) {"HWP 전체 압축 해제 제한(48 MiB)을 초과했습니다."};out.write(buffer,0,n)}
                    out.toByteArray()
                }
            }
            val header=read(fs.root.getEntry("FileHeader") as org.apache.poi.poifs.filesystem.DocumentEntry,false)
            val compressed=java.nio.ByteBuffer.wrap(header,36,4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int and 1 != 0
            val modes=mutableListOf<Int>()
            fun records(bytes: ByteArray,docInfo: Boolean) {
                val b=java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                while(b.hasRemaining()) {
                    require(b.remaining()>=4) {"잘린 HWP 레코드입니다."};val h=b.int
                    val tag=h and 1023;val level=(h ushr 10) and 1023
                    val size=if(h ushr 20==4095) {require(b.remaining()>=4);b.int} else h ushr 20
                    require(level<=128 && size>=0 && size<=b.remaining()) {"HWP 레코드 크기/중첩 제한을 초과했습니다."}
                    if(docInfo && tag==18) {require(size>=2);modes+=(b.getShort(b.position()).toInt() ushr 4) and 3}
                    b.position(b.position()+size)
                }
            }
            records(read(fs.root.getEntry("DocInfo") as org.apache.poi.poifs.filesystem.DocumentEntry,compressed),true)
            (fs.root.getEntry("BodyText") as org.apache.poi.poifs.filesystem.DirectoryEntry).forEach {entry ->
                require(entry is org.apache.poi.poifs.filesystem.DocumentEntry);records(read(entry,compressed),false)
            }
            if(fs.root.hasEntry("BinData")) (fs.root.getEntry("BinData") as org.apache.poi.poifs.filesystem.DirectoryEntry).forEach {entry ->
                require(entry is org.apache.poi.poifs.filesystem.DocumentEntry)
                val id=Regex("(?i)^bin([0-9a-f]{4})\\.[a-z0-9]+$").matchEntire(entry.name)?.groupValues?.get(1)?.toInt(16) ?: error("첨부 자원 경로가 올바르지 않습니다.")
                val mode=modes.getOrNull(id-1) ?: 0
                require(mode in 0..2);read(entry,mode==1 || mode==0 && compressed)
            }
            if(fs.root.hasEntry("Scripts")) (fs.root.getEntry("Scripts") as org.apache.poi.poifs.filesystem.DirectoryEntry).forEach {entry ->
                require(entry is org.apache.poi.poifs.filesystem.DocumentEntry)
                val data=read(entry,compressed)
                val emptyScript=ByteArray(20).apply {for(i in 16..19) this[i]=(-1).toByte()}
                require(entry.name=="JScriptVersion" && data.size<=16 || entry.name=="DefaultJScript" && (data.isEmpty() || data.contentEquals(emptyScript))) {"스크립트가 있는 HWP는 변환하지 않습니다."}
            }
        }
    }
}
