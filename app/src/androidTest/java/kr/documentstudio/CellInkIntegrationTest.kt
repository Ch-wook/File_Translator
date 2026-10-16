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

class CellInkIntegrationTest {
    private val app=ApplicationProvider.getApplicationContext<Application>()
    private fun main(block: ()->Unit)=InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private suspend fun ready(vm: EditorViewModel)=withTimeout(15000) {vm.state.first {!it.busy && it.session!=null}.session!!}
    private fun file(ext: String)=File(app.cacheDir,"tests/${UUID.randomUUID()}.$ext").apply {parentFile!!.mkdirs();writeBytes(byteArrayOf())}
    private fun uri(file: File)=FileProvider.getUriForFile(app,"kr.documentstudio.testfiles",file)
    @Test fun cellInkFollowsRowsColumnsSizeDuplicateUndoAndBackup()=runBlocking {
        lateinit var vm: EditorViewModel;main {vm=EditorViewModel(app);vm.create(Format.XLSX)};var s=ready(vm);val section=s.sections.single().id
        val grid=SheetInkMeasurements.read(s,s.sections.single(),1,1);val frame=grid.frame("B3")!!
        val stroke=Stroke(anchor=section,tool=InkTool.PEN,color=-16777216,width=.003f,points=listOf(InkPoint(frame.x+.01f,frame.y+.01f),InkPoint(frame.x+.08f,frame.y+.03f)),cell=CellInkBinding("B3",frame))
        main {vm.addStroke(stroke);vm.field("$section#B3","CELL_INK");vm.change(DocumentChange("sheet.insertRows",section,mapOf("index" to "2","count" to "2")))}
        s=ready(vm);assertEquals("B5",s.edits.strokes.single().cell!!.address);assertEquals("CELL_INK",s.sections.single().cells["B5"])
        main {vm.undo()};assertEquals("B3",vm.state.value.session!!.edits.strokes.single().cell!!.address)
        main {vm.redo();vm.change(DocumentChange("sheet.width",section,mapOf("column" to "2","width" to "25")))};s=ready(vm)
        val resized=SheetInkMeasurements.read(s,s.sections.single(),1,1);assertTrue(resized.frame("B5")!!.width>grid.frame("B3")!!.width)
        val raw=s.edits.strokes.single();val projection=InkProjection(section,resized)
        main {vm.transformInk(setOf(raw.id),duplicate=true,projection=projection)};assertEquals(2,vm.state.value.session!!.edits.strokes.size);assertEquals(raw,vm.state.value.session!!.edits.strokes.first())
        main {vm.change(DocumentChange("sheet.duplicate",section,mapOf("name" to "복사 필기")))};s=ready(vm)
        val copied=s.sections.last();assertEquals(2,s.edits.strokes.count {it.anchor==copied.id});assertTrue(s.edits.strokes.all {it.cell!!.address=="B5"})
        val backup=file("zip");vm.repository.exportBackup(s,uri(backup));val restored=vm.repository.open(uri(backup));assertEquals(s.edits,restored.edits)
        val pdf=file("pdf");vm.repository.exportPdf(restored,uri(pdf));assertEquals(4,PdfEngine.inspect(pdf).pages)
        val output=file("xlsx");vm.repository.save(s,uri(output));assertEquals("CELL_INK",PackageDocument.open(output).sections.first().cells["B5"])
    }
}
