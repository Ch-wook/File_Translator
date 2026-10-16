package kr.documentstudio

import android.app.Application
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kr.documentstudio.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class DocumentActionsTest {
    private val app=ApplicationProvider.getApplicationContext<Application>()
    private fun main(block: ()->Unit)=InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private suspend fun ready(vm: EditorViewModel,count: Int)=withTimeout(15000) {vm.state.first {!it.busy && it.tabs.size==count}}
    @Test fun inactivePaneEditsHistorySaveAndRecoveryUseItsOwnDocument() = runBlocking {
        lateinit var vm: EditorViewModel;main {vm=EditorViewModel(app);vm.create(Format.TXT)}
        val first=ready(vm,1).tabs.first().id
        main {vm.create(Format.TXT)};val second=ready(vm,2).active!!
        val left=vm.documentActions(first);val right=vm.documentActions(second)
        main {left.text("왼쪽 문서");right.text("오른쪽 문서")}
        assertEquals(second,vm.state.value.active)
        fun text(id: String)=vm.state.value.tabs.first {it.id==id}.edits.text
        assertEquals("왼쪽 문서",text(first));assertEquals("오른쪽 문서",text(second))
        main {vm.undo(first)};assertEquals("",text(first));assertEquals("오른쪽 문서",text(second))
        main {vm.redo(first)};assertEquals("왼쪽 문서",text(first))
        val output=File(app.cacheDir,"tests/${UUID.randomUUID()}.txt").apply {parentFile!!.mkdirs();writeText("")}
        val uri=FileProvider.getUriForFile(app,"kr.documentstudio.testfiles",output)
        main {vm.save(uri,first)};ready(vm,2)
        assertEquals("왼쪽 문서",output.readText())
        assertEquals("오른쪽 문서",vm.repository.restore(second).edits.text)
        assertEquals(second,vm.state.value.active)
    }
    @Test fun callbacksFromAClosedPaneNeverFallBackToTheActiveDocument() = runBlocking {
        lateinit var vm: EditorViewModel;main {vm=EditorViewModel(app);vm.create(Format.TXT)}
        val first=ready(vm,1).active!!;val stale=vm.documentActions(first)
        main {vm.create(Format.TXT)};val second=ready(vm,2).active!!
        main {vm.text("보존");vm.close(first)};ready(vm,1)
        main {stale.text("늦은 입력");stale.erase(setOf("unknown"));stale.field("late","값")}
        assertEquals(second,vm.state.value.active);assertEquals("보존",vm.state.value.session!!.edits.text)
        assertTrue(vm.state.value.session!!.edits.values.isEmpty())
    }
}
