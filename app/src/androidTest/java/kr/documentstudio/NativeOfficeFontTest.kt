package kr.documentstudio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import kr.documentstudio.core.*
import kr.documentstudio.data.DocumentRepository
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/** Real Writer selection -> common font panel -> native save -> parser and engine reopen. */
@RunWith(AndroidJUnit4::class)
class NativeOfficeFontTest {
    private val context: Context=ApplicationProvider.getApplicationContext()
    private val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val probe=Uri.parse("content://${context.packageName}.officeprobe")
    private fun eval(script: String): String {
        val raw=context.contentResolver.acquireUnstableContentProviderClient(probe).use {client->
            checkNotNull(client).call("eval",script,null)!!.getString("result")!!
        }
        return JSONTokener(raw).nextValue().toString()
    }
    private fun waitFor(message: String,timeout: Long=30000,condition: ()->Boolean){
        val end=SystemClock.uptimeMillis()+timeout
        while(SystemClock.uptimeMillis()<end){if(runCatching(condition).getOrDefault(false))return;SystemClock.sleep(100)}
        assertTrue(message,runCatching(condition).getOrDefault(false))
    }
    private fun tap(label: String){
        eval("(function(){const e=Array.from(document.querySelectorAll('button')).find(e=>e.getAttribute('aria-label')==="+JSONObject.quote(label)+"||e.textContent==="+JSONObject.quote(label)+");if(!e)throw new Error('Missing control');e.scrollIntoView({block:'center'});})()")
        waitFor("Visible Office tool: $label"){device.findObject(By.pkg(context.packageName).desc(label).enabled(true))!=null||device.findObject(By.pkg(context.packageName).text(label).enabled(true))!=null}
        (device.findObject(By.pkg(context.packageName).desc(label).enabled(true))?:device.findObject(By.pkg(context.packageName).text(label).enabled(true)))!!.click()
    }
    @Test fun partialFontSizeAndColorSurviveNativeSaveWithoutChangingAdjacentText()=runBlocking {
        val repo=DocumentRepository(context);var seed=repo.create(Format.DOCX)
        val text="KEEP_LEFT 선택TARGET KEEP_RIGHT";val target="선택TARGET"
        seed=seed.copy(edits=seed.edits.copy(values=mapOf(seed.sections.first().fields.first().id to text)))
        val file=File(context.cacheDir,"tests/ui-font-${UUID.randomUUID()}.docx").apply {parentFile!!.mkdirs();writeBytes(byteArrayOf())}
        val uri=FileProvider.getUriForFile(context,context.packageName+".testfiles",file)
        val session=repo.save(seed,uri);val before=DocumentRepository.digest(file);var working=repo.prepareOffice(session)
        val out=File(context.getExternalFilesDir(null),"native-office-font").apply {mkdirs()}
        var closed=false
        fun launch(id: String,name: String){context.startActivity(Intent().setClassName(context.packageName,"org.libreoffice.androidlib.StudioOfficeActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("sessionId",id).putExtra("workingPath",working.absolutePath).putExtra("displayName",name))}
        try {
            device.wakeUp();launch(session.id,session.name)
            waitFor("Real Office host loads",150000){eval("window.studioOffice?.ready===true")=="true"}
            assertEquals("48",eval("document.getElementById('studio-office-header').getBoundingClientRect().height"))
            assertEquals("true",eval("document.getElementById('studio-office-bottom').hidden"))
            tap("편집하기");waitFor("Explicit edit mode settles"){eval("app.map.isEditMode() && !document.getElementById('studio-office-back').disabled")=="true"}
            eval("app.map.search('KEEP_LEFT',false)");SystemClock.sleep(800)
            val originalFont=eval("app.map.stateChangeHandler.getItemValue('.uno:CharFontName')")
            val originalSize=eval("app.map.stateChangeHandler.getItemValue('.uno:FontHeight')")
            eval("app.map.search("+JSONObject.quote(target)+",false)");SystemClock.sleep(800)
            tap("글자")
            val label=eval("document.querySelector('[data-current-font]').getAttribute('aria-label')")
            tap(label)
            waitFor("Actual Korean font exists in engine and shared picker"){eval("Boolean(document.querySelector('[role=option][aria-label=\"Noto Sans KR\"]'))")=="true"}
            tap("Noto Sans KR");SystemClock.sleep(500)
            tap("18");waitFor("Selected text changes size"){eval("Number(app.map.stateChangeHandler.getItemValue('.uno:FontHeight'))")=="18"}
            tap("굵게");tap("글자색 #e53935")
            tap("설정 닫기")
            eval("app.map.search('KEEP_LEFT',false)");SystemClock.sleep(800)
            assertEquals(originalFont,eval("app.map.stateChangeHandler.getItemValue('.uno:CharFontName')"))
            assertEquals(originalSize,eval("app.map.stateChangeHandler.getItemValue('.uno:FontHeight')"))
            device.takeScreenshot(File(out,"docx-compact-font.png"))
            tap("저장 후 닫기")
            waitFor("Native save closes before accepting output",45000){File(working.path+".close-request").isFile}
            waitFor("Office writer releases its process"){device.executeShellCommand("pidof ${context.packageName}:office").trim().isEmpty()}
            assertTrue(repo.completeOfficeClose(session));closed=true
            assertEquals(before,DocumentRepository.digest(file))
            assertTrue(File(working.path+".edited").isFile)
            working.copyTo(File(out,"selected-font-output.docx"),true)
            ZipFile(working).use {z->
                val doc=Xml.parse(z.getInputStream(z.getEntry("word/document.xml")).use {it.readBytes()})
                val runs=Xml.elements(doc,"r")
                val matching=runs.filter {r->Xml.elements(r,"t").joinToString(""){it.textContent}.contains("TARGET")}
                assertTrue("Native save must retain the selected text run",matching.isNotEmpty())
                assertTrue("Only selected text must store 18pt",matching.any {r->Xml.elements(r,"sz").any {it.getAttributeNS(PackageDocument.W,"val")=="36"}})
                assertTrue("Selected run must retain font and color together",matching.any {r->
                    Xml.elements(r,"rFonts").any {it.getAttributeNS(PackageDocument.W,"ascii")=="Noto Sans KR"||it.getAttributeNS(PackageDocument.W,"eastAsia")=="Noto Sans KR"}&&
                    Xml.elements(r,"color").any {it.getAttributeNS(PackageDocument.W,"val").equals("E53935",true)}
                })
                val unchanged=runs.filter {r->Xml.elements(r,"t").joinToString(""){it.textContent}.contains("KEEP_")}
                assertTrue(unchanged.isNotEmpty());assertFalse("Unselected text cannot inherit the selected size",unchanged.any {r->Xml.elements(r,"sz").any {it.getAttributeNS(PackageDocument.W,"val")=="36"}})
                assertEquals(text,Xml.elements(doc,"t").joinToString(""){it.textContent})
            }
            val accepted=repo.acceptOffice(session);val saved=repo.save(accepted,uri);assertFalse(saved.dirty)
            val reopened=repo.open(uri);working=repo.prepareOffice(reopened);closed=false
            launch(reopened.id,reopened.name);waitFor("Saved DOCX reopens in VIEW",150000){eval("window.studioOffice?.ready===true && !app.map.isEditMode()")=="true"}
            tap("돌아가기");waitFor("Reading close request"){File(working.path+".close-request").isFile}
            waitFor("Reopened Office releases writer"){device.executeShellCommand("pidof ${context.packageName}:office").trim().isEmpty()}
            assertFalse(repo.completeOfficeClose(reopened));closed=true
            File(out,"summary.json").writeText(JSONObject().put("selectedNativeFont",true).put("independentSizeAndColor",true).put("unselectedPreserved",true).put("nativeSaved",true).put("parserReopened",true).put("engineReopened",true).put("originalProtected",true).put("physicalDeviceTested",false).toString(2))
        } catch(error: Throwable){
            runCatching {device.takeScreenshot(File(out,"failure.png"));device.dumpWindowHierarchy(File(out,"failure.xml"));File(out,"failure.txt").writeText(error.stackTraceToString())};throw error
        } finally {if(!closed)runCatching {context.contentResolver.acquireUnstableContentProviderClient(probe).use {it?.call("close",null,null)}}}
    }
}
