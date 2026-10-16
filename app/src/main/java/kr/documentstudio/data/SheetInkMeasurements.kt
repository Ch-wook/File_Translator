package kr.documentstudio.data

import kr.documentstudio.core.*
import java.util.zip.ZipFile

object SheetInkMeasurements {
    fun read(s: Session,section: Section,firstRow: Int,firstColumn: Int): SheetInkGrid {
        val columnSizes=(firstColumn..minOf(firstColumn+5,if(s.format==Format.XLS) 256 else 16384)).associateWith {64f}.toMutableMap()
        val rowSizes=(firstRow..minOf(firstRow+11,if(s.format==Format.XLS) 65536 else 1048576)).associateWith {20f}.toMutableMap()
        if(s.format==Format.XLSX) ZipFile(s.base).use {zip->
            val doc=Xml.parse(zip.getInputStream(zip.getEntry(section.id)).use {it.readBytes()})
            val defaults=Xml.elements(doc,"sheetFormatPr").firstOrNull()
            val defaultHeight=defaults?.getAttribute("defaultRowHeight")?.toFloatOrNull() ?: 15f
            val defaultWidth=defaults?.getAttribute("defaultColWidth")?.toFloatOrNull() ?: 8.43f
            rowSizes.replaceAll {_,_->defaultHeight*1.3333f};columnSizes.replaceAll {_,_->defaultWidth*7+5}
            Xml.elements(doc,"col").forEach {col->
                val min=col.getAttribute("min").toIntOrNull() ?: return@forEach;val max=col.getAttribute("max").toIntOrNull() ?: return@forEach
                columnSizes.keys.toList().filter {it in min..max}.forEach {c->if(col.getAttribute("hidden")=="1") columnSizes.remove(c) else col.getAttribute("width").toFloatOrNull()?.let {columnSizes[c]=it*7+5}}
            }
            Xml.elements(doc,"row").forEach {row->val r=row.getAttribute("r").toIntOrNull() ?: return@forEach
                if(r in rowSizes) {if(row.getAttribute("hidden")=="1") rowSizes.remove(r) else row.getAttribute("ht").toFloatOrNull()?.let {rowSizes[r]=it*1.3333f}}}
        }
        require(columnSizes.isNotEmpty() && rowSizes.isNotEmpty()) {"표시 범위의 모든 행 또는 열이 숨겨져 있습니다. 다른 시작 셀을 선택하세요."}
        return SheetInkGrid(columnSizes.map {InkMeasure(it.key,it.value.coerceIn(1f,2000f))},rowSizes.map {InkMeasure(it.key,it.value.coerceIn(1f,2000f))})
    }
}
