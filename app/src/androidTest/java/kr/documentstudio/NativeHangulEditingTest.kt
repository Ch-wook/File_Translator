package kr.documentstudio

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import kr.documentstudio.core.Format
import kr.documentstudio.core.LegacyDocument
import kr.documentstudio.core.PackageDocument
import kr.documentstudio.data.DocumentRepository
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real Android IME/InputConnection acceptance, using a Java service confined to the instrumentation APK. */
@RunWith(AndroidJUnit4::class)
class NativeHangulEditingTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ApplicationProvider.getApplicationContext<Application>()
    private val device get()=UiDevice.getInstance(instrumentation)
    private val provider get()=Uri.parse("content://"+context.packageName+".hangulprobe")
    private val output get()=File(context.getExternalFilesDir(null),"native-hangul-editing").apply {mkdirs()}
    private var sequence=0
    private val imeCommands=org.json.JSONArray()
    private fun probe(method: String,arg: String?=null,data: Bundle?=null): Bundle =
        requireNotNull(context.contentResolver.call(provider,method,arg,data))
    private fun value(expression: String): Any? {
        val raw=probe("eval","JSON.stringify(("+expression+"))").getString("result")
        val first=JSONTokener(raw ?: "null").nextValue()
        return if(first is String) JSONTokener(first).nextValue() else first
    }
    private fun json(expression: String)=value(expression) as JSONObject
    private fun await(label: String,timeout: Long=30000,condition: ()->Boolean) {
        val until=SystemClock.uptimeMillis()+timeout
        while(SystemClock.uptimeMillis()<until) {
            if(runCatching(condition).getOrDefault(false))return
            SystemClock.sleep(100)
        }
        device.takeScreenshot(File(output,"failure.png"))
        device.dumpWindowHierarchy(File(output,"failure.xml"))
        error(label)
    }
    private fun eval(script: String) {probe("eval",script)}
    private fun ime(command: String,text: String="",key: Int=0) {
        val id=++sequence
        probe("ime",data=Bundle().apply {putString("command",command);putString("text",text);putInt("key",key);putInt("sequence",id)})
        await("The actual Android InputConnection command must be acknowledged: "+command) {imeStatus().optInt("sequence")==id}
        val acknowledged=imeStatus();imeCommands.put(acknowledged)
        assertTrue("Android InputConnection must accept "+command,acknowledged.optBoolean("accepted"))
    }
    private fun imeStatus(): JSONObject=runCatching {
        JSONObject(device.executeShellCommand("run-as "+instrumentation.context.packageName+" cat files/ime-fixture-status.json"))
    }.getOrDefault(JSONObject())
    private fun awaitConnection(kind: Int) {
        await("Android must connect the focused input to IME class "+kind) {
            val id=++sequence
            probe("ime",data=Bundle().apply {putString("command","status");putInt("sequence",id)})
            SystemClock.sleep(80)
            imeStatus().optInt("sequence")==id && (imeStatus().optInt("inputType") and 15) == kind
        }
    }
    private fun count(text: String)= (value("window.rhwpStudio.documentStudio.mobile.count("+JSONObject.quote(text)+")") as Number).toInt()
    private fun engine(script: String)=eval("document.getElementById('engine').contentWindow."+script)
    private fun engineJson(script: String)=json("document.getElementById('engine').contentWindow."+script)
    // Probe JS runs in the outer host. Use this for controller expressions inside the iframe.
    private fun m(script: String): Any?=value("document.getElementById('engine').contentWindow.rhwpStudio.documentStudio.mobile."+script)
    private fun mJson(script: String)=m(script) as JSONObject
    private fun mCount(text: String)=(m("count("+JSONObject.quote(text)+")") as Number).toInt()
    private fun control(label: String) = device.wait(Until.findObject(By.pkg(context.packageName).desc(label)),5000)
        ?: device.wait(Until.findObject(By.pkg(context.packageName).text(label)),5000)
        ?: error("Visible control missing: "+label)
    private fun revealControl(label: String) {
        eval("(function(){const e=Array.from(document.querySelectorAll('button,input,select')).find(e=>e.getAttribute('aria-label')==="+JSONObject.quote(label)+"||e.textContent==="+JSONObject.quote(label)+");if(!e)throw new Error('Missing control');const p=document.getElementById('detail-sheet');if(p.contains(e)){const r=e.getBoundingClientRect(),b=p.getBoundingClientRect();p.scrollTop+=r.top-b.top-(p.clientHeight-r.height)/2;}else e.scrollIntoView({block:'nearest',inline:'center'});})()")
    }
    private fun tapVisibleControl(label: String) {
        revealControl(label)
        var bounds=JSONObject()
        await("The real WebView button must be visible and receive touch: "+label) {
            bounds=json("(function(){const e=Array.from(document.querySelectorAll('button,input,select')).find(e=>e.getAttribute('aria-label')==="+JSONObject.quote(label)+"||e.textContent==="+JSONObject.quote(label)+");if(!e)return {};const r=e.getBoundingClientRect(),panel=document.getElementById('mobile-panel'),p=panel.contains(e)?panel.getBoundingClientRect():{top:0,bottom:innerHeight},x=r.left+r.width/2,y=r.top+r.height/2,hit=document.elementFromPoint(x,y);return {x,y,visible:r.width>0&&r.height>0&&y>=p.top&&y<=p.bottom&&y<innerHeight&&x>=0&&x<innerWidth,hit:hit===e||e.contains(hit),disabled:e.disabled,width:innerWidth,height:innerHeight};})()")
            bounds.optBoolean("visible") && bounds.optBoolean("hit") && !bounds.optBoolean("disabled")
        }
        val browser=probe("geometry")
        val scale=browser.getInt("width")/bounds.getDouble("width")
        assertTrue(device.click(browser.getInt("left")+(bounds.getDouble("x")*scale).toInt(),
            browser.getInt("top")+(bounds.getDouble("y")*scale).toInt()))
    }
    @Test fun hwpKoreanImeSelectionFormattingAndSafeSave()=exercise(Format.HWP)
    @Test fun hwpxKoreanImeSelectionFormattingAndSafeSave()=exercise(Format.HWPX)

    private fun exercise(format: Format)=runBlocking {
        val repository=DocumentRepository(context)
        val session=repository.create(format)
        val working=repository.prepareOffice(session)
        val baseline=DocumentRepository.digest(session.base)
        val imeId=instrumentation.context.packageName+"/kr.documentstudio.fixtures.KoreanImeFixture"
        val previousIme=device.executeShellCommand("settings get secure default_input_method").trim()
        var closed=false
        try {
            device.wakeUp();device.executeShellCommand("am force-stop com.android.launcher3")
            device.executeShellCommand("ime enable "+imeId)
            device.executeShellCommand("ime set "+imeId)
            context.startActivity(Intent(context,HangulEditorActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("sessionId",session.id)
                .putExtra("workingPath",working.absolutePath).putExtra("displayName","한국어 입력 검증."+format.extension))
            await("Original document must load in VIEW",150000) {json("({state:document.body.dataset.state})").optString("state")=="READING"}
            control("편집하기").click()
            await("Explicit editing must become active") {json("({state:document.body.dataset.state})").optString("state")=="EDITING"}
            eval("document.getElementById('engine').contentWindow.rhwpStudio.documentStudio.mobile.plainText('IME_START|');document.getElementById('engine').contentWindow.rhwpStudio.documentStudio.mobile.focus()")
            probe("show-ime")
            awaitConnection(1)
            await("The test-only Korean IME must appear") {device.hasObject(By.text("한국어 입력 검증 IME")) || device.hasObject(By.textStartsWith("IME 요청 "))}
            for(text in listOf("ㅎ","하","한"))ime("compose",text)
            ime("commit","한");ime("compose","ㄱ");ime("compose","그");ime("compose","글");ime("commit","글")
            await("Korean syllables must compose once without separated jamo") {mCount("IME_START|한글")==1}
            assertEquals(0,mCount("IME_START|ㅎ"));assertEquals(0,mCount("한한"))
            eval("document.getElementById('engine').contentWindow.rhwpStudio.documentStudio.mobile.find('한글')")
            for(text in listOf("ㅎ","하","한","한국","한국어"))ime("compose",text)
            ime("commit","한국어")
            await("IME must replace only the selected text") {mCount("IME_START|한국어")==1 && mCount("IME_START|한글")==0}
            eval("document.getElementById('engine').contentWindow.rhwpStudio.documentStudio.mobile.dispatch('edit:undo')")
            await("One undo must restore the selected Korean text and preserve its prefix") {mCount("IME_START|한글")==1 && mCount("IME_START|한국어")==0}
            eval("document.getElementById('engine').contentWindow.rhwpStudio.documentStudio.mobile.dispatch('edit:redo')")
            await("One redo must repeat the committed Korean replacement") {mCount("IME_START|한국어")==1 && mCount("IME_START|한글")==0}
            eval("document.getElementById('engine').contentWindow.rhwpStudio.documentStudio.mobile.find('한국어')")
            ime("commit","DIRECT")
            await("Direct Android commit must replace only the selected text") {mCount("IME_START|DIRECT")==1}
            eval("document.getElementById('engine').contentWindow.rhwpStudio.documentStudio.mobile.dispatch('edit:undo')")
            await("One undo must restore a direct commit replacement") {mCount("IME_START|한국어")==1 && mCount("DIRECT")==0}
            ime("compose","가");ime("commit","가");ime("compose","가");ime("commit","가")
            await("Repeated composed syllables must not disappear") {mCount("IME_START|한국어가가")==1}
            eval("document.getElementById('engine').contentWindow.rhwpStudio.documentStudio.mobile.find('한국어')")
            val rangeBefore=mJson("capture()").getJSONObject("range").toString()
            tapVisibleControl("글자")
            tapVisibleControl("크기(pt)")
            awaitConnection(2)
            ime("selectAll");ime("commit","18")
            await("The numeric field must apply only the selected document text") {mJson("state()").getJSONObject("char").optInt("fontSize")==1800}
            tapVisibleControl("글자색 #e53935")
            await("A color change must preserve the selected size") {
                val p=mJson("state()").getJSONObject("char");p.optString("textColor").lowercase()=="#e53935" && p.optInt("fontSize")==1800
            }
            assertEquals("Opening and editing the panel must preserve the document range",rangeBefore,mJson("capture()").getJSONObject("range").toString())
            tapVisibleControl("설정 완료")
            probe("show-ime")
            // A fixture acknowledgement confirms dispatch, not that WebView has
            // switched away from the numeric field or consumed the arrow key.
            awaitConnection(1)
            ime("key",key=KeyEvent.KEYCODE_DPAD_RIGHT)
            await("The document must consume Right and collapse its retained selection before typing") {
                !mJson("state()").optBoolean("selection") && mCount("IME_START|한국어가가")==1
            }
            ime("commit"," ABC123")
            await("Typing must continue at the retained document cursor") {mCount("IME_START|한국어 ABC123가가")==1}
            eval("document.getElementById('engine').contentWindow.rhwpStudio.documentStudio.mobile.find('한국어');document.getElementById('engine').contentWindow.rhwpStudio.documentStudio.mobile.copy()")
            eval("document.getElementById('engine').contentWindow.rhwpStudio.documentStudio.mobile.find('가가');document.getElementById('engine').contentWindow.rhwpStudio.documentStudio.mobile.paste(false)")
            await("Android HTML clipboard must paste formatted text") {mCount("IME_START|한국어 ABC123한국어")==1}
            eval("document.getElementById('engine').contentWindow.rhwpStudio.documentStudio.mobile.find('한국어');document.getElementById('engine').contentWindow.rhwpStudio.documentStudio.mobile.find('한국어')")
            assertEquals(1800,mJson("state()").getJSONObject("char").getInt("fontSize"))
            eval("document.getElementById('engine').contentWindow.rhwpStudio.documentStudio.mobile.dispatch('edit:undo')")
            await("One undo must restore the replaced Korean text") {mCount("IME_START|한국어 ABC123가가")==1}
            eval("document.getElementById('engine').contentWindow.rhwpStudio.documentStudio.mobile.find('ABC123');document.getElementById('engine').contentWindow.rhwpStudio.documentStudio.mobile.copy(true)")
            await("Android cut must remove only the selected text") {mCount("ABC123")==0}
            eval("document.getElementById('engine').contentWindow.rhwpStudio.documentStudio.mobile.dispatch('edit:undo')")
            await("Cut undo must preserve surrounding Korean and formatting") {mCount("IME_START|한국어 ABC123가가")==1}
            eval("document.getElementById('engine').contentWindow.rhwpStudio.documentStudio.mobile.navigate(true);document.getElementById('engine').contentWindow.rhwpStudio.documentStudio.mobile.plainText('\\n');document.getElementById('engine').contentWindow.rhwpStudio.documentStudio.mobile.createTable(2,2);document.getElementById('engine').contentWindow.rhwpStudio.documentStudio.mobile.focus()")
            probe("show-ime")
            for(text in listOf("ㅅ","세","셀","셀입력"))ime("compose",text)
            ime("commit","셀입력")
            await("The actual Android IME must enter Korean in the real table cell") {mCount("셀입력")==1}
            eval("document.getElementById('engine').contentWindow.rhwpStudio.documentStudio.mobile.moveCell(true);document.getElementById('engine').contentWindow.rhwpStudio.documentStudio.mobile.focus()")
            ime("commit","KEEP_CELL")
            eval("document.getElementById('engine').contentWindow.rhwpStudio.documentStudio.mobile.find('셀입력');document.getElementById('engine').contentWindow.rhwpStudio.documentStudio.mobile.char({fontSize:1200,underline:true})")
            assertEquals(1200,mJson("state()").getJSONObject("char").getInt("fontSize"))
            assertTrue(mJson("state()").getJSONObject("char").getBoolean("underline"))
            eval("document.getElementById('engine').contentWindow.rhwpStudio.documentStudio.mobile.find('KEEP_CELL')")
            assertFalse("Cell character formatting must leave another cell unchanged",mJson("state()").getJSONObject("char").getBoolean("underline"))
            eval("document.getElementById('engine').contentWindow.rhwpStudio.documentStudio.mobile.find('셀입력');document.getElementById('engine').contentWindow.rhwpStudio.documentStudio.mobile.focus()")
            ime("key",key=KeyEvent.KEYCODE_DPAD_RIGHT)
            val viewport=json("(function(){const f=document.getElementById('engine'),r=f.getBoundingClientRect(),c=f.contentDocument.querySelector('.caret').getBoundingClientRect();return {frameTop:r.top,frameBottom:r.bottom,caretBottom:r.top+c.bottom,caretTop:r.top+c.top,caretHeight:c.height,height:innerHeight};})()")
            assertTrue("The IME must not cover the active caret",viewport.getDouble("caretHeight")>0 && viewport.getDouble("caretTop")>=viewport.getDouble("frameTop")-3 && viewport.getDouble("caretBottom")<=viewport.getDouble("frameBottom")+3)
            device.takeScreenshot(File(output,format.extension+"-keyboard-format.png"))
            assertEquals("Working-copy edits must not overwrite the baseline",baseline,DocumentRepository.digest(session.base))
            await("The real save button must be enabled") {json("({enabled:!document.getElementById('save').disabled})").optBoolean("enabled")}
            tapVisibleControl("저장 후 닫기")
            await("Validated HWP work copy must close",60000) {File(working.path+".closed").isFile}
            await("Hangul writer must release its process",20000) {device.executeShellCommand("pidof "+context.packageName+":hangul").trim().isEmpty()}
            closed=true
            assertTrue(File(working.path+".edited").isFile)
            working.copyTo(File(output,format.extension+"-saved-working."+format.extension),overwrite=true)
            val adopted=repository.acceptOffice(session)
            assertNotEquals(baseline,DocumentRepository.digest(adopted.base))
            val file=File(context.cacheDir,"tests/"+UUID.randomUUID()+"."+format.extension).apply {parentFile!!.mkdirs();writeBytes(byteArrayOf())}
            val uri=FileProvider.getUriForFile(context,context.packageName+".testfiles",file)
            repository.save(adopted,uri)
            val parsed=if(format==Format.HWP) LegacyDocument.open(file).sections else PackageDocument.open(file).sections
            assertTrue("Independent parser must reopen the saved Korean content",
                parsed.flatMap {it.fields}.joinToString("") {it.text}.contains("IME_START|한국어 ABC123가가"))
            val reopened=repository.open(uri)
            val again=repository.prepareOffice(reopened)
            closed=false
            val originalHash=DocumentRepository.digest(file)
            context.startActivity(Intent(context,HangulEditorActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("sessionId",reopened.id).putExtra("workingPath",again.absolutePath)
                .putExtra("displayName","재열기."+format.extension))
            await("Saved document must reopen in VIEW",150000) {json("({state:document.body.dataset.state})").optString("state")=="READING"}
            eval("document.getElementById('engine').contentWindow.rhwpStudio.documentStudio.mobile.find('한국어')")
            val props=mJson("state()").getJSONObject("char")
            assertEquals(1800,props.getInt("fontSize"));assertEquals("#e53935",props.getString("textColor").lowercase())
            eval("document.getElementById('engine').contentWindow.rhwpStudio.documentStudio.mobile.find('셀입력')")
            val cellProps=mJson("state()").getJSONObject("char")
            assertEquals(1200,cellProps.getInt("fontSize"));assertTrue(cellProps.getBoolean("underline"))
            assertEquals(1,mCount("KEEP_CELL"))
            assertEquals(originalHash,DocumentRepository.digest(file))
            eval("window.documentStudioHost.returnToWorkspace()")
            await("Read-only reopening must close",30000) {File(again.path+".closed").isFile}
            await("Read-only writer must release its process",20000) {device.executeShellCommand("pidof "+context.packageName+":hangul").trim().isEmpty()}
            closed=true
            val evidence=JSONObject().put("format",format.extension).put("actualAndroidInputConnection",true).put("imeFixtureOnlyInTestApk",true)
                .put("koreanComposition",true).put("repeatedSyllables",true).put("selectedReplacement",true).put("panelSelectionRetained",true)
                .put("panelImeReconnected",true).put("arrowAppliedBeforeTyping",true).put("cellKoreanInput",true).put("cellScopedCharacterFormat",true).put("androidHtmlClipboard",true).put("atomicSelectionReplacementUndo",true)
                .put("independentParserReopen",true).put("engineReopen",true).put("originalHashBefore",baseline)
                .put("savedFileHash",originalHash).put("viewport",viewport).put("physicalKeyboardDeviceTested",false)
            File(output,format.extension+"-editing.json").writeText(evidence.toString(2))
        } catch(error: Throwable) {
            runCatching {device.takeScreenshot(File(output,format.extension+"-failure.png"));device.dumpWindowHierarchy(File(output,format.extension+"-failure.xml"))}
            runCatching {File(output,format.extension+"-failure.json").writeText(JSONObject().put("error",error.toString()).put("cursorText",m("cursorText()")).put("ime",imeStatus()).put("imeCommands",imeCommands).put("state",mJson("state()")).toString(2))}
            throw error
        } finally {
            if(previousIme.isNotBlank() && previousIme!="null")device.executeShellCommand("ime set "+previousIme)
            device.executeShellCommand("ime disable "+imeId)
            if(!closed) {
                runCatching {probe("close")}
                val cleanupDeadline=SystemClock.uptimeMillis()+20000
                while(SystemClock.uptimeMillis()<cleanupDeadline && device.executeShellCommand("pidof "+context.packageName+":hangul").trim().isNotEmpty())SystemClock.sleep(100)
            }
        }
    }
}
