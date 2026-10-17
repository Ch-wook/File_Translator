package kr.documentstudio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import kr.documentstudio.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

class SheetDataUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun sheetToolsApplyUndoSaveAndReopen() {
        val vm=ViewModelProvider(compose.activity)[EditorViewModel::class.java]
        compose.runOnUiThread {vm.create(Format.XLSX)}
        compose.waitUntil(15000) {!vm.state.value.busy && vm.state.value.session?.format==Format.XLSX}
        val id=vm.state.value.session!!.sections.first().id
        compose.runOnUiThread {vm.field("$id#A1","Name");vm.field("$id#B1","Amount");vm.field("$id#C1","Status")}
        fun action(label: String) {
            compose.onNodeWithText("삽입·문서 도구").performClick()
            compose.onNodeWithText(label).performClick()
            compose.onNodeWithText("적용").performClick()
            compose.waitUntil(15000) {!vm.state.value.busy}
            assertFalse(vm.state.value.message.orEmpty().contains("실패"))
        }
        action("범위 테두리");val bordered=vm.state.value.session!!.base
        compose.runOnUiThread {vm.undo()};assertNotEquals(bordered,vm.state.value.session!!.base)
        compose.runOnUiThread {vm.redo()};assertEquals(bordered,vm.state.value.session!!.base)
        action("조건부 서식");action("드롭다운 목록");action("데이터 표 만들기")
        val output=File(compose.activity.cacheDir,"tests/${UUID.randomUUID()}.xlsx").apply {parentFile!!.mkdirs();writeBytes(byteArrayOf())}
        val uri=FileProvider.getUriForFile(compose.activity,"kr.documentstudio.testfiles",output)
        runBlocking {vm.repository.save(vm.state.value.session!!,uri)}
        val reopened=runBlocking {vm.repository.open(uri)}
        assertEquals("Name",reopened.sections.first().cells["A1"])
        ZipFile(reopened.base).use {zip->
            val xml=zip.getInputStream(zip.getEntry(reopened.sections.first().id)).use {Xml.parse(it.readBytes())}
            assertEquals(1,Xml.elements(xml,"conditionalFormatting").size)
            assertEquals(1,Xml.elements(xml,"dataValidation").size)
            assertEquals(1,Xml.elements(xml,"tablePart").size)
            assertTrue(zip.getEntry("xl/tables/table1.xml")!=null)
        }
    }
}
