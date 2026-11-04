package kr.documentstudio.core

import java.io.File
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.hslf.usermodel.HSLFSlideShow
import kr.dogfoot.hwplib.reader.HWPReader
import kr.dogfoot.hwplib.writer.HWPWriter
import kr.dogfoot.hwplib.`object`.bodytext.paragraph.Paragraph
import kr.dogfoot.hwplib.`object`.bodytext.paragraph.text.HWPCharNormal
import kr.dogfoot.hwplib.`object`.bodytext.paragraph.text.HWPCharType
import kr.dogfoot.hwplib.tool.blankfilemaker.BlankFileMaker

/** Native format round trips; deliberately separate from visual layout/rendering. */
class LegacyDocument private constructor(val source: File, val format: Format, val sections: List<Section>) {
    companion object {
        val formats = setOf(Format.DOC, Format.XLS, Format.PPT, Format.HWP)
        fun detect(file: File): Format {
            require(file.length() <= 16L * 1024 * 1024) { "구형 문서는 16 MiB 이하만 지원합니다." }
            return POIFSFileSystem(file, true).use { fs ->
                val names = fs.root.entryNames
                require("EncryptedPackage" !in names && "EncryptionInfo" !in names) { "암호화된 Office 문서는 지원하지 않습니다." }
                var entryCount=0
                fun check(dir: org.apache.poi.poifs.filesystem.DirectoryEntry,depth: Int=0) {
                    require(depth<=32) {"OLE 디렉터리 깊이 제한 초과"}
                    dir.forEach { entry ->
                        require(++entryCount<=5000) {"OLE 스트림 수 제한 초과"}
                        require(entry.name.lowercase() !in setOf("vba", "_vba_project_cur", "macros", "_vba_project", "digitalsignature")) { "매크로·서명 문서는 편집하지 않습니다." }
                        if (entry is org.apache.poi.poifs.filesystem.DirectoryEntry) check(entry,depth+1)
                    }
                }
                check(fs.root)
                when {
                    "FileHeader" in names && "BodyText" in names -> {
                        fs.createDocumentInputStream("FileHeader").use { input ->
                            val header = ByteArray(40); require(input.read(header)==40)
                            require(header.copyOfRange(0,17).toString(Charsets.US_ASCII)=="HWP Document File") { "HWP 헤더가 올바르지 않습니다." }
                            val flags = java.nio.ByteBuffer.wrap(header,36,4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                            require(flags and 0x2 == 0) { "암호화된 HWP는 지원하지 않습니다." }
                            require(flags and 0x4 == 0 || "ViewText" in names) { "배포용 HWP의 보기 데이터가 없습니다." }
                            if(flags and 1 != 0) {
                                var total=0L
                                val body=fs.root.getEntry("BodyText") as org.apache.poi.poifs.filesystem.DirectoryEntry
                                val streams=body.filterIsInstance<org.apache.poi.poifs.filesystem.DocumentEntry>()+listOfNotNull(fs.root.getEntry("DocInfo") as? org.apache.poi.poifs.filesystem.DocumentEntry)
                                streams.forEach {entry->org.apache.poi.poifs.filesystem.DocumentInputStream(entry).use {raw->java.util.zip.InflaterInputStream(raw,java.util.zip.Inflater(true)).use {data->val buffer=ByteArray(65536);while(true) {val n=data.read(buffer);if(n<0) break;total+=n;require(total<=48L*1024*1024) {"HWP 압축 해제 용량 제한(48 MiB) 초과"}}}}}
                            }
                        }
                        Format.HWP
                    }
                    "WordDocument" in names -> Format.DOC
                    "Workbook" in names || "Book" in names -> Format.XLS
                    "PowerPoint Document" in names -> Format.PPT
                    else -> error("지원하지 않는 OLE 컨테이너입니다.")
                }
            }
        }
        /** Opening a distributable HWP for viewing must not grant editing rights. */
        fun hwpReadOnlyReason(file: File): String? = POIFSFileSystem(file,true).use { fs ->
            fs.createDocumentInputStream("FileHeader").use {input ->
                val header=ByteArray(40);require(input.read(header)==40) {"HWP 헤더가 잘렸습니다."}
                require(header.copyOfRange(0,17).toString(Charsets.US_ASCII)=="HWP Document File") {"HWP 헤더가 올바르지 않습니다."}
                val flags=java.nio.ByteBuffer.wrap(header,36,4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                if(flags and 0x4 != 0) "배포용 HWP는 읽기 전용으로만 볼 수 있습니다." else null
            }
        }
        fun open(file: File): LegacyDocument {
            val format = detect(file)
            require(format!=Format.HWP || hwpReadOnlyReason(file)==null) {"배포용 HWP는 읽기 전용입니다. 본문 추출 편집을 허용하지 않습니다."}
            val sections = when (format) {
                Format.XLS -> HSSFWorkbook(file.inputStream()).use { wb ->
                    (0 until wb.numberOfSheets).map { i ->
                        val sheet = wb.getSheetAt(i)
                        val cells = buildMap {
                            sheet.forEach { row -> row.forEach { c ->
                                require(size < 100000) { "셀 수 제한을 초과했습니다." }
                                put(columnName(c.columnIndex+1)+(c.rowIndex+1), when(c.cellType) {
                                    CellType.FORMULA -> "="+c.cellFormula
                                    CellType.NUMERIC -> c.numericCellValue.toString()
                                    CellType.BOOLEAN -> if(c.booleanCellValue) "TRUE" else "FALSE"
                                    CellType.ERROR -> "#ERROR!"
                                    else -> c.toString()
                                })
                            } }
                        }
                        Section("legacy/sheet$i", sheet.sheetName, emptyList(), cells)
                    }
                }
                Format.DOC -> HWPFDocument(file.inputStream()).use { doc ->
                    val range = doc.range
                    listOf(Section("legacy/body", "본문 텍스트 런", (0 until range.numCharacterRuns()).mapNotNull { i ->
                        val run=range.getCharacterRun(i); val text=run.text().trimEnd('\r','\u0007')
                        if(text.any { it < ' ' && it!='\t' } || text.isEmpty()) null else Field("legacy/body#$i", "텍스트 ${i+1}", text, run.isBold)
                    }))
                }
                Format.PPT -> HSLFSlideShow(file.inputStream()).use { show ->
                    show.slides.mapIndexed { i, slide ->
                        val runs=slide.textParagraphs.flatten().flatMap { it.textRuns }
                        Section("legacy/slide$i", "슬라이드 ${i+1}", runs.mapIndexed { j, r -> Field("legacy/slide$i#$j","텍스트 ${j+1}",r.rawText,r.isBold) })
                    }
                }
                Format.HWP -> {
                    val doc=HWPReader.fromFile(file)
                    doc.bodyText.sectionList.mapIndexed { i, sec ->
                        val fields=mutableListOf<Field>()
                        sec.forEachIndexed { j, p -> chunks(p).forEachIndexed { k, chunk ->
                            fields.add(Field("legacy/section$i#$j:$k", "문단 ${j+1} · 런 ${k+1}", chunk.third))
                        } }
                        Section("legacy/section$i","구역 ${i+1}",fields)
                    }
                }
                else -> error("구형 형식 오류")
            }
            require(sections.isNotEmpty() && sections.sumOf { it.fields.size } <= 10000) { "내용이 없거나 편집 크기 제한을 초과했습니다." }
            return LegacyDocument(file,format,sections)
        }
        fun create(format: Format, file: File) {
            when(format) {
                Format.XLS -> HSSFWorkbook().use { wb -> wb.createSheet("시트1"); file.outputStream().use { wb.write(it) } }
                Format.PPT -> HSLFSlideShow().use { show -> show.createSlide(); file.outputStream().use { show.write(it) } }
                Format.HWP -> HWPWriter.toFile(BlankFileMaker.make(),file.path)
                else -> error("이 구형 형식은 기존 문서만 편집합니다.")
            }
        }
        private fun chunks(p: Paragraph): List<Triple<Int, Int, String>> {
            val chars=p.text?.charList ?: return emptyList()
            val list=mutableListOf<Triple<Int,Int,String>>(); var start=-1
            chars.forEachIndexed { i,ch ->
                val normal=ch.type==HWPCharType.Normal && ch.code.toInt() and 0xffff >= 32
                if(normal && start<0) start=i
                if(!normal && start>=0) {list.add(Triple(start,i,chars.subList(start,i).joinToString("") {(it as HWPCharNormal).ch.toString()}));start=-1}
            }
            if(start>=0) list.add(Triple(start,chars.size,chars.subList(start,chars.size).joinToString("") {(it as HWPCharNormal).ch.toString()}))
            if(list.isEmpty()) {val end=chars.indexOfLast {it.code.toInt()==13}.let {if(it<0) chars.size else it};list.add(Triple(end,end,""))}
            return list
        }
    }
    fun write(destination: File, edits: Edits) {
        require(source.canonicalPath != destination.canonicalPath)
        when(format) {
            Format.XLS -> HSSFWorkbook(source.inputStream()).use { wb ->
                edits.values.forEach { (id, value) ->
                    val i=id.substringBefore('#').substringAfter("legacy/sheet").toInt(); val address=id.substringAfter('#')
                    require(validAddress(address) && column(address)<=256 && address.dropWhile {it.isLetter()}.toInt()<=65536) { "XLS 셀 범위는 A1–IV65536입니다." }
                    val sheet=wb.getSheetAt(i); val r=address.dropWhile {it.isLetter()}.toInt()-1
                    val c=(sheet.getRow(r) ?: sheet.createRow(r)).let { it.getCell(column(address)-1) ?: it.createCell(column(address)-1) }
                    require(!c.isPartOfArrayFormulaGroup) { "배열 수식은 편집하지 않습니다." }
                    when {
                        value.startsWith('=') -> c.cellFormula=value.drop(1)
                        value.toDoubleOrNull()?.isFinite()==true -> c.setCellValue(value.toDouble())
                        else -> c.setCellValue(value.removePrefix("'"))
                    }
                }
                wb.forceFormulaRecalculation=true
                destination.outputStream().use {wb.write(it)}
            }
            Format.DOC -> HWPFDocument(source.inputStream()).use { doc ->
                val range=doc.range
                val indices=(edits.values.keys+edits.bold.keys).map { it.substringAfter('#').toInt() }.distinct().sortedDescending()
                indices.forEach { i ->
                    val run=range.getCharacterRun(i); val old=run.text().trimEnd('\r','\u0007')
                    edits.bold["legacy/body#$i"]?.let {run.setBold(it)}
                    edits.values["legacy/body#$i"]?.let { value ->
                        require(value.none {it<' ' && it!='\t'} && old.isNotEmpty()) {"구형 DOC 텍스트 런에는 문단 나누기를 입력할 수 없습니다."}
                        run.replaceText(old,value,0)
                    }
                }
                destination.outputStream().use {doc.write(it)}
            }
            Format.PPT -> HSLFSlideShow(source.inputStream()).use { show ->
                show.slides.forEachIndexed { i, slide -> slide.textParagraphs.flatten().flatMap {it.textRuns}.forEachIndexed { j, run ->
                    edits.values["legacy/slide$i#$j"]?.let {run.setText(it)}
                    edits.bold["legacy/slide$i#$j"]?.let {run.setBold(it)}
                } }
                destination.outputStream().use {show.write(it)}
            }
            Format.HWP -> {
                val doc=HWPReader.fromFile(source)
                doc.bodyText.sectionList.forEachIndexed { i,sec -> sec.forEachIndexed { j,p ->
                    val original=chunks(p); val chars=p.text?.charList ?: return@forEachIndexed
                    original.withIndex().toList().asReversed().forEach { (k,chunk) ->
                        val value=edits.values["legacy/section$i#$j:$k"] ?: return@forEach
                        require(value.none {it<' ' || it.isSurrogate()}) {"HWP 런은 줄바꿈·서로게이트 문자를 지원하지 않습니다."}
                        require(p.rangeTag==null) {"영역 태그가 있는 HWP 문단은 안전하게 수정할 수 없습니다."}
                        val before=chars.take(chunk.first).sumOf {it.charSize}.toLong()
                        val oldLength=chunk.second-chunk.first; val delta=value.length-oldLength
                        p.charShape?.positonShapeIdPairList?.forEach { pair ->
                            if(pair.position>=before+oldLength) pair.position+=delta
                            else if(pair.position>before) pair.position=before+(pair.position-before).coerceAtMost(value.length.toLong())
                        }
                        chars.subList(chunk.first,chunk.second).clear()
                        chars.addAll(chunk.first,value.map { ch -> HWPCharNormal().apply {code=ch.code} })
                        p.header.characterCount=p.text.charSize.toLong(); p.deleteLineSeg()
                    }
                } }
                HWPWriter.toFile(doc,destination.path)
            }
            else -> error("구형 형식 오류")
        }
        val merged=File(destination.parentFile,"compound-${java.util.UUID.randomUUID()}.bin")
        try {
            POIFSFileSystem(source.inputStream()).use {original->POIFSFileSystem(destination,true).use {changed->
                fun copy(from: org.apache.poi.poifs.filesystem.DirectoryEntry,to: org.apache.poi.poifs.filesystem.DirectoryEntry) {
                    from.forEach {entry->
                        if(entry is org.apache.poi.poifs.filesystem.DirectoryEntry) {
                            val target=if(to.hasEntry(entry.name)) to.getEntry(entry.name) as org.apache.poi.poifs.filesystem.DirectoryEntry else to.createDirectory(entry.name);copy(entry,target)
                        } else {if(to.hasEntry(entry.name)) to.getEntry(entry.name).delete();org.apache.poi.poifs.filesystem.DocumentInputStream(entry as org.apache.poi.poifs.filesystem.DocumentEntry).use {to.createDocument(entry.name,it)}}
                    }
                }
                copy(changed.root,original.root);merged.outputStream().use {original.writeFilesystem(it)}
            }}
            merged.copyTo(destination,true)
        } finally {merged.delete()}
        require(open(destination).format==format) { "원본 형식 저장 검증 실패" }
    }
}
