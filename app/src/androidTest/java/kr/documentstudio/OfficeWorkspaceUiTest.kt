package kr.documentstudio

import android.net.Uri
import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kr.documentstudio.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.zip.ZipFile

/** Native engine integration, including the shipped toolbar and saved object geometry. */
class OfficeWorkspaceUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val vm get()=ViewModelProvider(compose.activity)[EditorViewModel::class.java]
    private fun call(method: String,arg: String?=null): Bundle=compose.activity.contentResolver.call(Uri.parse("content://kr.documentstudio.officeprobe"),method,arg,null) ?: Bundle()
    private fun eval(script: String)=call("eval",script).getString("result")
    private fun click(label: String) {assertEquals("true",eval("(() => { const b=Array.from(document.querySelectorAll('#studio-office-tools button')).find(b=>b.textContent===${JSONObject.quote(label)}); if(!b || b.disabled) return false; b.click(); return true; })()"))}
    private fun ready() {compose.waitUntil(120000) {!vm.state.value.busy}}
    private fun open(format: Format,shape: Boolean=false) {
        compose.runOnUiThread {vm.create(format)};ready()
        if(shape) {
            val section=vm.state.value.session!!.sections.first().id
            compose.runOnUiThread {vm.change(DocumentChange("slide.rectangle",section,mapOf("x" to "2","y" to "2","width" to "4","height" to "3","color" to "2858A5")))};ready()
        }
        compose.onNodeWithContentDescription("문서 메뉴").performClick()
        compose.onNodeWithText("정밀 오피스 편집").performScrollTo().performClick()
        compose.waitUntil(180000) {runCatching {call("state").getBoolean("editing") && eval("Boolean(window.studioOffice && studioOffice.ready && app.map.isEditMode())")=="true"}.getOrDefault(false)}
    }
    private fun close() {call("close");ready()}
    @Test fun officeFamiliesOpenEditableWithTheirOwnToolsWithoutAnExtraPencilTap() {
        for(format in listOf(Format.DOCX,Format.XLSX,Format.PPTX)) {
            open(format)
            try {
                assertEquals("true",eval("document.querySelector('#studio-office-tools [role=tab][aria-selected=true]').textContent==='홈'"))
                click("개체")
                assertEquals("true",eval("document.querySelector('#studio-office-actions [data-command=TransformDialog]').disabled"))
                if(format==Format.XLSX) click("셀")
                if(format==Format.PPTX) click("슬라이드")
            } finally {close()}
        }
    }
    @Test fun impressObjectSelectionAndDragResizePersistNativeGeometry() {
        open(Format.PPTX,shape=true)
        try {
            // Native mouse coordinates use twips. Click the blue rectangle at 3cm,3cm.
            eval("app.map._docLayer._postMouseEvent('buttondown',1701,1701,1,1,0);app.map._docLayer._postMouseEvent('buttonup',1701,1701,1,1,0);true")
            compose.waitUntil(30000) {eval("GraphicSelection.hasActiveSelection()")=="true"}
            click("개체")
            compose.waitUntil(10000) {eval("!document.querySelector('[data-command=TransformDialog]').disabled")=="true"}
            // The same engine operation used by dragging/resizing its selection handles.
            eval("GraphicSelection.sendElementArea(new cool.SimpleRectangle(2268,1701,3402,2268));true")
            Thread.sleep(1500)
            close()
            ZipFile(vm.state.value.session!!.base).use {zip->
                val document=Xml.parse(zip.getInputStream(zip.getEntry(vm.state.value.session!!.sections.first().id)).readBytes())
                val transform=Xml.elements(document,"xfrm").first {t->Xml.child(t,"off")?.getAttribute("x")?.toLongOrNull()?.let {kotlin.math.abs(it-1440000L)<2000L}==true}
                fun near(element: String,attribute: String,expected: Long) {assertTrue(kotlin.math.abs(Xml.child(transform,element)!!.getAttribute(attribute).toLong()-expected)<2000L)}
                near("off","y",1080000L);near("ext","cx",2160000L);near("ext","cy",1440000L)
            }
        } finally {if(vm.state.value.busy) runCatching {close()}}
    }
}
