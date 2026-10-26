package kr.documentstudio.core

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipFile
import kr.dogfoot.hwplib.reader.HWPReader
import kr.dogfoot.hwplib.`object`.bodytext.control.ControlTable
import kr.dogfoot.hwplib.`object`.bodytext.paragraph.Paragraph
import kr.dogfoot.hwplib.`object`.bodytext.paragraph.text.HWPCharType
import kr.dogfoot.hwplib.`object`.docinfo.CharShape
import kr.dogfoot.hwpxlib.reader.HWPXReader
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.apache.poi.poifs.filesystem.DirectoryEntry
import org.apache.poi.poifs.filesystem.DocumentEntry
import org.apache.poi.poifs.filesystem.DocumentInputStream
import org.w3c.dom.Element

/** Independent byte-level checks of files exported by the actual mobile Rhwp scenarios. */
object MobileRoundTripTool {
    @JvmStatic fun main(args: Array<String>) {
        val dir=File(args.single())
        val png=File(dir,"mobile-image.png").readBytes()
        verifyHwp(dir,png)
        verifyHwpx(dir,png)
        println("MOBILE_INDEPENDENT_ROUND_TRIP HWP/HWPX: partial character runs, adjacent formatting, table contents/structure and exact image payload passed.")
    }
    private fun paragraphs(p: Paragraph): List<Paragraph> = buildList {
        add(p)
        p.controlList?.filterIsInstance<ControlTable>()?.forEach {t->t.rowList.forEach {r->r.cellList.forEach {c->c.paragraphList.forEach {addAll(paragraphs(it))}}}}
    }
    private fun text(p: Paragraph)=p.text?.charList?.filter {it.type==HWPCharType.Normal}?.joinToString("") {it.code.toChar().toString()} ?: ""
    private fun hwpRuns(file: File): Pair<String,List<CharShape>> {
        val doc=requireNotNull(HWPReader.fromFile(file))
        val characters=mutableListOf<Char>();val shapes=mutableListOf<CharShape>()
        doc.bodyText.sectionList.forEach {s->s.forEach {p->paragraphs(p).forEach {q->
            var offset=0L
            val runs=q.charShape?.positonShapeIdPairList.orEmpty()
            q.text?.charList?.forEach {ch->
                if(ch.type==HWPCharType.Normal) {
                    val id=requireNotNull(runs.lastOrNull {it.position<=offset}) {"Missing character run at $offset"}.shapeId.toInt()
                    characters.add(ch.code.toChar());shapes.add(doc.docInfo.charShapeList[id])
                }
                offset+=ch.charSize
            }
        }}}
        return characters.joinToString("") to shapes
    }
    private fun hwpSignature(s: CharShape)=listOf(s.baseSize,s.property.isBold,s.property.isItalic,s.charColor.value,s.ratios.hangul,s.charSpaces.hangul)
    private fun verifyHwp(dir: File,png: ByteArray) {
        val file=File(dir,"mobile-formatting.hwp")
        val (text,shapes)=hwpRuns(file);val first=text.indexOf("가나다라 ABC");require(first>=0) {text}
        for(i in first+1..first+2) {
            val s=shapes[i]
            require(s.baseSize==1800 && s.property.isBold)
            require(s.charColor.r==0xe5.toShort() && s.charColor.g==0x39.toShort() && s.charColor.b==0x35.toShort())
            require(s.ratios.hangul.toInt()==85 && s.charSpaces.hangul.toInt()==12)
        }
        require(!shapes[first].property.isBold)
        val (_,baseline)=hwpRuns(File(dir,"fixture.hwp"))
        require(hwpSignature(shapes[first+5])==hwpSignature(baseline.first())) {"Unselected Latin character formatting changed"}
        val doc=HWPReader.fromFile(file)
        val all=doc.bodyText.sectionList.flatMap {s->s.flatMap {paragraphs(it)}}
        val tables=all.flatMap {it.controlList.orEmpty().filterIsInstance<ControlTable>()}
        val table=requireNotNull(tables.find {t->t.rowList.any {r->r.cellList.any {c->c.paragraphList.any {text(it).contains("MOBILE_CELL_A")}}}})
        require(table.rowList.size==2 && table.rowList.sumOf {it.cellList.size}==4)
        val cellText=table.rowList.flatMap {it.cellList}.flatMap {it.paragraphList.toList()}.joinToString("") {text(it)}
        for(v in listOf("MOBILE_CELL_A","MOBILE_CELL_B","MOBILE_CELL_C","MOBILE_CELL_D"))require(Regex(Regex.escape(v)).findAll(cellText).count()==1) {"Table text lost/duplicated: $v"}
        var matchingImage=false
        POIFSFileSystem(file,true).use {fs->
            val bin=fs.root.getEntry("BinData") as DirectoryEntry
            for(entry in bin)if(entry is DocumentEntry) {
                val raw=DocumentInputStream(entry).use {it.readBytes()}
                val decoded=runCatching {InflaterInputStream(ByteArrayInputStream(raw),Inflater(true)).use {it.readBytes()}}.getOrNull()
                if(raw.contentEquals(png)||decoded?.contentEquals(png)==true)matchingImage=true
            }
        }
        require(matchingImage) {"Inserted PNG bytes were not retained in HWP"}
    }
    private fun verifyHwpx(dir: File,png: ByteArray) {
        val file=File(dir,"mobile-formatting.hwpx")
        require(HWPXReader.fromFile(file).sectionXMLFileList().count()>0)
        ZipFile(file).use {zip->
            val head=Xml.parse(zip.getInputStream(zip.getEntry("Contents/header.xml")).readBytes())
            val body=Xml.parse(zip.getInputStream(zip.getEntry("Contents/section0.xml")).readBytes())
            val properties=Xml.elements(head,"charPr").associateBy {it.getAttribute("id")}
            val chars=mutableListOf<Char>();val shapes=mutableListOf<Element>()
            for(run in Xml.elements(body,"run")) {
                val shape=properties[run.getAttribute("charPrIDRef")] ?: continue
                // Direct t children avoid counting text from a nested control twice.
                for(i in 0 until run.childNodes.length) {
                    val child=run.childNodes.item(i) as? Element ?: continue
                    if(child.localName=="t")for(c in child.textContent){chars.add(c);shapes.add(shape)}
                }
            }
            val text=chars.joinToString("");val first=text.indexOf("가나다라 ABC");require(first>=0) {text}
            for(i in first+1..first+2) {
                val s=shapes[i];require(s.getAttribute("height")=="1800")
                require(s.getAttribute("textColor").equals("#E53935",true));require(Xml.child(s,"bold")!=null)
                require(Xml.child(s,"ratio")?.getAttribute("hangul")=="85")
                require(Xml.child(s,"spacing")?.getAttribute("hangul")=="12")
            }
            require(Xml.child(shapes[first],"bold")==null)
            ZipFile(File(dir,"fixture.hwpx")).use {original->
                val h=Xml.parse(original.getInputStream(original.getEntry("Contents/header.xml")).readBytes())
                val b=Xml.parse(original.getInputStream(original.getEntry("Contents/section0.xml")).readBytes())
                val id=Xml.elements(b,"run").first {Xml.elements(it,"t").any {t->t.textContent.contains("PAGE_EDIT_ORIGINAL")}}.getAttribute("charPrIDRef")
                val base=Xml.elements(h,"charPr").first {it.getAttribute("id")==id}
                fun signature(s: Element)=listOf(s.getAttribute("height"),s.getAttribute("textColor"),Xml.child(s,"bold")!=null,Xml.child(s,"italic")!=null,Xml.child(s,"ratio")?.getAttribute("hangul"),Xml.child(s,"spacing")?.getAttribute("hangul"))
                require(signature(shapes[first+5])==signature(base)) {"Unselected HWPX formatting changed"}
                require(Xml.elements(body,"tbl").size==Xml.elements(b,"tbl").size+1) {"An existing table was lost"}
                for(label in Xml.elements(b,"t").map {it.textContent}.filter {it.startsWith("KEEP_CELL_")}) {
                    val saved=Xml.elements(body,"t").single {it.textContent==label}
                    val old=Xml.elements(b,"t").single {it.textContent==label}
                    val oldId=(old.parentNode as Element).getAttribute("charPrIDRef")
                    val savedId=(saved.parentNode as Element).getAttribute("charPrIDRef")
                    require(signature(properties.getValue(savedId))==signature(Xml.elements(h,"charPr").first {it.getAttribute("id")==oldId})) {"Untouched original cell format changed"}
                }
            }
            val table=Xml.elements(body,"tbl").first {Xml.elements(it,"t").any {t->t.textContent.contains("MOBILE_CELL_A")}}
            require(Xml.elements(table,"tr").size==2 && Xml.elements(table,"tc").size==4)
            val cellText=Xml.elements(table,"t").joinToString("") {it.textContent}
            for(v in listOf("MOBILE_CELL_A","MOBILE_CELL_B","MOBILE_CELL_C","MOBILE_CELL_D"))require(Regex(Regex.escape(v)).findAll(cellText).count()==1)
            require(zip.entries().asSequence().filter {!it.isDirectory && it.name.startsWith("BinData/")}.any {zip.getInputStream(it).use {input->input.readBytes().contentEquals(png)}}) {"Inserted PNG bytes were not retained in HWPX"}
        }
    }
}
