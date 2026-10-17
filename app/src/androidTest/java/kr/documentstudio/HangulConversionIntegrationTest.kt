package kr.documentstudio

import android.app.Application
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kr.documentstudio.core.*
import kr.documentstudio.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

class HangulConversionIntegrationTest {
    private val app=ApplicationProvider.getApplicationContext<Application>()
    private fun file(ext: String)=File(app.cacheDir,"tests/${UUID.randomUUID()}.$ext").apply {parentFile!!.mkdirs();writeBytes(byteArrayOf())}
    private fun uri(f: File)=FileProvider.getUriForFile(app,"kr.documentstudio.testfiles",f)
    private fun main(block: ()->Unit)=InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private suspend fun ready(vm: EditorViewModel)=withTimeout(30000) {vm.state.first {!it.busy}}
    @Test fun conversionKeepsOriginalWorkingTextInkAndBothBackupFormats()=runBlocking {
        val repo=DocumentRepository(app);val original=repo.create(Format.HWP)
        val key=original.sections[0].fields[0].id;val before=original.base.readBytes()
        val layer=InkLayer("review","검토",false,true)
        val source=original.copy(edits=original.edits.copy(values=mapOf(key to "편집 중 한글"),paper=Paper.GRID,
            layers=listOf(InkLayer(),layer),strokes=listOf(Stroke(anchor=original.sections[0].id,tool=InkTool.PEN,color=-1,width=.004f,layerId="review",points=listOf(InkPoint(.2f,.3f,.6f,.1f))))))
        val target=repo.convertHangul(source)
        assertNotEquals(source.id,target.id);assertNull(target.uri);assertEquals(Format.HWPX,target.format)
        assertArrayEquals(before,source.base.readBytes());assertEquals(source.edits,repo.restore(source.id).edits)
        assertEquals("편집 중 한글",target.sections[0].fields[0].text);assertTrue(target.edits.values.isEmpty())
        assertEquals(source.edits.strokes[0].copy(anchor=target.sections[0].id),target.edits.strokes[0])
        assertEquals(source.edits.layers,target.edits.layers);assertEquals(Paper.GRID,target.edits.paper)
        val report=repo.conversionReport(target)!!;assertEquals(1,report.sections)
        val saved=file("hwpx");repo.save(target,uri(saved))
        val reopened=repo.open(uri(saved));assertEquals("편집 중 한글",reopened.sections[0].fields[0].text)
        assertEquals(report,repo.conversionReport(repo.restore(target.id)))
        for(ext in listOf("zip","dsbackup")) {
            val archive=file(ext);val password=if(ext=="dsbackup") "변환 백업 pass!".toCharArray() else null
            repo.exportBackup(target,uri(archive),password)
            val restored=repo.open(uri(archive),password=password)
            assertEquals(target.edits,restored.edits);assertEquals(report,repo.conversionReport(restored))
            val again=file("zip");repo.exportBackup(restored,uri(again))
            ZipFile(again).use {zip ->
                assertArrayEquals(before,zip.getInputStream(zip.getEntry("conversion/original.hwp")).use {it.readBytes()})
                assertNotNull(zip.getEntry("conversion/working.hwp"));assertNotNull(zip.getEntry("conversion/report.json"))
            }
        }
    }
    @Test fun inactiveTargetConvertsToNewTabAndClosedCallbacksDoNothing()=runBlocking {
        lateinit var vm: EditorViewModel;main {vm=EditorViewModel(app);vm.create(Format.HWP)};ready(vm)
        val source=vm.state.value.session!!;val actions=vm.documentActions(source.id)
        main {actions.field(source.sections[0].fields[0].id,"원본 보존");vm.create(Format.TXT)};ready(vm)
        val other=vm.state.value.active!!;main {vm.text("다른 작업");actions.convertHangul()};ready(vm)
        assertEquals(3,vm.state.value.tabs.size);val converted=vm.state.value.session!!
        assertEquals(Format.HWPX,converted.format);assertEquals("원본 보존",converted.sections[0].fields[0].text)
        assertEquals("다른 작업",vm.state.value.tabs.first {it.id==other}.edits.text)
        main {vm.close(source.id)};ready(vm);val active=vm.state.value.active
        main {actions.convertHangul()};ready(vm)
        assertEquals(2,vm.state.value.tabs.size);assertEquals(active,vm.state.value.active)
    }
    @Test fun unsupportedDocumentLeavesIndexAndSourceUntouched()=runBlocking {
        val repo=DocumentRepository(app);val original=repo.create(Format.HWP)
        val hwp=kr.dogfoot.hwplib.reader.HWPReader.fromFile(original.base)
        val p=hwp.bodyText.sectionList[0].getParagraph(0)
        val type=kr.dogfoot.hwplib.`object`.bodytext.control.ControlType.IndexMark
        p.text.addNewExtendControlChar().apply {code=21;addition=ByteArray(12).apply {repeat(4) {this[it]=(type.ctrlId ushr (8*it)).toByte()}}}
        p.addNewControl(type)
        val incoming=file("hwp");kr.dogfoot.hwplib.writer.HWPWriter.toFile(hwp,incoming.toString())
        val source=repo.open(uri(incoming));val before=incoming.readBytes();val ids=repo.recents().map {it.id}.toSet()
        try {repo.convertHangul(source);fail("Unsupported control accepted")} catch(e: IllegalStateException) {assertTrue(e.message!!.contains("IndexMark"))}
        assertEquals(ids,repo.recents().map {it.id}.toSet());assertArrayEquals(before,incoming.readBytes());assertArrayEquals(before,source.base.readBytes())
    }
    @Test fun nestedTableHeaderAndNotesConvertOnAndroidAndDetectModifiedProvenance()=runBlocking {
        val hwp=kr.dogfoot.hwplib.tool.blankfilemaker.BlankFileMaker.make();val p=hwp.bodyText.sectionList[0].getParagraph(0)
        val tableType=kr.dogfoot.hwplib.`object`.bodytext.control.ControlType.Table
        p.text.addExtendCharForTable()
        val table=p.addNewControl(tableType) as kr.dogfoot.hwplib.`object`.bodytext.control.ControlTable
        table.table.rowCount=1;table.table.columnCount=2;table.table.borderFillId=1;table.table.cellCountOfRowList.add(2)
        val row=table.addNewRow()
        repeat(2) {c ->val cell=row.addNewCell();cell.listHeader.apply {paraCount=1;rowIndex=0;colIndex=c;rowSpan=1;colSpan=1;width=10000;height=3000;borderFillId=1}
            cell.paragraphList.addNewParagraph().apply {createText();text.addString("Android 셀 $c");createCharShape();charShape.addParaCharShape(0,0)}
        }
        p.text.addExtendCharForHeader()
        val header=p.addNewControl(kr.dogfoot.hwplib.`object`.bodytext.control.ControlType.Header) as kr.dogfoot.hwplib.`object`.bodytext.control.ControlHeader
        header.header.applyPage=kr.dogfoot.hwplib.`object`.bodytext.control.ctrlheader.header.HeaderFooterApplyPage.BothPage;header.listHeader.paraCount=1
        header.paragraphList.addNewParagraph().apply {createText();text.addString("Android 머리말");createCharShape();charShape.addParaCharShape(0,0)}
        val noteType=kr.dogfoot.hwplib.`object`.bodytext.control.ControlType.Footnote
        p.text.addNewExtendControlChar().apply {code=17;addition=ByteArray(12).apply {repeat(4) {this[it]=(noteType.ctrlId ushr (it*8)).toByte()}}}
        val note=p.addNewControl(noteType) as kr.dogfoot.hwplib.`object`.bodytext.control.ControlFootnote
        note.header.numberShape=kr.dogfoot.hwplib.`object`.bodytext.control.sectiondefine.NumberShape.Number;note.listHeader.paraCount=1
        note.paragraphList.addNewParagraph().apply {createText();text.addString("Android 각주");createCharShape();charShape.addParaCharShape(0,0)}
        val incoming=file("hwp");kr.dogfoot.hwplib.writer.HWPWriter.toFile(hwp,incoming.toString())
        val repo=DocumentRepository(app);val source=repo.open(uri(incoming));val converted=repo.convertHangul(source)
        val report=repo.conversionReport(converted)!!
        assertEquals(1,report.tables);assertEquals(2,report.cells);assertEquals(1,report.headers);assertEquals(1,report.footnotes);assertEquals(5,report.paragraphs)
        assertTrue(converted.sections[0].fields.any {it.text=="Android 셀 1"});assertTrue(converted.sections[0].fields.any {it.text=="Android 각주"})
        File(converted.base.parentFile,"preserved-backup/conversion/working.hwp").appendText("modified")
        assertTrue(runCatching {repo.conversionReport(converted)}.isFailure)
        assertNotNull(repo.restore(converted.id).pack)
    }
}
