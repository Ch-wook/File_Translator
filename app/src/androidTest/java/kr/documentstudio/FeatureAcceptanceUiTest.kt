package kr.documentstudio

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kr.documentstudio.core.*
import kr.documentstudio.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class FeatureAcceptanceUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val vm get()=ViewModelProvider(compose.activity)[EditorViewModel::class.java]
    private fun ready() {compose.waitUntil(20000) {!vm.state.value.busy};compose.waitForIdle()}
    private fun create(format: Format) {ready();compose.runOnUiThread {vm.create(format)};ready()}
    private fun file(ext: String)=File(compose.activity.cacheDir,"tests/${UUID.randomUUID()}.$ext").apply {parentFile!!.mkdirs();writeBytes(byteArrayOf())}
    private fun uri(f: File)=FileProvider.getUriForFile(compose.activity,"kr.documentstudio.testfiles",f)

    @Test fun formulaCompletionSearchAndSavedCalculationAgree() {
        create(Format.XLSX)
        val field=compose.onNodeWithText("값 또는 =수식")
        field.performTextReplacement("=su")
        compose.onNodeWithText("SUM(",useUnmergedTree=true).performClick()
        field.assertTextContains("=SUM(");field.performTextReplacement("=SUM(10,20)")
        compose.onNodeWithText("함수",useUnmergedTree=true).performClick()
        compose.onNodeWithText("함수 이름 또는 설명").performTextReplacement("평균")
        compose.onNodeWithText("AVERAGE(값, …)").assertIsDisplayed()
        compose.onNodeWithText("닫기").performClick()
        field.assertTextContains("=SUM(10,20)")
        val output=file("xlsx");compose.runOnUiThread {vm.save(uri(output))};ready()
        assertEquals("30",Formula(PackageDocument.open(output).sections.first().cells).display("A1"))
    }
    @Test fun hangulTableCanBeCreatedUndoneEditedAndReopened() {
        create(Format.HWPX)
        compose.onNodeWithText("삽입·문서 도구").performClick();compose.onNodeWithText("표 삽입").performClick()
        compose.onNodeWithText("행 수 (1~50)").performTextReplacement("2")
        compose.onNodeWithText("열 수 (1~20)").performTextReplacement("2")
        compose.onNodeWithText("적용").performClick();ready()
        assertEquals(4,vm.state.value.session!!.sections.first().fields.count {it.label.startsWith("표 ")})
        compose.onNodeWithContentDescription("실행 취소").performClick()
        assertEquals(0,vm.state.value.session!!.sections.first().fields.count {it.label.startsWith("표 ")})
        compose.onNodeWithContentDescription("다시 실행").performClick()
        val cell=vm.state.value.session!!.sections.first().fields.first {it.label.contains("1행 1열")}
        compose.onNodeWithContentDescription(cell.label).performScrollTo().performTextReplacement("표 셀 편집")
        val output=file("hwpx");compose.runOnUiThread {vm.save(uri(output))};ready()
        assertEquals("표 셀 편집",PackageDocument.open(output).sections.first().fields.single {it.id==cell.id}.text)
        val recovered=runBlocking {DocumentRepository(compose.activity).restore(vm.state.value.active!!)}
        assertEquals("표 셀 편집",recovered.edits.values[cell.id])
    }
    @Test fun viewAndSendIntentsWaitForCurrentCommandInsteadOfBeingDiscarded() {
        create(Format.TXT)
        compose.runOnUiThread {vm.text("기존 작업 보존")}
        val original=vm.state.value.session!!
        val first=file("txt").apply {writeText("외부 보기")};val second=file("md").apply {writeText("# 공유 문서")}
        compose.runOnUiThread {
            vm.create(Format.PDF)
            assertTrue(vm.state.value.busy)
            val instrumentation=InstrumentationRegistry.getInstrumentation()
            val launchIntent=compose.activity.intent
            try {
                instrumentation.callActivityOnNewIntent(compose.activity,Intent(Intent.ACTION_VIEW).setDataAndType(uri(first),"text/plain"))
                instrumentation.callActivityOnNewIntent(compose.activity,Intent(Intent.ACTION_SEND).setType("text/markdown").putExtra(Intent.EXTRA_STREAM,uri(second)))
            } finally {
                // ActivityScenario identifies lifecycle callbacks by the launch
                // intent's action/data/type. Restore that test-only identity
                // after both real onNewIntent callbacks consumed their inputs.
                compose.activity.intent=launchIntent
            }
        }
        compose.waitUntil(20000) {!vm.state.value.busy && vm.state.value.tabs.any {it.uri==uri(second).toString()}}
        assertTrue(vm.state.value.tabs.any {it.uri==uri(first).toString() && it.edits.text=="외부 보기"})
        assertEquals("# 공유 문서",vm.state.value.session!!.edits.text)
        assertEquals("기존 작업 보존",runBlocking {DocumentRepository(compose.activity).restore(original.id)}.edits.text)
        compose.activityRule.scenario.recreate();assertEquals(uri(second).toString(),vm.state.value.session!!.uri)
    }
    @Test fun existingPdfImageDialogUpdatesContentAndSupportsUndo() {
        create(Format.PDF)
        val image=file("png");val bitmap=Bitmap.createBitmap(20,20,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.RED)}
        try {image.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}} finally {bitmap.recycle()}
        compose.runOnUiThread {vm.change(DocumentChange("pdf.image","0",mapOf("uri" to uri(image).toString())))};ready()
        val before=PdfImages.list(vm.state.value.session!!.base,0).single()
        compose.onNodeWithTag("ribbon-편집").performScrollTo().performClick()
        compose.onNodeWithText("기존 이미지 편집").performScrollTo().performClick()
        compose.waitUntil(15000) {compose.onAllNodesWithText("이미지 1 ·",substring=true).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("이미지 1 ·",substring=true).performClick()
        compose.onNodeWithText("왼쪽 위치 %").performTextReplacement("50")
        compose.onNodeWithText("위치·크기 적용").performClick();ready()
        assertEquals(50f,PdfImages.list(vm.state.value.session!!.base,0).single().x,0.001f)
        compose.onNodeWithContentDescription("실행 취소").performClick()
        assertEquals(before,PdfImages.list(vm.state.value.session!!.base,0).single())
        compose.onNodeWithContentDescription("다시 실행").performClick()
        val output=file("pdf");compose.runOnUiThread {vm.save(uri(output))};ready()
        assertEquals(50f,PdfImages.list(output,0).single().x,0.001f)
    }
    @Test fun slideImagePreviewReleasesOldImagesAcrossStructureUndoAndRecreation() {
        create(Format.PPTX)
        val image=file("png");val bitmap=Bitmap.createBitmap(20,20,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.BLUE)}
        try {image.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}} finally {bitmap.recycle()}
        val section=vm.state.value.session!!.sections.first().id
        compose.runOnUiThread {vm.change(DocumentChange("slide.image",section,mapOf("uri" to uri(image).toString())))};ready()
        compose.onNodeWithTag("ribbon-보기").performScrollTo().performClick();compose.onNodeWithText("개체 미리보기").performScrollTo().performClick()
        fun view(root: android.view.View): kr.documentstudio.ui.SlideView? {
            if(root is kr.documentstudio.ui.SlideView && root.isShown && root.width>0 && root.height>0) return root
            if(root is android.view.ViewGroup) for(i in 0 until root.childCount) view(root.getChildAt(i))?.let {return it}
            return null
        }
        fun currentImage()=view(compose.activity.window.decorView)?.scene?.shapes?.firstNotNullOfOrNull {it.image}
        compose.waitUntil(15000) {currentImage()!=null}
        val first=currentImage()!!
        repeat(3) {i->
            compose.runOnUiThread {vm.change(DocumentChange("slide.transform",section,mapOf("shape" to "1","x" to (2+i).toString(),"y" to "2","width" to "4","height" to "4","rotation" to "0")))};ready()
            try {compose.waitUntil(15000) {currentImage()!=null && !currentImage()!!.isRecycled}} catch(e: ComposeTimeoutException) {
                val s=vm.state.value.session!!;val probe=SlideEngine.load(s,s.sections.first())
                try {throw AssertionError("message=${vm.state.value.message}; preview=${view(compose.activity.window.decorView)}; native images=${probe.shapes.count {it.image!=null}}; omitted=${probe.omitted}",e)} finally {probe.recycle()}
            }
            compose.onNodeWithContentDescription("실행 취소").performClick()
            compose.waitUntil(15000) {currentImage()!=null && !currentImage()!!.isRecycled}
        }
        assertTrue(first.isRecycled)
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15000) {currentImage()!=null && !currentImage()!!.isRecycled}
        compose.runOnUiThread {
            val preview=view(compose.activity.window.decorView)!!
            val output=Bitmap.createBitmap(preview.width.coerceAtLeast(1),preview.height.coerceAtLeast(1),Bitmap.Config.ARGB_8888)
            try {preview.draw(android.graphics.Canvas(output))} finally {output.recycle()}
        }
    }
}
