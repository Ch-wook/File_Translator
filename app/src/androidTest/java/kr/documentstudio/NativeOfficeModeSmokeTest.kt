package kr.documentstudio

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Rect
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import kr.documentstudio.core.Format
import kr.documentstudio.data.DocumentRepository
import kr.documentstudio.data.Session
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.math.abs

/**
 * Real separate-process Office + Android WebView smoke coverage.
 * UI mode and production viewport metadata must both exist after native document load.
 * This tests attempted ASCII keys/Korean clipboard paste, not Korean IME composition,
 * object hit-testing, or arbitrary typed text. Three separate cases exercise actual
 * structural edits and native/provider save plus independent-parser and engine reopen.
 */
@RunWith(AndroidJUnit4::class)
class NativeOfficeModeSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val device = UiDevice.getInstance(instrumentation)
    private val evidence by lazy {
        File(context.getExternalFilesDir(null), "native-office-mode-smoke").apply { mkdirs() }
    }
    @Before fun supportedAndroid() {
        assumeTrue("Native Office requires Android 11+", Build.VERSION.SDK_INT >= 30)
        device.wakeUp()
        // Reset the CI AOSP Quickstep process; its ANR must not cover the app under test.
        device.executeShellCommand("am force-stop com.android.launcher3")
    }

    @Test fun docReadOnlyUsesActualNativeEngine() = runBlocking { exercise(Format.DOC) }
    @Test fun docxReadOnlyUsesActualNativeEngine() = runBlocking { exercise(Format.DOCX) }
    @Test fun xlsReadOnlyUsesActualNativeEngine() = runBlocking { exercise(Format.XLS) }
    @Test fun xlsxReadOnlyUsesActualNativeEngine() = runBlocking { exercise(Format.XLSX) }
    @Test fun pptReadOnlyUsesActualNativeEngine() = runBlocking { exercise(Format.PPT) }
    @Test fun pptxReadOnlyUsesActualNativeEngine() = runBlocking { exercise(Format.PPTX) }
    @Test fun docxModeOnlyTransitionPreservesScrolledViewportAndDoesNotWrite() = runBlocking {
        exercise(Format.DOCX, toggleOnly = true)
    }

    @Test fun docxNativePageBreakSavesAndReopens() = runBlocking { saveMutation(Format.DOCX) }
    @Test fun xlsxNativeColumnInsertSavesAndReopens() = runBlocking { saveMutation(Format.XLSX) }
    @Test fun pptxNativeSlideInsertSavesAndReopens() = runBlocking { saveMutation(Format.PPTX) }

    /** Actual UI commands, native Save acknowledgement, provider save, parser and engine reopen. */
    private suspend fun saveMutation(format: Format) {
        val label = format.extension + "-save-roundtrip"
        val repo = DocumentRepository(context)
        val token = "PRESERVE_NATIVE_" + format.extension.uppercase()
        var seed = repo.create(format)
        val key = if (format == Format.XLSX) seed.sections.first().id + "#A1"
            else seed.sections.first().fields.first().id
        seed = seed.copy(edits = seed.edits.copy(values = mapOf(key to token)))
        val original = File(context.cacheDir, "tests/native-save-${UUID.randomUUID()}.${format.extension}")
        original.parentFile!!.mkdirs(); original.writeBytes(byteArrayOf())
        val uri = FileProvider.getUriForFile(context, context.packageName + ".testfiles", original)
        val session = repo.save(seed, uri)
        val originalBefore = DocumentRepository.digest(original)
        val oldBaseHash = DocumentRepository.digest(session.base)
        var working = repo.prepareOffice(session)
        val record = JSONObject().put("format", format.extension).put("scenario", "native-edited-save-reopen")
            .put("originalBefore", originalBefore).put("token", token)
        var completed = false
        fun launch(file: File, id: String, name: String) {
            context.startActivity(Intent().setClassName(context.packageName,
                "org.libreoffice.androidlib.StudioOfficeActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("sessionId", id).putExtra("workingPath", file.absolutePath).putExtra("displayName", name))
        }
        suspend fun awaitClosed(file: File, sessionToClose: Session): Boolean {
            await("Native Save must confirm output before its durable close request", 45_000) {
                File(file.path + ".close-request").isFile
            }
            await("Office process must finish", 15_000) {
                device.executeShellCommand("pidof ${context.packageName}:office").trim().isEmpty()
            }
            val saveOriginal=repo.completeOfficeClose(sessionToClose)
            assertTrue("Host confirms the released native writer and matching output hash",File(file.path+".closed").isFile)
            return saveOriginal
        }
        fun verifyMutation(file: File) {
            java.util.zip.ZipFile(file).use { zip ->
                when (format) {
                    Format.DOCX -> {
                        val doc = kr.documentstudio.core.Xml.parse(zip.getInputStream(zip.getEntry("word/document.xml")).use { it.readBytes() })
                        val breaks = kr.documentstudio.core.Xml.elements(doc, "br").count {
                            it.getAttributeNS(kr.documentstudio.core.PackageDocument.W, "type") == "page"
                        }
                        val before = kr.documentstudio.core.Xml.elements(doc, "pageBreakBefore").count {
                            it.getAttributeNS(kr.documentstudio.core.PackageDocument.W, "val") !in listOf("0","false","off")
                        }
                        assertTrue("Saved DOCX must contain the native page break", breaks + before >= 1)
                    }
                    Format.XLSX -> {
                        val cells = kr.documentstudio.core.PackageDocument.open(file).sections.first().cells
                        assertEquals("Native column insertion must shift A1 to B1", token, cells["B1"])
                        assertNotEquals(token, cells["A1"])
                    }
                    Format.PPTX -> {
                        assertEquals("Native slide insertion must create a second slide",2,kr.documentstudio.core.PackageDocument.open(file).sections.size)
                        val slide=kr.documentstudio.core.Xml.parse(zip.getInputStream(zip.getEntry("ppt/slides/slide1.xml")).use {it.readBytes()})
                        val runs=kr.documentstudio.core.Xml.elements(slide,"r")
                        val selected=runs.firstOrNull {r->kr.documentstudio.core.Xml.elements(r,"t").any {it.textContent.contains(token)}} ?: error("Edited slide text is missing")
                        val props=kr.documentstudio.core.Xml.elements(selected,"rPr").single()
                        assertEquals("1800",props.getAttribute("sz"));assertEquals("1",props.getAttribute("b"))
                        assertTrue("The native font is stored on the original slide",kr.documentstudio.core.Xml.elements(props,"latin").any {it.getAttribute("typeface")=="NanumGothic"}||kr.documentstudio.core.Xml.elements(props,"ea").any {it.getAttribute("typeface")=="NanumGothic"})
                    }
                    else -> error("Unexpected roundtrip format")
                }
            }
            val parsed = kr.documentstudio.core.PackageDocument.open(file)
            val texts = parsed.sections.flatMap { it.fields.map { field -> field.text } + it.cells.values }.joinToString("")
            assertTrue("Unmodified original text must survive native save", texts.contains(token))
            assertEquals(format, parsed.format)
        }
        try {
            session.base.copyTo(File(evidence, "$label-input.${format.extension}"), overwrite = true)
            launch(working, session.id, session.name)
            visible("편집하기", 150_000); tap("편집하기"); visible("보기로 전환", 20_000)
            eval("(function(){window.__modeCommands=[];window.__modeResults=[];const send=app.socket.sendMessage.bind(app.socket);app.socket.sendMessage=function(message,...args){if(typeof message==='string'&&message.startsWith('uno '))window.__modeCommands.push(message);return send(message,...args);};app.map.on('commandresult',e=>window.__modeResults.push({command:e.commandName,success:e.success}));})()")
            when (format) {
                Format.DOCX -> { tap("문단"); tap("쪽 나누기") }
                Format.XLSX -> { tap("셀"); tap("열 삽입") }
                Format.PPTX -> {
                    eval("(function(){window.__slideSearch=null;app.map.on('search',e=>window.__slideSearch={phrase:e.originalPhrase,count:e.count});app.searchService.search("+JSONObject.quote(token)+",false);})()")
                    await("Native slide text is selected before formatting",30_000){eval("window.__slideSearch?.count===1")=="true"}
                    tap("글자");tap("글꼴 선택");tap("NanumGothic");tap("18")
                    if(eval("app.map.stateChangeHandler.getItemValue('.uno:Bold')")!="true")tap("굵게")
                    tap("설정 닫기");tap("슬라이드");tap("새 슬라이드")
                    await("The inserted slide is available",10000){eval("app.map.getNumberOfParts()")=="2"}
                    tap("슬라이드 1");await("Slide list navigates to the original slide",10000){eval("app.map.getCurrentPartNumber()")=="0"}
                    tap("슬라이드 2");await("Slide list navigates to the new slide",10000){eval("app.map.getCurrentPartNumber()")=="1"}
                }
                else -> error("Unexpected roundtrip format")
            }
            val expectedCommand=when(format){Format.DOCX->"InsertPagebreak";Format.XLSX->"InsertColumnsBefore";Format.PPTX->"InsertPage";else->error("Unexpected native format")}
            await("The real visible button must send its native command: $expectedCommand",5000){
                eval("(window.__modeCommands||[]).some(message=>message.startsWith("+JSONObject.quote("uno .uno:"+expectedCommand)+"))")=="true"
            }
            visible("저장 후 닫기",20_000);commandEvidence(label)
            tap("저장 후 닫기",20_000)
            assertTrue("Explicit save intent must survive native process exit",awaitClosed(working,session))
            assertTrue("Only successful native save may approve output", File(working.path + ".edited").isFile)
            assertEquals("Engine save must not write the provider original", originalBefore, DocumentRepository.digest(original))
            assertEquals("Engine save must not replace the old baseline", oldBaseHash, DocumentRepository.digest(session.base))
            verifyMutation(working)
            working.copyTo(File(evidence, "$label-output.${format.extension}"), overwrite = true)
            val accepted = repo.acceptOffice(session)
            assertEquals(session.revision + 1, accepted.revision)
            assertTrue(accepted.dirty)
            val saved = repo.save(accepted, uri)
            assertFalse(saved.dirty)
            assertNotEquals(originalBefore, DocumentRepository.digest(original))
            verifyMutation(original)
            val reopened = repo.open(uri)
            verifyMutation(reopened.base)
            record.put("originalAfter", DocumentRepository.digest(original))
                .put("savedFormat", reopened.format.extension).put("parserReopen", true)
            working = repo.prepareOffice(reopened)
            val reopenedHash = DocumentRepository.digest(working)
            launch(working, reopened.id, reopened.name)
            visible("편집하기", 150_000); tap("돌아가기"); assertFalse(awaitClosed(working,reopened))
            assertFalse("Reopening edited content in VIEW cannot save again", File(working.path + ".edited").exists())
            assertEquals(reopenedHash, DocumentRepository.digest(working))
            assertEquals(reopened.revision, repo.acceptOffice(reopened).revision)
            record.put("engineReopen", true).put("result", "passed")
            completed = true
        } catch (failure: Throwable) {
            record.put("result", "failed").put("error", failure.toString()); throw failure
        } finally {
            if (!completed) runCatching {
                commandEvidence(label)
                device.dumpWindowHierarchy(File(evidence, "$label-failure.xml"))
                device.takeScreenshot(File(evidence, "$label-failure.png"))
                if (working.isFile) working.copyTo(File(evidence, "$label-failure.${format.extension}"), overwrite = true)
                for(name in listOf("office-lifecycle.json","office-view-state-error.json")) {
                    val metadata=File(working.parentFile,name)
                    if(metadata.isFile) metadata.copyTo(File(evidence,"$label-$name"),overwrite=true)
                }
                device.executeShellCommand("pidof ${context.packageName}:office").trim()
                    .split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
                    .filter { it != Process.myPid() }.forEach { Process.killProcess(it) }
            }
            File(evidence, "$label.json").writeText(record.put("completed", completed).toString(2))
        }
    }

    private suspend fun create(repo: DocumentRepository, format: Format, longDocument: Boolean): Pair<Session, File> {
        val original = File(context.cacheDir, "tests/native-office-${UUID.randomUUID()}.${format.extension}")
        original.parentFile!!.mkdirs()
        val uri = FileProvider.getUriForFile(context, context.packageName + ".testfiles", original)
        val session = if (format == Format.DOC) {
            instrumentation.context.assets.open("legacy.doc").use { input ->
                original.outputStream().use { input.copyTo(it) }
            }
            repo.open(uri)
        } else {
            var seed = repo.create(format)
            if (longDocument) {
                val field = seed.sections.first().fields.first()
                val text = (1..240).joinToString("\n") { "원본 보기 위치 확인 $it" }
                seed = seed.copy(edits = seed.edits.copy(values = mapOf(field.id to text)))
            }
            original.writeBytes(byteArrayOf())
            repo.save(seed, uri)
        }
        return session to original
    }

    private suspend fun exercise(format: Format, toggleOnly: Boolean = false) {
        val label = format.extension + if (toggleOnly) "-mode-only" else "-view"
        val repo = DocumentRepository(context)
        val (session, original) = create(repo, format, toggleOnly)
        val working = repo.prepareOffice(session)
        val beforeOriginal = DocumentRepository.digest(original)
        val beforeBase = DocumentRepository.digest(session.base)
        val beforeWorking = DocumentRepository.digest(working)
        val edited = File(working.path + ".edited")
        val closed = File(working.path + ".closed")
        val viewportFile = File(working.parentFile, "office-view-state.json")
        var completed = false
        val record = JSONObject().put("format", format.extension).put("sessionId", session.id)
            .put("scenario", if (toggleOnly) "native-mode-only" else "native-readonly-input-attempt")
            .put("originalBefore", beforeOriginal).put("baseBefore", beforeBase).put("workingBefore", beforeWorking)
        try {
            // Deliberately omit openIntent: production must default to VIEW.
            context.startActivity(Intent().setClassName(context.packageName,
                "org.libreoffice.androidlib.StudioOfficeActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("sessionId", session.id).putExtra("workingPath", working.absolutePath)
                .putExtra("displayName", session.name))
            visible("편집하기", 150_000)
            visible("편집하기")
            await("Actual native layout must publish a valid viewport", 30_000) { viewport(viewportFile) != null }
            assertFalse("VIEW cannot approve a working copy", edited.exists())
            assertEquals(beforeWorking, DocumentRepository.digest(working))

            if (toggleOnly) {
                val initial = viewport(viewportFile)!!
                val bounds = documentBounds()
                assertTrue(device.swipe(bounds.centerX(), bounds.top + bounds.height() * 3 / 4,
                    bounds.centerX(), bounds.top + bounds.height() / 3, 35))
                await("Long document must actually scroll before testing preservation", 15_000) {
                    viewport(viewportFile)?.let { abs(it.getDouble("y") - initial.getDouble("y")) > 40.0 } == true
                }
                val scrolled = stableViewport(viewportFile)
                record.put("scrollSettledBeforeMode",true)
                device.dumpWindowHierarchy(File(evidence,"$label-before-edit.xml"))
                device.takeScreenshot(File(evidence,"$label-before-edit.png"))
                record.put("viewportBeforeToggle", scrolled)
                tap("편집하기")
                visible("보기로 전환", 20_000)
                visible("보기로 전환")
                val afterEdit=stableViewport(viewportFile)
                record.put("viewportAfterEnteringEdit",afterEdit)
                viewportFile.copyTo(File(evidence,"$label-edit-viewport.json"),overwrite=true)
                assertEquals("Entering edit retains zoom",scrolled.getDouble("zoom"),afterEdit.getDouble("zoom"),0.001)
                assertEquals("Entering edit retains horizontal position",scrolled.getDouble("x"),afterEdit.getDouble("x"),40.0)
                assertEquals("Entering edit retains vertical position",scrolled.getDouble("y"),afterEdit.getDouble("y"),40.0)
                tap("보기로 전환")
                visible("편집하기", 20_000)
                // Compare actual settled native positions after both toolbar/layout changes.
                val after = stableViewport(viewportFile)
                record.put("viewportAfterToggle", after)
                assertEquals(scrolled.getInt("part"), after.getInt("part"))
                assertEquals(scrolled.getDouble("zoom"), after.getDouble("zoom"), 0.001)
                assertEquals(scrolled.getDouble("x"), after.getDouble("x"), 40.0)
                assertEquals(scrolled.getDouble("y"), after.getDouble("y"), 40.0)
                assertFalse("Mode permission alone must not approve document output", edited.exists())
            } else {
                val bounds = documentBounds()
                device.click(bounds.centerX(), bounds.top + bounds.height() / 2)
                device.pressKeyCode(KeyEvent.KEYCODE_A)
                device.pressKeyCode(KeyEvent.KEYCODE_B)
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("read-only smoke", "읽기 상태 붙여넣기 차단"))
                device.pressKeyCode(KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON)
                device.swipe(bounds.centerX(), bounds.top + bounds.height() * 2 / 3,
                    bounds.centerX() + bounds.width() / 8, bounds.top + bounds.height() / 2, 15)
                SystemClock.sleep(1_000)
                visible("편집하기")
                assertFalse("Attempted input in VIEW must not create approved output", edited.exists())
                if (format == Format.DOCX) {
                    // Actual common search UI, physical Ctrl+V: the readonly
                    // document guard must preserve clipboard input in UI fields.
                    tap("찾기")
                    val search=device.wait(Until.findObject(By.pkg(context.packageName).desc("찾을 내용")),10000)
                        ?: device.wait(Until.findObject(By.pkg(context.packageName).clazz("android.widget.EditText")),5000)
                        ?: throw AssertionError("Native view search input missing")
                    search.click()
                    await("Search field must receive focus before keyboard paste",10000) {
                        eval("document.activeElement===document.querySelector('#studio-office-actions input[type=search]')") == "true"
                    }
                    device.pressKeyCode(KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON)
                    await("VIEW search must still accept clipboard text",10000) {
                        eval("document.querySelector('#studio-office-actions input[type=search]')?.value || ''") == "읽기 상태 붙여넣기 차단"
                    }
                    tap("설정 닫기")
                    device.pressKeyCode(KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON)
                    visible("편집하기")
                    record.put("viewSearchClipboardPaste",true)
                    record.put("documentPasteBlockedAfterSearchClosed",true)
                    assertFalse("Search input cannot approve document edits",edited.exists())
                }
            }

            device.dumpWindowHierarchy(File(evidence, "$label.xml"))
            device.takeScreenshot(File(evidence, "$label.png"))
            tap("돌아가기")
            await("Native close must persist its request before BYE", 30_000) { File(working.path+".close-request").isFile }
            await("The isolated Office process must finish before another document", 15_000) {
                device.executeShellCommand("pidof ${context.packageName}:office").trim().isEmpty()
            }
            assertFalse("VIEW close cannot request provider replacement", repo.completeOfficeClose(session))
            assertTrue("Host confirms closure only after process exit and lock release", closed.isFile)
            assertFalse("Read/mode-only close must not produce .edited", edited.exists())
            assertEquals("Native read-only close cannot reserialize working bytes", beforeWorking, DocumentRepository.digest(working))
            assertEquals("Provider original must remain unchanged", beforeOriginal, DocumentRepository.digest(original))
            assertEquals("Immutable baseline must remain unchanged", beforeBase, DocumentRepository.digest(session.base))
            val accepted = repo.acceptOffice(session)
            assertEquals(session.base, accepted.base)
            assertEquals(session.revision, accepted.revision)
            val reopened = repo.open(android.net.Uri.parse(session.uri!!))
            assertEquals(format, reopened.format)
            assertEquals(beforeOriginal, DocumentRepository.digest(original))
            record.put("originalAfter", DocumentRepository.digest(original))
                .put("baseAfter", DocumentRepository.digest(session.base))
                .put("workingAfter", DocumentRepository.digest(working))
                .put("approvedMarker", edited.exists()).put("cleanCloseObserved", true)
                .put("providerReopenFormat", reopened.format.extension).put("result", "passed")
            completed = true
        } catch (failure: Throwable) {
            record.put("result", "failed").put("error", failure.toString())
            throw failure
        } finally {
            runCatching {
                if (!completed) {
                    device.dumpWindowHierarchy(File(evidence, "$label-failure.xml"))
                    device.takeScreenshot(File(evidence, "$label-failure.png"))
                }
                if (viewportFile.exists()) viewportFile.copyTo(File(evidence, "$label-viewport.json"), overwrite = true)
                val viewError=File(working.parentFile,"office-view-state-error.json")
                if(viewError.exists()) viewError.copyTo(File(evidence,"$label-viewport-error.json"),overwrite=true)
                val lifecycle=File(working.parentFile,"office-lifecycle.json")
                if(lifecycle.exists()) lifecycle.copyTo(File(evidence,"$label-lifecycle.json"),overwrite=true)
                record.put("completed", completed)
                File(evidence, "$label.json").writeText(record.toString(2))
            }
            if (!completed) {
                // Diagnose a toolbar hit-test failure independently from Android Back.
                // The original test still fails; cleanup cannot turn it into a pass.
                runCatching {
                    device.pressBack()
                    val deadline=SystemClock.uptimeMillis()+5_000
                    while(!closed.isFile && SystemClock.uptimeMillis()<deadline) SystemClock.sleep(100)
                    record.put("failureCleanupBackClosed",closed.isFile)
                    File(evidence, "$label.json").writeText(record.toString(2))
                }
                // Keep source and working files for inspection; only end the isolated
                // engine process so a failed case cannot poison the following case.
                runCatching {
                    device.executeShellCommand("pidof ${context.packageName}:office").trim()
                        .split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
                        .filter { it != Process.myPid() }.forEach { Process.killProcess(it) }
                }
            }
        }
    }

    private fun eval(script: String): String {
        val uri=Uri.parse("content://${context.packageName}.officeprobe")
        val raw=context.contentResolver.acquireUnstableContentProviderClient(uri).use {client->checkNotNull(client).call("eval",script,null)!!.getString("result")!!}
        return JSONTokener(raw).nextValue().toString()
    }
    private fun tap(label: String,timeout: Long=20000) {
        val quoted=JSONObject.quote(label)
        eval("(function(){const e=Array.from(document.querySelectorAll('button')).find(e=>!e.disabled && e.getClientRects().length && (e.getAttribute('aria-label')==="+quoted+"||e.textContent==="+quoted+"));if(!e)throw new Error('Missing visible native control');e.scrollIntoView({block:'center',inline:'nearest',behavior:'instant'});})()")
        NativeUiActions.tap(device,context.packageName,label,timeout)
    }
    private fun commandEvidence(label: String) {
        runCatching {File(evidence,"$label-command-state.json").writeText(eval("JSON.stringify({editing:app.map.isEditMode(),changed:window.studioOffice.changed,blocked:app.map.uiManager.isUIBlocked(),dialog:app.map.dialog.hasOpenedDialog(),jsDialog:app.map.jsdialog?.hasDialogOpened(),commands:window.__modeCommands||[],results:window.__modeResults||[],buttons:Array.from(document.querySelectorAll('#studio-office-actions button')).map(b=>({label:b.getAttribute('aria-label'),disabled:b.disabled,rect:b.getBoundingClientRect().toJSON()}))})"))}
    }

    private fun visible(text: String,timeout: Long=15_000): UiObject2 {
        val deadline=SystemClock.uptimeMillis()+timeout
        while(SystemClock.uptimeMillis()<deadline) {
            NativeUiActions.refresh()
            val control=device.findObject(By.pkg(context.packageName).text(text).enabled(true))
                ?: device.findObject(By.pkg(context.packageName).desc(text).enabled(true))
            if(control!=null)return control
            SystemClock.sleep(100)
        }
        throw AssertionError("Native Office UI missing: $text")
    }

    private fun documentBounds(): Rect {
        val web = device.wait(Until.findObject(By.pkg(context.packageName).clazz("android.webkit.WebView")), 10_000)
            ?: throw AssertionError("Actual Office WebView missing")
        val bounds = web.visibleBounds
        assertTrue("Document WebView must occupy usable screen space", bounds.width() > 200 && bounds.height() > 300)
        return bounds
    }
    private fun viewport(file: File): JSONObject? = runCatching {
        if (!file.isFile) return@runCatching null
        JSONObject(file.readText()).takeIf {
            listOf("x", "y", "zoom").all { key -> it.has(key) && it.getDouble(key).isFinite() }
                && it.has("part") && it.getInt("part") >= 0
        }
    }.getOrNull()
    private fun stableViewport(file: File): JSONObject {
        val deadline=SystemClock.uptimeMillis()+20_000
        var stableSince=SystemClock.uptimeMillis()
        var previous: JSONObject?=null
        while(SystemClock.uptimeMillis()<deadline) {
            val current=viewport(file)
            if(current!=null) {
                val last=previous
                val same=last!=null && current.getInt("part")==last.getInt("part")
                    && listOf("x","y","zoom").all {abs(current.getDouble(it)-last.getDouble(it))<0.001}
                if(!same) {previous=current;stableSince=SystemClock.uptimeMillis()}
                // Production metadata is polled each second; this spans another
                // real sample and avoids treating an in-flight fling as settled.
                else if(SystemClock.uptimeMillis()-stableSince>=1_500) return current
            }
            SystemClock.sleep(100)
        }
        throw AssertionError("Native viewport must settle before comparing or tapping mode controls")
    }
    private fun await(message: String, timeout: Long, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        assertTrue(message, condition())
    }
}
