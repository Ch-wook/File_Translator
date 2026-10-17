package kr.documentstudio.core

import kr.dogfoot.hwplib.`object`.bodytext.control.*
import kr.dogfoot.hwplib.`object`.bodytext.paragraph.Paragraph
import kr.dogfoot.hwplib.reader.HWPReader
import kr.dogfoot.hwplib.tool.blankfilemaker.BlankFileMaker
import kr.dogfoot.hwplib.writer.HWPWriter
import kr.dogfoot.hwpxlib.reader.HWPXReader
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

class HangulConversionTest {
    private fun addition(type: ControlType) = ByteArray(12).apply { repeat(4) { this[it]=(type.ctrlId ushr (8*it)).toByte() } }
    private fun temp(ext: String) = File.createTempFile("convert-test-", ".$ext").apply { deleteOnExit() }
    private fun textSource(text: String): File {
        val hwp=BlankFileMaker.make();val para=hwp.bodyText.sectionList[0].getParagraph(0)
        // hwplib.addString uses codePointAt per UTF-16 index; construct valid raw code units for this fixture.
        text.forEach {para.text.addNewNormalChar().code=it.code}
        para.text.addNewCharControlChar().code=13
        val out=temp("hwp");HWPWriter.toFile(hwp,out.toString())
        return out
    }
    @Test fun koreanAndSurrogatePairsAreConvertedAndReadByIndependentParser() {
        val src=textSource("한글 문서 😀 원본");val out=temp("hwpx")
        val before=src.readBytes();val report=HangulConversion.convert(src,out)
        assertEquals(1,report.sections);assertEquals(1,report.paragraphs)
        assertEquals("한글 문서 😀 원본",PackageDocument.open(out).sections[0].fields[0].text)
        assertEquals(1,HWPXReader.fromFile(out).sectionXMLFileList().count())
        assertArrayEquals(before,src.readBytes())
        assertEquals(report,HangulConversion.verify(src,out))
        ZipFile(out).use {
            assertEquals("mimetype",it.entries().nextElement().name);assertEquals(ZipEntry.STORED,it.getEntry("mimetype").method)
            assertTrue(it.getInputStream(it.getEntry("version.xml")).reader().readText().contains("Document Studio"))
        }
    }
    @Test fun hyphenLineBreakTabAndFixedSpacesRetainPrecedingText() {
        val hwp=BlankFileMaker.make();val p=hwp.bodyText.sectionList[0].getParagraph(0)
        p.text.addString("앞");p.text.addNewCharControlChar().code=24
        p.text.addString("뒤");p.text.addNewCharControlChar().code=10
        p.text.addNewInlineControlChar().apply {code=9;addition=ByteArray(12)}
        p.text.addNewCharControlChar().code=30;p.text.addNewCharControlChar().code=31
        val src=temp("hwp");HWPWriter.toFile(hwp,src.toString());val out=temp("hwpx")
        HangulConversion.convert(src,out)
        ZipFile(out).use {val xml=it.getInputStream(it.getEntry("Contents/section0.xml")).reader().readText();assertTrue(xml.contains("앞"));assertTrue(xml.contains("hyphen"));assertTrue(xml.contains("뒤"));assertTrue(xml.contains("nbSpace"));assertTrue(xml.contains("fwSpace"))}
    }
    @Test fun paragraphBeyondUnsignedShortBoundaryKeepsLastRun() {
        val src=textSource("x".repeat(66000)+"긴 문단 끝");val out=temp("hwpx")
        HangulConversion.convert(src,out)
        assertEquals("x".repeat(66000)+"긴 문단 끝",PackageDocument.open(out).sections[0].fields[0].text)
    }
    @Test fun tableCellsHeadersFootersAndNotesKeepTheirOwnText() {
        val hwp=BlankFileMaker.make();val p=hwp.bodyText.sectionList[0].getParagraph(0)
        p.text.addString("본문")
        fun fill(para: Paragraph, text: String) {
            para.createText();para.text.addString(text);para.createCharShape();para.charShape.addParaCharShape(0,0)
        }
        p.text.addExtendCharForTable();val table=p.addNewControl(ControlType.Table) as ControlTable
        table.table.rowCount=2;table.table.columnCount=2;table.table.borderFillId=1
        repeat(2) { r -> val row=table.addNewRow();table.table.cellCountOfRowList.add(2)
            repeat(2) { c -> val cell=row.addNewCell();cell.listHeader.apply {
                paraCount=1;rowIndex=r;colIndex=c;rowSpan=1;colSpan=1;width=10000;height=3000;borderFillId=1
            };fill(cell.paragraphList.addNewParagraph(),"셀 $r,$c") }
        }
        p.text.addExtendCharForHeader();val header=p.addNewControl(ControlType.Header) as ControlHeader
        header.listHeader.paraCount=1;fill(header.paragraphList.addNewParagraph(),"머리말")
        header.header.applyPage=kr.dogfoot.hwplib.`object`.bodytext.control.ctrlheader.header.HeaderFooterApplyPage.BothPage
        p.text.addExtendCharForFooter();val footer=p.addNewControl(ControlType.Footer) as ControlFooter
        footer.listHeader.paraCount=1;fill(footer.paragraphList.addNewParagraph(),"꼬리말")
        footer.header.applyPage=kr.dogfoot.hwplib.`object`.bodytext.control.ctrlheader.header.HeaderFooterApplyPage.BothPage
        fun controlChar(type: ControlType) { p.text.addNewExtendControlChar().apply {code=17;addition=addition(type)} }
        controlChar(ControlType.Footnote);val foot=p.addNewControl(ControlType.Footnote) as ControlFootnote
        foot.listHeader.paraCount=1;fill(foot.paragraphList.addNewParagraph(),"각주 내용")
        foot.header.numberShape=kr.dogfoot.hwplib.`object`.bodytext.control.sectiondefine.NumberShape.Number
        controlChar(ControlType.Endnote);val end=p.addNewControl(ControlType.Endnote) as ControlEndnote
        end.listHeader.paraCount=1;fill(end.paragraphList.addNewParagraph(),"미주 내용")
        end.header.numberShape=kr.dogfoot.hwplib.`object`.bodytext.control.sectiondefine.NumberShape.Number
        val src=temp("hwp");HWPWriter.toFile(hwp,src.toString());val out=temp("hwpx")
        val report=HangulConversion.convert(src,out)
        assertEquals(9,report.paragraphs);assertEquals(1,report.tables);assertEquals(4,report.cells)
        assertEquals(1,report.headers);assertEquals(1,report.footers);assertEquals(1,report.footnotes);assertEquals(1,report.endnotes)
        assertEquals(1,HWPXReader.fromFile(out).sectionXMLFileList().count())
        // Swapping cell text preserves aggregate text but must fail the scoped comparison.
        val tampered=temp("hwpx")
        ZipFile(out).use {zip -> ZipOutputStream(tampered.outputStream()).use {target ->
            zip.entries().toList().forEach {entry -> target.putNextEntry(ZipEntry(entry.name));val data=zip.getInputStream(entry).use {it.readBytes()}
                target.write(if(entry.name=="Contents/section0.xml") data.toString(Charsets.UTF_8).replace("셀 0,0","TEMP").replace("셀 0,1","셀 0,0").replace("TEMP","셀 0,1").toByteArray() else data);target.closeEntry()
            }
        } }
        assertTrue(runCatching {HangulConversion.verify(src,tampered)}.isFailure)
    }
    @Test fun unsupportedControlDoesNotOverwriteDestinationOrOriginal() {
        val hwp=BlankFileMaker.make();val p=hwp.bodyText.sectionList[0].getParagraph(0)
        p.text.addNewExtendControlChar().apply {code=21;addition=addition(ControlType.IndexMark)}
        p.addNewControl(ControlType.IndexMark)
        val src=temp("hwp");HWPWriter.toFile(hwp,src.toString());val before=src.readBytes()
        val out=temp("hwpx").apply {writeText("existing destination")}
        val error=runCatching {HangulConversion.convert(src,out)}.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("IndexMark"));assertEquals("existing destination",out.readText());assertArrayEquals(before,src.readBytes())
        assertFalse(src.parentFile!!.listFiles().orEmpty().any {it.name.startsWith("hangul-conversion-")})
    }
    @Test fun malformedOrWrongInputAndSameDestinationAreRejected() {
        val src=textSource("원본");val before=src.readBytes()
        assertTrue(runCatching {HangulConversion.convert(src,src)}.isFailure);assertArrayEquals(before,src.readBytes())
        val wrong=temp("hwp").apply {writeText("not an OLE file")};val out=temp("hwpx").apply {writeText("keep")}
        assertTrue(runCatching {HangulConversion.convert(wrong,out)}.isFailure);assertEquals("keep",out.readText())
    }
    @Test fun pictureResourceAndEquationScriptAreRetained() {
        assertEquals("image/png",kr.dogfoot.hwp2hwpx.util.HWPUtil.mediaType("PNG"))
        assertEquals("image/jpeg",kr.dogfoot.hwp2hwpx.util.HWPUtil.mediaType("JpG"))
        val hwp=BlankFileMaker.make();val p=hwp.bodyText.sectionList[0].getParagraph(0)
        val png=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aWQAAAABJRU5ErkJggg==")
        val mode=kr.dogfoot.hwplib.`object`.docinfo.bindata.BinDataCompress.ByStorageDefault
        hwp.binData.addNewEmbeddedBinaryData("Bin0001.PNG",png,mode)
        hwp.docInfo.addNewBinData().apply {
            property.type=kr.dogfoot.hwplib.`object`.docinfo.bindata.BinDataType.Embedding;property.compress=mode;binDataID=1;extensionForEmbedding="PNG"
        }
        p.text.addExtendCharForGSO()
        val pic=p.addNewGsoControl(kr.dogfoot.hwplib.`object`.bodytext.control.gso.GsoControlType.Picture) as kr.dogfoot.hwplib.`object`.bodytext.control.gso.ControlPicture
        pic.shapeComponentPicture.apply {pictureInfo.binItemID=1;innerMargin.right=123;innerMargin.top=456}
        p.text.addNewExtendControlChar().apply {code=11;addition=addition(ControlType.Equation)}
        val equation=p.addNewControl(ControlType.Equation) as ControlEquation
        equation.eqEdit.apply {script.fromUTF16LEString("a^2+b^2=c^2");versionInfo.fromUTF16LEString("Equation Version 60");fontName.fromUTF16LEString("HYhwpEQ")}
        val src=temp("hwp");HWPWriter.toFile(hwp,src.toString());val out=temp("hwpx")
        val report=HangulConversion.convert(src,out)
        assertEquals(1,report.pictures);assertEquals(1,report.binaries);assertEquals(1,report.equations);assertEquals(1,report.paragraphs)
        ZipFile(out).use {zip ->
            assertArrayEquals(png,zip.getInputStream(zip.getEntry("BinData/image1.PNG")).use {it.readBytes()})
            assertTrue(zip.getInputStream(zip.getEntry("Contents/content.hpf")).reader().readText().contains("media-type=\"image/png\""))
            val xml=Xml.parse(zip.getInputStream(zip.getEntry("Contents/section0.xml")).use {it.readBytes()})
            val margin=Xml.child(Xml.elements(xml,"pic").single(),"inMargin")!!
            assertEquals("123",margin.getAttribute("right"));assertEquals("456",margin.getAttribute("top"))
            assertEquals("a^2+b^2=c^2",Xml.elements(xml,"script").single().textContent)
        }
        assertNotNull(HWPXReader.fromFile(out))
    }
    @Test fun compressedResourceBombIsRejectedBeforeLibraryInflation() {
        val hwp=BlankFileMaker.make()
        hwp.binData.addNewEmbeddedBinaryData("Bin0001.png",ByteArray(49*1024*1024),kr.dogfoot.hwplib.`object`.docinfo.bindata.BinDataCompress.Compress)
        hwp.docInfo.addNewBinData().apply {property.type=kr.dogfoot.hwplib.`object`.docinfo.bindata.BinDataType.Embedding;property.compress=kr.dogfoot.hwplib.`object`.docinfo.bindata.BinDataCompress.Compress;binDataID=1;extensionForEmbedding="png"}
        val src=temp("hwp");HWPWriter.toFile(hwp,src.toString());val out=temp("hwpx").apply {writeText("keep")}
        val error=runCatching {HangulConversion.convert(src,out)}.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("48 MiB"));assertEquals("keep",out.readText())
    }
    @Test fun optionalSummaryDatesAndStringsMayBeAbsent() {
        val original=textSource("메타데이터");val src=temp("hwp")
        val summary=org.apache.poi.hpsf.PropertySetFactory.newSummaryInformation().apply {title="제목만 있음"}
        summary.firstSection.formatID=org.apache.poi.hpsf.ClassID("{9FA2B660-1061-11D4-B4C6-006097C09D8C}")
        val bytes=java.io.ByteArrayOutputStream().also {summary.write(it)}.toByteArray()
        org.apache.poi.poifs.filesystem.POIFSFileSystem(original.inputStream()).use {fs ->
            fs.root.createDocument("\u0005HwpSummaryInformation",bytes.inputStream());src.outputStream().use {fs.writeFilesystem(it)}
        }
        val out=temp("hwpx");HangulConversion.convert(src,out)
        ZipFile(out).use {zip -> val xml=zip.getInputStream(zip.getEntry("Contents/content.hpf")).reader().readText()
            assertTrue(xml.contains("제목만 있음"));assertFalse(xml.contains("CreatedDate"));assertFalse(xml.contains(">null<"))
        }
    }
}
