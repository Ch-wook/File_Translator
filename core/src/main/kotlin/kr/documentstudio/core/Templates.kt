package kr.documentstudio.core

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object Templates {
    fun create(format: Format, file: File) {
        if(format==Format.HWPX) {
            Templates::class.java.getResourceAsStream("/templates/blank.hwpx")!!.use {input->file.outputStream().use {input.copyTo(it)}}
            PackageDocument.open(file);return
        }
        require(format in listOf(Format.DOCX, Format.XLSX, Format.PPTX))
        val parts = linkedMapOf<String, String>()
        val root = when(format) { Format.DOCX -> "word/document.xml"; Format.XLSX -> "xl/workbook.xml"; else -> "ppt/presentation.xml" }
        val mainType = when(format) {
            Format.DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
            Format.XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"
            else -> "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"
        }
        val overrides = mutableListOf(root to mainType)
        parts["_rels/.rels"] = """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="${PackageDocument.R}/officeDocument" Target="$root"/></Relationships>"""
        when(format) {
            Format.DOCX -> parts[root] = """<w:document xmlns:w="${PackageDocument.W}"><w:body><w:p><w:r><w:t>새 문서</w:t></w:r></w:p><w:p><w:r><w:t>여기에 내용을 입력하세요.</w:t></w:r></w:p><w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>"""
            Format.XLSX -> {
                parts[root] = """<workbook xmlns="${PackageDocument.S}" xmlns:r="${PackageDocument.R}"><sheets><sheet name="시트1" sheetId="1" r:id="rId1"/></sheets><calcPr fullCalcOnLoad="1"/></workbook>"""
                parts["xl/_rels/workbook.xml.rels"] = """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="${PackageDocument.R}/worksheet" Target="worksheets/sheet1.xml"/></Relationships>"""
                parts["xl/worksheets/sheet1.xml"] = """<worksheet xmlns="${PackageDocument.S}"><sheetData/></worksheet>"""
                parts["xl/styles.xml"] = """<styleSheet xmlns="${PackageDocument.S}"><fonts count="1"><font><sz val="11"/><name val="Arial"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>"""
                parts["xl/_rels/workbook.xml.rels"] = parts.getValue("xl/_rels/workbook.xml.rels").replace("</Relationships>","<Relationship Id=\"rIdStyles\" Type=\"${PackageDocument.R}/styles\" Target=\"styles.xml\"/></Relationships>")
                overrides.add("xl/styles.xml" to "application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml")
                overrides.add("xl/worksheets/sheet1.xml" to "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml")
            }
            Format.PPTX -> {
                val p = "http://schemas.openxmlformats.org/presentationml/2006/main"
                val a = PackageDocument.A
                val r = PackageDocument.R
                val rel = "http://schemas.openxmlformats.org/package/2006/relationships"
                val tree = """<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>"""
                val colors = """<a:clrMap accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" bg1="lt1" bg2="lt2" folHlink="folHlink" hlink="hlink" tx1="dk1" tx2="dk2"/>"""
                parts[root] = """<p:presentation xmlns:p="$p" xmlns:a="$a" xmlns:r="$r"><p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId2"/></p:sldMasterIdLst><p:sldIdLst><p:sldId id="256" r:id="rId1"/></p:sldIdLst><p:sldSz cx="12192000" cy="6858000" type="screen16x9"/><p:notesSz cx="6858000" cy="9144000"/></p:presentation>"""
                parts["ppt/_rels/presentation.xml.rels"] = """<Relationships xmlns="$rel"><Relationship Id="rId1" Type="$r/slide" Target="slides/slide1.xml"/><Relationship Id="rId2" Type="$r/slideMaster" Target="slideMasters/slideMaster1.xml"/></Relationships>"""
                parts["ppt/slides/slide1.xml"] = """<p:sld xmlns:p="$p" xmlns:a="$a" xmlns:r="$r"><p:cSld><p:spTree>$tree<p:sp><p:nvSpPr><p:cNvPr id="2" name="제목"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="914400" y="914400"/><a:ext cx="10363200" cy="1828800"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/></p:spPr><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr lang="ko-KR" sz="3200"/><a:t>새 프레젠테이션</a:t></a:r><a:endParaRPr lang="ko-KR"/></a:p></p:txBody></p:sp></p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"""
                parts["ppt/slides/_rels/slide1.xml.rels"] = """<Relationships xmlns="$rel"><Relationship Id="rId1" Type="$r/slideLayout" Target="../slideLayouts/slideLayout1.xml"/></Relationships>"""
                parts["ppt/slideLayouts/slideLayout1.xml"] = """<p:sldLayout xmlns:p="$p" xmlns:a="$a" xmlns:r="$r" type="blank" preserve="1"><p:cSld name="빈 슬라이드"><p:spTree>$tree</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"""
                parts["ppt/slideLayouts/_rels/slideLayout1.xml.rels"] = """<Relationships xmlns="$rel"><Relationship Id="rId1" Type="$r/slideMaster" Target="../slideMasters/slideMaster1.xml"/></Relationships>"""
                parts["ppt/slideMasters/slideMaster1.xml"] = """<p:sldMaster xmlns:p="$p" xmlns:a="$a" xmlns:r="$r"><p:cSld><p:spTree>$tree</p:spTree></p:cSld>$colors<p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst><p:txStyles><p:titleStyle/><p:bodyStyle/><p:otherStyle/></p:txStyles></p:sldMaster>"""
                parts["ppt/slideMasters/_rels/slideMaster1.xml.rels"] = """<Relationships xmlns="$rel"><Relationship Id="rId1" Type="$r/slideLayout" Target="../slideLayouts/slideLayout1.xml"/><Relationship Id="rId2" Type="$r/theme" Target="../theme/theme1.xml"/></Relationships>"""
                val schemes = listOf("dk1" to "000000", "lt1" to "FFFFFF", "dk2" to "1F2937", "lt2" to "F7F4EB", "accent1" to "315D54", "accent2" to "C08050", "accent3" to "587AA6", "accent4" to "9469AB", "accent5" to "4A9B97", "accent6" to "9E8544", "hlink" to "0563C1", "folHlink" to "954F72").joinToString("") { (n,v) -> "<a:$n><a:srgbClr val=\"$v\"/></a:$n>" }
                parts["ppt/theme/theme1.xml"] = """<a:theme xmlns:a="$a" name="문서 스튜디오"><a:themeElements><a:clrScheme name="기본">$schemes</a:clrScheme><a:fontScheme name="기본"><a:majorFont><a:latin typeface="Arial"/><a:ea typeface=""/><a:cs typeface=""/></a:majorFont><a:minorFont><a:latin typeface="Arial"/><a:ea typeface=""/><a:cs typeface=""/></a:minorFont></a:fontScheme><a:fmtScheme name="기본"><a:fillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:fillStyleLst><a:lnStyleLst>${(1..3).joinToString("") { "<a:ln w=\"9525\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:prstDash val=\"solid\"/></a:ln>" }}</a:lnStyleLst><a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst><a:bgFillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:bgFillStyleLst></a:fmtScheme></a:themeElements></a:theme>"""
                listOf("slides/slide1.xml" to "slide", "slideLayouts/slideLayout1.xml" to "slideLayout", "slideMasters/slideMaster1.xml" to "slideMaster", "theme/theme1.xml" to "theme").forEach { (path, type) ->
                    overrides.add("ppt/$path" to if (type == "theme") "application/vnd.openxmlformats-officedocument.theme+xml" else "application/vnd.openxmlformats-officedocument.presentationml.$type+xml")
                }
            }
            else -> Unit
        }
        parts["[Content_Types].xml"] = """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>${overrides.joinToString("") { (part, type) -> "<Override PartName=\"/$part\" ContentType=\"$type\"/>" }}</Types>"""
        ZipOutputStream(file.outputStream()).use { zip -> parts.forEach { (path, xml) -> zip.putNextEntry(ZipEntry(path)); zip.write(xml.toByteArray()); zip.closeEntry() } }
        PackageDocument.open(file)
    }
}
