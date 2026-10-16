package kr.documentstudio

import android.content.Context
import android.graphics.Color
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.runBlocking
import kr.documentstudio.core.*
import kr.documentstudio.data.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DocumentIntegrationTest {
    private lateinit var context: Context
    private lateinit var repo: DocumentRepository
    @Before fun setup() {context=ApplicationProvider.getApplicationContext();PDFBoxResourceLoader.init(context);repo=DocumentRepository(context)}
    private fun file(name: String): File = File(context.cacheDir,"tests/${UUID.randomUUID()}-$name").apply {parentFile!!.mkdirs();writeBytes(byteArrayOf())}
    private fun uri(f: File)=FileProvider.getUriForFile(context,"kr.documentstudio.testfiles",f)
    private fun stroke(anchor: String)=Stroke(anchor=anchor,tool=InkTool.PEN,color=Color.BLUE,width=0.006f,points=listOf(InkPoint(0.2f,0.25f,0.5f),InkPoint(0.5f,0.5f,1f)))

    @Test fun textOpenEditOriginalSaveReopenAndInkRecovery() = runBlocking {
        val original=file("test.txt").apply {writeText("원본\r\n둘째 줄")}
        val opened=repo.open(uri(original));assertEquals("원본\r\n둘째 줄",opened.edits.text)
        val edited=opened.copy(edits=opened.edits.copy(text="편집됨\r\n둘째 줄",strokes=listOf(stroke("text"))))
        val saved=repo.save(edited,uri(original));assertFalse(saved.dirty);assertEquals("편집됨\r\n둘째 줄",original.readText())
        val reopened=repo.open(uri(original));assertEquals(saved.edits.text,reopened.edits.text);assertEquals(saved.edits.strokes,reopened.edits.strokes)
        assertTrue(File(saved.base.parentFile,"provider-backup.bin").readText().startsWith("원본"))
    }
    @Test fun markdownRecoverySurvivesRepositoryReconstruction() = runBlocking {
        val opened=repo.create(Format.MD)
        val edited=opened.copy(edits=Edits(text="# 복구\n\n- [x] 작업",strokes=listOf(stroke("text"))))
        repo.checkpoint(edited)
        val reconstructed=DocumentRepository(context).restore(opened.id)
        assertEquals(edited.edits,reconstructed.edits);assertTrue(reconstructed.dirty)
    }
    @Test fun officeOpenEditOriginalFormatSaveReopenAndSidecarInk() = runBlocking {
        listOf(Format.DOCX,Format.PPTX,Format.XLSX).forEach { format ->
            val opened=repo.create(format);val sec=opened.sections.first()
            val changes=if(format==Format.XLSX) mapOf("${sec.id}#A1" to "5","${sec.id}#A2" to "7","${sec.id}#B1" to "=SUM(A1:A2)") else mapOf(sec.fields.first().id to "편집한 한글 & text")
            val edited=opened.copy(edits=opened.edits.copy(values=changes,strokes=listOf(stroke(sec.id))))
            val output=file("sample.${format.extension}")
            val saved=repo.save(edited,uri(output));assertFalse(saved.dirty)
            val reopened=repo.open(uri(output));assertEquals(format,reopened.format);assertEquals(saved.edits.strokes,reopened.edits.strokes)
            if(format==Format.XLSX) assertEquals("12",Formula(reopened.sections.first().cells).display("B1")) else assertEquals("편집한 한글 & text",reopened.sections.first().fields.first().text)
        }
    }
    @Test fun pdfStandardInkSaveReopenRenderAndNoDuplication() = runBlocking {
        val opened=repo.create(Format.PDF)
        val edited=opened.copy(edits=opened.edits.copy(strokes=listOf(stroke("pdf:0"))))
        val output=file("ink.pdf");val saved=repo.save(edited,uri(output))
        PDDocument.load(output).use {doc -> assertEquals(1,doc.getPage(0).annotations.size);assertEquals("Ink",doc.getPage(0).annotations[0].subtype);assertNotNull(doc.getPage(0).annotations[0].appearance)}
        val reopened=repo.open(uri(output));assertEquals(1,reopened.edits.strokes.size)
        assertEquals(edited.edits.strokes.first().points.first().x,reopened.edits.strokes.first().points.first().x,0.001f)
        repo.save(reopened,uri(output));PDDocument.load(output).use {assertEquals(1,it.getPage(0).annotations.size)}
        val bitmap=repo.render(saved,0);assertTrue(bitmap.width>0);bitmap.recycle()
    }
    @Test fun partialErasurePersistsAsSeparatePdfInkWithoutRejoining() = runBlocking {
        val opened=repo.create(Format.PDF)
        val original=stroke("pdf:0").copy(tool=InkTool.BRUSH,opacity=0.65f,
            points=listOf(InkPoint(0.1f,0.5f,0.5f,0.2f),InkPoint(0.9f,0.5f,1.5f,0.8f)))
        val pieces=InkErasure.erase(listOf(original),"pdf:0",listOf(InkPoint(0.5f,0.5f)),0.04f,1.414f,true)
        assertEquals(2,pieces.size)
        val edits=opened.edits.copy(strokes=pieces)
        assertEquals(edits,StateCodec.decode(StateCodec.encode(edits)))
        val output=file("partially-erased.pdf")
        val saved=repo.save(opened.copy(edits=edits),uri(output))
        assertEquals(pieces,repo.restore(saved.id).edits.strokes)
        PDDocument.load(output).use {assertEquals(2,it.getPage(0).annotations.count {a->a.subtype=="Ink"})}
        // Read the PDF as a new provider document so sidecar recovery cannot hide an export error.
        val copy=file("portable-erased.pdf");output.copyTo(copy,overwrite=true)
        val reopened=repo.open(uri(copy))
        assertEquals(2,reopened.edits.strokes.size)
        val sorted=reopened.edits.strokes.sortedBy {it.points.first().x}
        assertTrue(sorted[0].points.last().x<0.46f && sorted[1].points.first().x>0.54f)
        sorted.forEach {assertEquals(InkTool.BRUSH,it.tool);assertEquals(0.65f,it.opacity,0.001f)}
        assertEquals(pieces[1].points.first().pressure,sorted[1].points.first().pressure,0.001f)
        assertEquals(pieces[1].points.first().tilt,sorted[1].points.first().tilt,0.001f)
        // Render the saved portable PDF directly: repository bases intentionally
        // remove owned annotations because the editor draws its ink overlay.
        val bitmap=PDDocument.load(output).use {com.tom_roush.pdfbox.rendering.PDFRenderer(it).renderImage(0,1f)}
        try {
            val gap=bitmap.getPixel(bitmap.width/2,bitmap.height/2)
            assertTrue(Color.red(gap)>240 && Color.green(gap)>240 && Color.blue(gap)>240)
            val ink=bitmap.getPixel((bitmap.width*0.2f).toInt(),bitmap.height/2)
            assertTrue("Saved PDF must show blue ink, got $ink",Color.blue(ink)>Color.red(ink)+50)
        } finally {bitmap.recycle()}
        repo.save(reopened,uri(copy))
        PDDocument.load(copy).use {assertEquals(2,it.getPage(0).annotations.count {a->a.subtype=="Ink"})}
    }
    @Test fun externalChangeRefusesOverwriteAndKeepsRecovery() = runBlocking {
        val original=file("conflict.txt").apply {writeText("original")};val opened=repo.open(uri(original))
        val edited=opened.copy(edits=opened.edits.copy(text="edited"));original.writeText("external change")
        try {repo.save(edited,uri(original));fail("Expected conflict refusal")} catch(e: IllegalArgumentException) {assertTrue(e.message!!.contains("외부"))}
        assertEquals("external change",original.readText());assertEquals("edited",repo.restore(opened.id).edits.text)
    }
    @Test fun pdfExportIncludesKoreanAndInkAndBackupIncludesSidecar() = runBlocking {
        val opened=repo.create(Format.TXT);val edited=opened.copy(edits=Edits(text="한글 문서 내보내기\nEnglish",strokes=listOf(stroke("text"))))
        val output=file("export.pdf");repo.exportPdf(edited,uri(output));assertEquals(1,PdfEngine.inspect(output).pages)
        val zip=file("backup.zip");repo.exportBackup(edited,uri(zip))
        java.util.zip.ZipFile(zip).use {assertNotNull(it.getEntry("state.json"));assertNotNull(it.getEntry("base.bin"));val state=org.json.JSONObject(it.getInputStream(it.getEntry("state.json")).bufferedReader().readText());assertEquals(edited.edits,StateCodec.decode(state.getJSONObject("edits")))}
        val restored=repo.open(uri(zip));assertEquals(edited.edits,restored.edits);assertNull(restored.uri);assertNotEquals(edited.id,restored.id)
    }
    @Test fun archivalPdfRefusesOverwriteAndSavesOrdinaryCopy() = runBlocking {
        val source=file("archive.pdf");ExportEngine.blank(source)
        val temporary=file("metadata.pdf")
        PDDocument.load(source).use {doc ->
            doc.documentCatalog.metadata=com.tom_roush.pdfbox.pdmodel.common.PDMetadata(doc).apply {
                importXMPMetadata("<x:xmpmeta xmlns:x='adobe:ns:meta/'><pdfaid:part xmlns:pdfaid='http://www.aiim.org/pdfa/ns/id/'>1</pdfaid:part></x:xmpmeta>".toByteArray())
            };doc.save(temporary)
        };temporary.copyTo(source,overwrite=true)
        val opened=repo.open(uri(source));assertTrue(opened.pdf!!.archival)
        try {repo.save(opened,uri(source));fail("Expected PDF/A refusal")} catch(e: IllegalArgumentException){assertTrue(e.message!!.contains("PDF/A"))}
        val copy=file("ordinary.pdf");val saved=repo.save(opened,uri(copy));assertFalse(PdfEngine.inspect(copy).archival);assertFalse(saved.pdf!!.archival)
        repo.save(saved,uri(copy));assertFalse(PdfEngine.inspect(copy).archival)
    }
    @Test fun damagedPdfIsRejected() = runBlocking {
        val corrupt=file("bad.pdf").apply {writeText("%PDF-1.7\nnot a PDF")}
        try {repo.open(uri(corrupt));fail("Expected parse failure")} catch(_: Exception) {assertTrue(corrupt.exists())}
    }
    @Test fun legacyExcelAndHangulSaveReopenWithInk() = runBlocking {
        for(format in listOf(Format.XLS,Format.HWP)) {
            val opened=repo.create(format);val sec=opened.sections.first()
            val id=if(format==Format.XLS) sec.id+"#A1" else sec.fields.first().id
            val edited=opened.copy(edits=Edits(values=mapOf(id to "원본 형식 편집"),strokes=listOf(stroke(sec.id))))
            val output=file("edited.${format.extension}");repo.save(edited,uri(output))
            val reopened=repo.open(uri(output));assertEquals(format,reopened.format);assertEquals(edited.edits.strokes,reopened.edits.strokes)
            assertEquals("원본 형식 편집",if(format==Format.XLS) reopened.sections.first().cells["A1"] else reopened.sections.first().fields.first().text)
        }
    }
    @Test fun legacyPowerPointSaveReopenOnAndroid() = runBlocking {
        val input=file("legacy.ppt")
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets.open("legacy.ppt").use {input.outputStream().use {out -> it.copyTo(out)}}
        val opened=repo.open(uri(input));val field=opened.sections.first().fields.first()
        val edited=opened.copy(edits=Edits(values=mapOf(field.id to "한글 슬라이드 편집")))
        repo.save(edited,uri(input));assertEquals("한글 슬라이드 편집",repo.open(uri(input)).sections.first().fields.first().text)
    }
    @Test fun legacyWordSaveReopenOnAndroid() = runBlocking {
        val input=file("legacy.doc")
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets.open("legacy.doc").use {input.outputStream().use {out -> it.copyTo(out)}}
        val opened=repo.open(uri(input));val field=opened.sections.first().fields.first()
        val edited=opened.copy(edits=Edits(values=mapOf(field.id to "Word 한글 편집")))
        repo.save(edited,uri(input));assertEquals("Word 한글 편집",repo.open(uri(input)).sections.first().fields.first().text)
    }
    @Test fun structureAndStylesRecoverFromNewBaselineAndPortableBackup() = runBlocking {
        val opened=repo.create(Format.DOCX);val field=opened.sections.first().fields.first()
        val styled=opened.copy(edits=opened.edits.copy(styles=mapOf(field.id to mapOf("size" to "22","italic" to "true")),strokes=listOf(stroke(opened.sections.first().id))))
        val changed=repo.change(styled,DocumentChange("word.paragraph",arguments=mapOf("text" to "복구되는 추가 문단")))
        assertTrue(changed.dirty);assertEquals("복구되는 추가 문단",changed.sections.first().fields.last().text)
        val restored=DocumentRepository(context).restore(changed.id);assertEquals(changed.base,restored.base);assertEquals(changed.revision,restored.revision);assertEquals(changed.edits.strokes,restored.edits.strokes)
        val backup=file("structure.zip");repo.exportBackup(restored,uri(backup));val imported=repo.open(uri(backup));assertEquals("복구되는 추가 문단",imported.sections.first().fields.last().text)
        val output=file("structure.docx");repo.save(restored,uri(output));assertEquals("복구되는 추가 문단",repo.open(uri(output)).sections.first().fields.last().text)
    }
    @Test fun pdfPageChangesKeepInkAnchoredAndMetadataPortable() = runBlocking {
        val opened=repo.create(Format.PDF);val original=stroke("pdf:0").copy(tool=InkTool.BRUSH,opacity=0.7f)
        val added=repo.change(opened.copy(edits=Edits(strokes=listOf(original))),DocumentChange("pdf.add","0"))
        assertEquals(2,added.pdf!!.pages)
        val moved=repo.change(added,DocumentChange("pdf.move","0",mapOf("index" to "1")));assertEquals("pdf:1",moved.edits.strokes.first().anchor)
        val rotated=repo.change(moved,DocumentChange("pdf.rotate","1"));assertEquals(1-original.points[0].y,rotated.edits.strokes[0].points[0].x,0.0001f)
        val originalSize=PdfEngine.displaySize(moved.base,1);assertEquals(original.width*originalSize.first/originalSize.second,rotated.edits.strokes[0].width,0.0001f)
        val noted=repo.change(rotated,DocumentChange("pdf.note","1",mapOf("text" to "한글 메모 보존","x" to "10","y" to "10")))
        val output=file("pages.pdf");repo.save(noted,uri(output));val reopened=repo.open(uri(output))
        assertEquals(InkTool.BRUSH,reopened.edits.strokes[0].tool);assertEquals(0.7f,reopened.edits.strokes[0].opacity);assertEquals(original.points[0].pressure,reopened.edits.strokes[0].points[0].pressure)
        PDDocument.load(output).use {assertTrue(it.getPage(1).annotations.any {ann->ann.contents=="한글 메모 보존"})}
        val extracted=repo.change(reopened,DocumentChange("pdf.extract","0",mapOf("first" to "2","last" to "2")));assertEquals(1,extracted.pdf!!.pages);assertNull(extracted.uri);assertEquals("pdf:0",extracted.edits.strokes.first().anchor)
    }
    @Test fun pdfAcroFormIsActuallyEditableAndPersistent() = runBlocking {
        val source=file("form.pdf");val temporary=file("form-output.pdf");ExportEngine.blank(source)
        PDDocument.load(source).use {doc ->
            val form=com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm(doc)
            doc.documentCatalog.acroForm=form
            form.defaultResources=com.tom_roush.pdfbox.pdmodel.PDResources().apply {put(com.tom_roush.pdfbox.cos.COSName.getPDFName("Helv"),com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA)}
            form.defaultAppearance="/Helv 12 Tf 0 g"
            val field=com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField(form).apply {partialName="Name";defaultAppearance="/Helv 12 Tf 0 g"}
            val widget=field.widgets.first();widget.rectangle=com.tom_roush.pdfbox.pdmodel.common.PDRectangle(50f,700f,200f,30f);widget.page=doc.getPage(0);doc.getPage(0).annotations.add(widget);form.fields.add(field)
            field.value="Original";doc.save(temporary)
        };temporary.copyTo(source,true)
        val opened=repo.open(uri(source));assertEquals("Name",opened.pdf!!.forms.first().name)
        val edited=opened.copy(edits=Edits(values=mapOf("pdf:form#Name" to "Edited form")))
        repo.save(edited,uri(source));assertEquals("Edited form",repo.open(uri(source)).pdf!!.forms.first().value)
        PDDocument.load(source).use {assertNotNull(it.documentCatalog.acroForm.getField("Name").widgets[0].appearance)}
        val body=repo.change(repo.open(uri(source)),DocumentChange("pdf.text","0",mapOf("text" to "Searchable added body","x" to "10","y" to "20","size" to "14")))
        assertTrue(PdfEngine.text(body.base).contains("Searchable added body"));assertEquals(listOf(0),PdfEngine.search(body.base,"added body"))
    }
    @Test fun realHwpxTemplateEditsStylesSaveAndReopenOnAndroid() = runBlocking {
        val opened=repo.create(Format.HWPX);val field=opened.sections.first().fields.first()
        val edited=opened.copy(edits=Edits(values=mapOf(field.id to "한글 편집 & 원본 보존"),styles=mapOf(field.id to mapOf("size" to "16","bold" to "true")),strokes=listOf(stroke(opened.sections.first().id))))
        val added=repo.change(edited,DocumentChange("hwpx.paragraph",opened.sections.first().id,mapOf("text" to "추가 문단")))
        val output=file("edited.hwpx");repo.save(added,uri(output));val reopened=repo.open(uri(output))
        assertEquals(listOf("한글 편집 & 원본 보존","추가 문단"),reopened.sections.first().fields.map {it.text});assertEquals(edited.edits.strokes,reopened.edits.strokes)
    }
    @Test fun providerFailureActuallyRollsBackAndPreservesUnsavedWork() = runBlocking {
        val original=File(context.cacheDir,"tests/failing-provider.txt").apply {parentFile!!.mkdirs();writeText("original protected")}
        val target=android.net.Uri.parse("content://kr.documentstudio.faults/document?test=${UUID.randomUUID()}")
        FailingProvider.remainingFailures=0;val opened=repo.open(target);val edited=opened.copy(edits=Edits(text="unsaved edit"))
        FailingProvider.remainingFailures=1
        try {repo.save(edited,target);fail("Expected storage failure")} catch(e: IllegalStateException) {assertTrue(e.message!!.contains("원본을 복원"))}
        assertEquals("original protected",original.readText());assertEquals("unsaved edit",repo.restore(opened.id).edits.text)
    }
    @Test fun providerRollbackFailureRetainsIndependentOriginalBackup() = runBlocking {
        val original=File(context.cacheDir,"tests/failing-provider.txt").apply {parentFile!!.mkdirs();writeText("independent original")}
        val target=android.net.Uri.parse("content://kr.documentstudio.faults/document?test=${UUID.randomUUID()}")
        FailingProvider.remainingFailures=0;val opened=repo.open(target);val edited=opened.copy(edits=Edits(text="pending edit"))
        FailingProvider.remainingFailures=2
        try {repo.save(edited,target);fail("Expected storage and rollback failure")} catch(e: IllegalStateException) {assertTrue(e.message!!.contains("복원에 실패"))}
        assertEquals("independent original",File(opened.base.parentFile,"provider-backup.bin").readText());assertEquals("pending edit",repo.restore(opened.id).edits.text)
        FailingProvider.remainingFailures=0;original.writeText("independent original")
    }
    @Test fun presentationPreviewMoveAndPdfExportAreReal() = runBlocking {
        val opened=repo.create(Format.PPTX);val sec=opened.sections.first();val scene=SlideEngine.load(opened,sec)
        assertEquals(1,scene.shapes.size);assertTrue(scene.shapes[0].text.contains("새 프레젠테이션"));val shape=scene.shapes[0]
        val canvasBitmap=android.graphics.Bitmap.createBitmap(400,300,android.graphics.Bitmap.Config.ARGB_8888).apply {eraseColor(Color.MAGENTA)}
        try {val canvas=android.graphics.Canvas(canvasBitmap);canvas.translate(70f,50f);SlideEngine.draw(canvas,scene,200f,100f);assertEquals(Color.MAGENTA,canvasBitmap.getPixel(0,0));assertEquals(Color.WHITE,canvasBitmap.getPixel(75,55));assertEquals(Color.MAGENTA,canvasBitmap.getPixel(300,250))} finally {canvasBitmap.recycle();scene.recycle()}
        val moved=repo.change(opened,DocumentChange("slide.transform",sec.id,mapOf("shapeId" to shape.id,"x" to "3","y" to "2","width" to "10","height" to "2")))
        val after=SlideEngine.load(moved,moved.sections.first());assertEquals(3*72f/2.54f,after.shapes[0].x,0.01f);after.recycle()
        val output=file("slide.pdf");repo.exportPdf(moved,uri(output));assertEquals(1,PdfEngine.inspect(output).pages)
        val extracted=PdfEngine.text(output);assertTrue(extracted.filterNot {it.isWhitespace()}.contains("새프레젠테이션"));assertEquals(listOf(0),PdfEngine.search(output,"새 프레젠테이션"))
    }
    @Test fun markdownFormattingAndTextEncodingAreRecoverable() = runBlocking {
        val opened=repo.create(Format.MD);val edited=opened.copy(edits=Edits(text="# 제목\n\n**강조** 및 `코드`\n\n- [x] 체크"))
        val rendered=MarkdownRender.render(edited.edits.text);assertFalse(rendered.contains("**"));assertTrue(rendered.contains("강조"))
        val changed=repo.reencode(edited,"UTF-16LE",true);val restored=repo.restore(changed.id);assertEquals("UTF-16LE",restored.textContent!!.charset);assertTrue(restored.textContent.bom)
        val output=file("utf16.md");repo.save(restored,uri(output));assertEquals(edited.edits.text,TextContent.decode(output.readBytes()).text)
    }
    @Test fun printingWritesOnlySelectedPagesWithInk() = runBlocking {
        val opened=repo.create(Format.PDF);val added=repo.change(opened,DocumentChange("pdf.add","0"))
        val body=repo.change(added,DocumentChange("pdf.text","1",mapOf("text" to "Selected second page","x" to "10","y" to "20","size" to "14")))
        val source=file("print-source.pdf");repo.save(body.copy(edits=Edits(strokes=listOf(stroke("pdf:1")))),uri(source))
        val output=file("printed.pdf");val indices=output.outputStream().use {kr.documentstudio.ui.DocumentPrintAdapter.writeSelected(source,arrayOf(android.print.PageRange(1,1)),it)}
        assertEquals(listOf(1),indices);assertEquals(1,PdfEngine.inspect(output).pages);assertTrue(PdfEngine.text(output).contains("Selected second page"))
        PDDocument.load(output).use {assertEquals("Ink",it.getPage(0).annotations.first().subtype)}
        try {output.outputStream().use {kr.documentstudio.ui.DocumentPrintAdapter.writeSelected(source,arrayOf(android.print.PageRange.ALL_PAGES),it) {true}};fail("Expected cancelled print")} catch(_: kotlinx.coroutines.CancellationException) { }
    }
    @Test fun pdfMergeAndRealImageInsertionPreservePagesAndInk() = runBlocking {
        val opened=repo.create(Format.PDF);val other=repo.create(Format.PDF);val attachment=file("merge.pdf");repo.save(other,uri(attachment))
        val merged=repo.change(opened.copy(edits=Edits(strokes=listOf(stroke("pdf:0")))),DocumentChange("pdf.merge","0",mapOf("uri" to uri(attachment).toString())))
        assertEquals(2,merged.pdf!!.pages);assertEquals("pdf:0",merged.edits.strokes.first().anchor)
        val png=file("image.png");val bitmap=android.graphics.Bitmap.createBitmap(32,16,android.graphics.Bitmap.Config.ARGB_8888).apply {eraseColor(Color.RED)}
        try {png.outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}} finally {bitmap.recycle()}
        val imaged=repo.change(merged,DocumentChange("pdf.image","1",mapOf("uri" to uri(png).toString())))
        PDDocument.load(imaged.base).use {doc->val resources=doc.getPage(1).resources;assertTrue(resources.xObjectNames.any {resources.getXObject(it) is com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject})}
        val output=file("merged-image.pdf");repo.save(imaged,uri(output));val reopened=repo.open(uri(output));assertEquals(2,reopened.pdf!!.pages);assertEquals(1,reopened.edits.strokes.size)
        val rendered=repo.render(reopened,1);assertTrue(rendered.width>0);rendered.recycle()
    }
    @Test fun nativeOfficePicturesTableMergeAndSpreadsheetStructurePersistOnAndroid() = runBlocking {
        val png=file("office.png");val bitmap=android.graphics.Bitmap.createBitmap(40,20,android.graphics.Bitmap.Config.ARGB_8888).apply {eraseColor(Color.BLUE)}
        try {png.outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}} finally {bitmap.recycle()}
        for(format in listOf(Format.DOCX,Format.PPTX)) {
            val opened=repo.create(format);val picture=repo.change(opened,DocumentChange(if(format==Format.DOCX) "word.image" else "slide.image",opened.sections.first().id,mapOf("uri" to uri(png).toString())))
            val output=file("picture.${format.extension}");repo.save(picture,uri(output));val reopened=repo.open(uri(output));assertEquals(format,reopened.format)
            if(format==Format.PPTX) {val scene=SlideEngine.load(reopened,reopened.sections.first());try {assertTrue(scene.shapes.any {it.image!=null})} finally {scene.recycle()}}
            else {val table=repo.change(reopened,DocumentChange("word.table",arguments=mapOf("rows" to "2","columns" to "2")));val merged=repo.change(table,DocumentChange("word.tableMerge",arguments=mapOf("row" to "1","column" to "1","lastRow" to "2","lastColumn" to "2")));val split=repo.change(merged,DocumentChange("word.tableSplit",arguments=mapOf("row" to "1","column" to "1")));repo.save(split,uri(output));assertEquals(Format.DOCX,repo.open(uri(output)).format)}
        }
        val sheet=repo.create(Format.XLSX);val id=sheet.sections[0].id
        val pasted=repo.change(sheet,DocumentChange("sheet.paste",id,mapOf("range" to "A1","text" to "2\t=A1*2\n5\t=A2*2")))
        val filled=repo.change(pasted,DocumentChange("sheet.fill",id,mapOf("source" to "A1:A2","destination" to "A1:A5")))
        val moved=repo.change(filled,DocumentChange("sheet.insertRows",id,mapOf("index" to "2","count" to "2")))
        val recovered=DocumentRepository(context).restore(moved.id);assertEquals("=A4*2",recovered.sections[0].cells["B4"]);assertEquals("14.0",recovered.sections[0].cells["A7"])
        assertEquals("2024-01-01",CellFormatting.format("45292","yyyy-mm-dd"));assertEquals("12.5%",CellFormatting.format("0.125","0.0%"))
        val output=file("range.xlsx");repo.save(recovered,uri(output));assertEquals("14",repo.open(uri(output)).calculator(repo.open(uri(output)).sections[0]).display("A7"))
    }
    @Test fun existingPdfTextIsReplacedInContentAndFollowingGlyphPositionStaysFixed() = runBlocking {
        val source=file("native-body.pdf")
        PDDocument().use {doc->val page=com.tom_roush.pdfbox.pdmodel.PDPage();doc.addPage(page);com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc,page).use {stream->stream.beginText();stream.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA,12f);stream.newLineAtOffset(50f,700f);stream.setCharacterSpacing(0.8f);stream.setWordSpacing(2f);stream.showTextWithPositioning(arrayOf("Original",-80f," Tail"));stream.endText()};doc.save(source)}
        fun tailX(file: File): Float {var x=Float.NaN;PDDocument.load(file).use {doc->object: com.tom_roush.pdfbox.text.PDFTextStripper() {override fun writeString(text: String,positions: MutableList<com.tom_roush.pdfbox.text.TextPosition>) {positions.firstOrNull {it.unicode=="T"}?.let {x=it.xDirAdj};super.writeString(text,positions)}}.getText(doc)};return x}
        val before=tailX(source);val opened=repo.open(uri(source));val run=PdfBodyText.runs(opened.base,0).first {it.text=="Original"}
        val changed=repo.change(opened,DocumentChange("pdf.replaceText","0",mapOf("id" to run.id.toString(),"expected" to run.text,"text" to "New")))
        val output=file("changed-body.pdf");repo.save(changed,uri(output));assertFalse(PdfEngine.text(output).contains("Original"));assertTrue(PdfEngine.text(output).contains("New"));assertEquals(before,tailX(output),0.01f)
        PDDocument.load(output).use {assertTrue(it.getPage(0).annotations.isEmpty());assertFalse(it.getPage(0).resources.xObjectNames.iterator().hasNext())}
        val next=repo.open(uri(output));val newRun=PdfBodyText.runs(next.base,0).first {it.text=="New"}
        try {repo.change(next,DocumentChange("pdf.replaceText","0",mapOf("id" to newRun.id.toString(),"expected" to newRun.text,"text" to "한글")));fail("Expected original font encoding refusal")} catch(e: IllegalArgumentException) {assertTrue(e.message!!.contains("폰트"))}
        assertTrue(PdfEngine.text(output).contains("New"))
    }
    @Test fun pdfBookmarksAndSmallThumbnailsSurvivePageMoveAndDeletion() = runBlocking {
        val first=repo.create(Format.PDF);val added=repo.change(first,DocumentChange("pdf.add","0"))
        val marked=repo.change(added,DocumentChange("pdf.bookmarkAdd","1",mapOf("title" to "두 번째 쪽")))
        assertEquals(1,PdfBookmarks.list(marked.base).single().page)
        val moved=repo.change(marked,DocumentChange("pdf.move","1",mapOf("index" to "0")));assertEquals(0,PdfBookmarks.list(moved.base).single().page)
        val renamed=repo.change(moved,DocumentChange("pdf.bookmarkRename","0",mapOf("id" to "0","title" to "바뀐 이름")));assertEquals("바뀐 이름",PdfBookmarks.list(renamed.base).single().title)
        val deleted=repo.change(renamed,DocumentChange("pdf.delete","0"));assertEquals(0,PdfBookmarks.list(deleted.base).single().page)
        val thumbnail=PdfEngine.render(deleted.base,0,240,340);assertTrue(thumbnail.width<=240 && thumbnail.height<=340);thumbnail.recycle()
        val output=file("bookmarks.pdf");repo.save(deleted,uri(output));assertEquals("바뀐 이름",PdfBookmarks.list(output).single().title)
        val cleared=repo.change(repo.open(uri(output)),DocumentChange("pdf.bookmarkDelete","0",mapOf("id" to "0")));assertTrue(PdfBookmarks.list(cleared.base).isEmpty())
    }
}
