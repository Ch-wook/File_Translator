package kr.documentstudio

import android.app.Application
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kr.documentstudio.core.*
import kr.documentstudio.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class InkAdvancedPersistenceTest {
    private val app=ApplicationProvider.getApplicationContext<Application>()
    private fun file(ext: String)=File(app.cacheDir,"tests/${UUID.randomUUID()}.$ext").apply {parentFile!!.mkdirs();writeBytes(byteArrayOf())}
    private fun uri(f: File)=FileProvider.getUriForFile(app,"kr.documentstudio.testfiles",f)
    private fun main(block: ()->Unit)=InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun edits(anchor: String)=Edits(strokes=listOf(Stroke(anchor=anchor,tool=InkTool.PEN,color=-16777216,width=.007f,points=listOf(InkPoint(.2f,.3f,.4f,.5f),InkPoint(.3f,.4f,1.2f,.9f)),pressureCurve=1.7f,smoothing=.8f,layerId="review")),layers=listOf(InkLayer(),InkLayer("review","검토",false,true)),paper=Paper.GRID)
    @Test fun pdfHiddenLayerStandardFlagsAndRawStyleSurviveFreshUriAndBackup()=runBlocking {
        val repo=DocumentRepository(app);val source=repo.create(Format.PDF);val e=edits("pdf:0").copy(paper=Paper.BLANK)
        val saved=file("pdf");repo.save(source.copy(edits=e),uri(saved))
        PDDocument.load(saved).use {doc->val a=doc.getPage(0).annotations.single();assertTrue(a.isHidden);assertFalse(a.isPrinted);assertTrue(a.cosObject.containsKey("AP"))}
        val fresh=file("pdf");saved.copyTo(fresh,true);val reopened=repo.open(uri(fresh))
        assertEquals(e.strokes,reopened.edits.strokes);assertEquals(e.layers,reopened.edits.layers)
        val backup=file("zip");repo.exportBackup(reopened,uri(backup));val restored=repo.open(uri(backup));assertEquals(e,restored.edits)
        val old=StateCodec.encode(e).apply {remove("layers");remove("paper");getJSONArray("strokes").getJSONObject(0).apply {remove("layer");remove("curve");remove("smoothing")}}
        assertEquals("default",StateCodec.decode(old).strokes.single().layerId)
    }
    @Test fun settingsPresetsAndLayerLocksPersistAndUndoIndependently()=runBlocking {
        val prefs=app.getSharedPreferences("ink-settings-test",0);prefs.edit().clear().commit()
        val settings=Settings(pressureCurve=2f,smoothing=.6f,autoSnap=true,toolbarTop=true,toolbarCollapsed=true,favoriteColors=listOf(-1,-2))
        val preset=PenPreset.from("회의",settings);val expected=settings.copy(presets=listOf(preset))
        InkPreferences.save(prefs.edit(),expected).commit();assertEquals(expected,InkPreferences.extend(Settings(),prefs))
        lateinit var vm: EditorViewModel;main {vm=EditorViewModel(app);vm.create(Format.TXT)}
        withTimeout(15000) {vm.state.first {!it.busy && it.session!=null}}
        main {vm.inkLayers(edits("text").layers);vm.addStroke(edits("text").strokes.single())}
        assertTrue(vm.state.value.session!!.edits.strokes.isEmpty())
        main {vm.inkLayers(edits("text").layers.map {it.copy(visible=true,locked=false)});vm.addStroke(edits("text").strokes.single());vm.inkLayers(edits("text").layers);vm.erase(setOf(vm.state.value.session!!.edits.strokes.single().id))}
        assertEquals(1,vm.state.value.session!!.edits.strokes.size)
        main {vm.paper(Paper.DOTS)};assertEquals(Paper.DOTS,vm.state.value.session!!.edits.paper)
        main {vm.undo()};assertEquals(Paper.BLANK,vm.state.value.session!!.edits.paper)
        main {vm.redo();vm.flush()};withTimeout(15000) {vm.state.first {!it.busy}}
        val restored=vm.repository.restore(vm.state.value.active!!);assertEquals(Paper.DOTS,restored.edits.paper);assertEquals(2,restored.edits.layers.size)
    }
}
