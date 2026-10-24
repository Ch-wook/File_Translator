package kr.documentstudio.core

import java.io.File
import java.util.zip.ZipFile
import kr.dogfoot.hwplib.reader.HWPReader
import kr.dogfoot.hwpxlib.reader.HWPXReader

/** Independent readers verify real files produced by the browser/WASM engine. */
object VisualFixtureTool {
    @JvmStatic fun main(args: Array<String>) {
        val directory=File(args[1]).apply {mkdirs()}
        if(args[0]=="prepare") {
            val hwp=File(directory,"fixture.hwp");LegacyDocument.create(Format.HWP,hwp)
            val original=LegacyDocument.open(hwp);val field=original.sections.first().fields.first()
            val changed=File(directory,"hwp-stage.hwp");original.write(changed,Edits(values=mapOf(field.id to "PAGE_EDIT_ORIGINAL")));changed.copyTo(hwp,true);changed.delete()
            val blank=File(directory,"hwpx-blank.hwpx");Templates.create(Format.HWPX,blank)
            val table=File(directory,"hwpx-table.hwpx")
            PackageFeatures.apply(blank,table,change=DocumentChange("hwpx.table","Contents/section0.xml",mapOf("rows" to "2","columns" to "2")))
            val doc=PackageDocument.open(table);val fields=doc.sections.first().fields
            doc.write(File(directory,"fixture.hwpx"),Edits(values=fields.mapIndexed {i,f->f.id to if(i==0) "PAGE_EDIT_ORIGINAL" else "KEEP_CELL_$i"}.toMap(),styles=mapOf(fields.first().id to mapOf("bold" to "true","size" to "16"))))
            blank.delete();table.delete()
            println("Prepared HWP and HWPX fixtures with editable body, formatting and a 2×2 table.")
        } else {
            for(format in listOf(Format.HWP,Format.HWPX)) {
                val file=File(directory,"edited.${format.extension}");require(file.isFile) {"Browser output is missing: $file"}
                val sections=if(format==Format.HWP) {require(HWPReader.fromFile(file)!=null);LegacyDocument.open(file).sections} else {require(HWPXReader.fromFile(file).sectionXMLFileList().count()>0);PackageDocument.open(file).sections}
                val text=sections.flatMap {it.fields}.joinToString("") {it.text}
                require(text.contains("PAGE_EDIT_CHANGED")) {"Edited text did not survive ${format.extension} serialization: $text"}
                require(!text.contains("PAGE_EDIT_ORIGINAL"))
                require(text.contains("한글 직접 편집")) {"Korean input did not survive serialization"}
                if(format==Format.HWPX) {
                    val fields=sections.first().fields
                    require(fields.any {it.text=="CELL_CHANGED"}) {"Direct table cell edit was not saved"}
                    for(i in 2..5) require(fields.any {it.text=="KEEP_CELL_$i"}) {"An untouched table cell was lost"}
                    ZipFile(file).use {zip->val xml=Xml.parse(zip.getInputStream(zip.getEntry("Contents/section0.xml")).readBytes());require(Xml.elements(xml,"tbl").size==1);require(Xml.elements(xml,"tc").size==4)}
                    require(fields.first {it.text.contains("PAGE_EDIT_CHANGED")}.styles["bold"]=="true") {"Original bold formatting was lost"}
                }
                println("${format.extension}: independent parser reopened edited file; text and untouched structure passed.")
            }
            val phone=File(directory,"edited-phone.hwpx")
            require(HWPXReader.fromFile(phone).sectionXMLFileList().count()>0)
            require(PackageDocument.open(phone).sections.flatMap {it.fields}.any {it.text.contains("한글 직접 편집")})
            println("hwpx phone: Korean input survived independent reopening.")
        }
    }
}
