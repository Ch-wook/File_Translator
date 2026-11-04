package kr.documentstudio

import android.net.Uri
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kr.documentstudio.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.zip.ZipFile

/** Runs the shipped native engine; distinct from editing XML in the host. */
class OfficeProfessionalFeaturesTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val automation get()=InstrumentationRegistry.getInstrumentation().uiAutomation
    private fun call(method: String,arg: String?=null): Bundle=compose.activity.contentResolver.call(Uri.parse("content://kr.documentstudio.officeprobe"),method,arg,null) ?: Bundle()
    private fun eval(script: String)=call("eval",script).getString("result")
    private fun send(message: String) {eval("app.socket.sendMessage(${JSONObject.quote(message)});true");Thread.sleep(250)}
    private fun uno(name: String,args: String="")=send("uno .uno:$name"+if(args.isEmpty()) "" else " $args")
    private fun paste(text: String)=send("paste mimetype=text/plain;charset=utf-8\n"+android.util.Base64.encodeToString(text.toByteArray(Charsets.UTF_8),android.util.Base64.NO_WRAP))
    private fun click(text: String) {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if(node==null) return null
            if((node.text?.toString()==text || node.contentDescription?.toString()==text) && node.isEnabled && node.isClickable) return node
            for(i in 0 until node.childCount) find(node.getChild(i))?.let {return it};return null
        }
        var target: AccessibilityNodeInfo?=null
        compose.waitUntil(30000) {target=find(automation.rootInActiveWindow);target!=null}
        assertTrue(target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun open(format: Format): EditorViewModel {
        automation.rootInActiveWindow
        val vm=ViewModelProvider(compose.activity)[EditorViewModel::class.java]
        compose.runOnUiThread {vm.create(format)};compose.waitUntil(15000) {!vm.state.value.busy && vm.state.value.session?.format==format}
        compose.onNodeWithContentDescription("문서 메뉴").performClick()
        compose.onNodeWithText("원본 문서 편집").performScrollTo().performClick()
        compose.waitUntil(45000) {runCatching {call("state").getBoolean("ready")}.getOrDefault(false)}
        compose.waitUntil(20000) {call("state").getBoolean("editing") && eval("Boolean(window.app && app.map && app.map._docLoaded && app.map.isEditMode())")=="true"}
        Thread.sleep(1200);return vm
    }
    private fun close(vm: EditorViewModel) {call("close");compose.waitUntil(45000) {!vm.state.value.busy}}
    @Test fun writerHeadingNumberingFootnoteAndTrackedTextAreNativeStoredParts() {
        val vm=open(Format.DOCX);val original=vm.state.value.session!!.base
        try {
            uno("SelectAll");paste("ENGINE_HEADING")
            send("uno .uno:StyleApply {\"Style\":{\"type\":\"string\",\"value\":\"Heading 1\"},\"FamilyName\":{\"type\":\"string\",\"value\":\"ParagraphStyles\"}}")
            paste("\nENGINE_LIST");uno("DefaultNumbering")
            uno("InsertFootnote");paste("ENGINE_FOOTNOTE")
            uno("TrackChanges");paste(" ENGINE_TRACKED")
            close(vm)
            val result=vm.state.value.session!!;assertNotEquals(original,result.base)
            ZipFile(result.base).use {zip->
                fun xml(name: String)=zip.getInputStream(zip.getEntry(name) ?: error("Missing $name")).use {Xml.parse(it.readBytes())}
                val body=xml("word/document.xml");val notes=xml("word/footnotes.xml");val settings=xml("word/settings.xml")
                assertTrue(Xml.elements(body,"t").any {it.textContent.contains("ENGINE_HEADING")})
                assertTrue(Xml.elements(body,"pStyle").any {it.getAttributeNS(PackageDocument.W,"val").contains("Heading")})
                assertTrue(Xml.elements(body,"numId").any {it.getAttributeNS(PackageDocument.W,"val")!="0"});assertTrue(Xml.elements(body,"footnoteReference").isNotEmpty())
                assertTrue(Xml.elements(notes,"t").any {it.textContent.contains("ENGINE_FOOTNOTE")})
                assertTrue(Xml.elements(notes,"ins").isNotEmpty());assertTrue(Xml.elements(settings,"trackRevisions").isNotEmpty())
            }
        } finally {if(vm.state.value.busy) runCatching {close(vm)}}
    }
    @Test fun calcFormulaAndCurrencyAreStoredByNativeEngine() {
        val vm=open(Format.XLSX)
        try {
            for((cell,value) in listOf("A1" to "12","B1" to "30","C1" to "=SUM(A1:B1)")) {
                uno("GoToCell","{\"ToPoint\":{\"type\":\"string\",\"value\":\"$cell\"}}")
                paste(value)
            }
            uno("GoToCell","{\"ToPoint\":{\"type\":\"string\",\"value\":\"C1\"}}")
            uno("NumberFormatCurrency");close(vm)
            val result=vm.state.value.session!!
            assertEquals(12.0,result.sections.first().cells["A1"]!!.toDouble(),0.0)
            assertEquals("=SUM(A1:B1)",result.sections.first().cells["C1"])
            ZipFile(result.base).use {zip->
                val sheet=zip.getInputStream(zip.getEntry(result.sections.first().id)).use {Xml.parse(it.readBytes())}
                val cell=Xml.elements(sheet,"c").single {it.getAttribute("r")=="C1"}
                assertEquals(42.0,Xml.child(cell,"v")!!.textContent.toDouble(),0.0)
                assertTrue(cell.getAttribute("s").toInt()>0)
            }
        } finally {if(vm.state.value.busy) runCatching {close(vm)}}
    }
    @Test fun impressLayoutAndNewSlideAreStoredByNativeEngine() {
        val vm=open(Format.PPTX);val before=vm.state.value.session!!.sections.size
        try {
            uno("AssignLayout","{\"WhatPage\":{\"type\":\"unsigned short\",\"value\":\"0\"},\"WhatLayout\":{\"type\":\"unsigned short\",\"value\":\"1\"}}")
            uno("InsertPage")
            compose.waitUntil(15000) {eval("app.map.getNumberOfParts()")=="${before+1}"}
            close(vm)
            val result=vm.state.value.session!!;assertEquals(before+1,result.sections.size)
            ZipFile(result.base).use {zip->
                val presentation=zip.getInputStream(zip.getEntry("ppt/presentation.xml")).use {Xml.parse(it.readBytes())}
                assertEquals(before+1,Xml.elements(presentation,"sldId").size)
                val relations=zip.getInputStream(zip.getEntry("ppt/slides/_rels/slide1.xml.rels")).use {Xml.parse(it.readBytes())}
                assertTrue(Xml.elements(relations,"Relationship").any {it.getAttribute("Type").endsWith("/slideLayout")})
            }
        } finally {if(vm.state.value.busy) runCatching {close(vm)}}
    }
}
