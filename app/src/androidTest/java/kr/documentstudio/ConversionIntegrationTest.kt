package kr.documentstudio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.runBlocking
import kr.documentstudio.core.Format
import kr.documentstudio.data.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ConversionIntegrationTest {
    private val context: Context=ApplicationProvider.getApplicationContext()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val device=UiDevice.getInstance(instrumentation)
    private val output get()=File(context.getExternalFilesDir(null),"native-conversion").apply {mkdirs()}
    private fun await(label: String,timeout: Long=60000,condition: ()->Boolean){val end=SystemClock.uptimeMillis()+timeout;while(SystemClock.uptimeMillis()<end){if(runCatching(condition).getOrDefault(false))return;SystemClock.sleep(200)};device.takeScreenshot(File(output,"failure.png"));error(label)}
    private fun probe(method: String,arg: String?=null)=context.contentResolver.acquireUnstableContentProviderClient(Uri.parse("content://"+context.packageName+".officeprobe")).use {requireNotNull(it).call(method,arg,null)}
    private fun uri(file: File)=FileProvider.getUriForFile(context,context.packageName+".testfiles",file)
    @Test fun pptxTenSlidesToPdf()=nativePdf("pptx",10,listOf("TEST_SLIDE_01","TEST_SLIDE_10","TABLE"))
    @Test fun docxPagesToPdf()=nativePdf("docx",2,listOf("DOCX_PAGE_ONE","DOCX_PAGE_TWO","HEADER_TEST","FOOTER_TEST","TABLE_TEST"))
    @Test fun xlsxSheetsToPdf()=nativePdf("xlsx",2,listOf("XLSX_SHEET_ONE","XLSX_SHEET_TWO","42"))
    private fun nativePdf(ext: String,pages: Int,tokens: List<String>)=runBlocking {
        device.wakeUp();device.executeShellCommand("am force-stop com.android.launcher3")
        val dir=File(context.cacheDir,"tests/conversion-"+ext).apply {mkdirs()}
        val original=File(dir,"layout."+ext);instrumentation.context.assets.open("conversion/layout."+ext).use {input->original.outputStream().use {input.copyTo(it)}}
        val hash=ConversionFiles.digest(original);val repo=DocumentRepository(context);val source=repo.open(uri(original),"UTF-8");val working=repo.prepareOffice(source)
        context.startActivity(Intent().setClassName(context.packageName,"org.libreoffice.androidlib.StudioOfficeActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("sessionId",source.id).putExtra("workingPath",working.absolutePath).putExtra("displayName",source.name).putExtra("originalUri",uri(original).toString()))
        try {
            await("Actual native document must load",120000){probe("state")?.getBoolean("ready")==true}
            await("Mobile PDF conversion menu must exist"){device.findObject(By.desc("문서 메뉴"))!=null}
            device.findObject(By.desc("문서 메뉴")).click()
            assertTrue(device.wait(Until.hasObject(By.text("PDF로 변환")),10000));device.pressBack()
            val pdf=File(dir,"result.pdf").apply {writeBytes(byteArrayOf())};probe("convert-pdf",uri(pdf).toString())
            await("Native filter must save and read back PDF",120000){device.findObject(By.text("PDF 변환 완료"))!=null}
            val info=PdfEngine.inspect(pdf);assertEquals(pages,info.pages)
            PDDocument.load(pdf).use {doc->val text=PDFTextStripper().getText(doc);for(token in tokens)assertTrue("PDF must preserve "+token,text.contains(token))
                if(ext=="pptx"){var previous=-1;for(i in 1..10){val next=text.indexOf("TEST_SLIDE_%02d".format(i));assertTrue(next>previous);previous=next};val box=doc.getPage(0).mediaBox;assertEquals(16.0/9.0,(box.width/box.height).toDouble(),.02)}
            }
            val bitmap=PdfEngine.render(pdf,0,600,600);try{assertTrue(bitmap.width>0);bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,File(output,ext+"-page1.png").outputStream())}finally{bitmap.recycle()}
            assertEquals(hash,ConversionFiles.digest(original))
            File(output,ext+".json").writeText(JSONObject().put("pages",pages).put("tokensVerified",true).put("originalUnchanged",true).put("nativeFilter",true).toString(2))
            device.findObject(By.text("닫기"))?.click()
        }finally{runCatching {probe("close")};await("Office process must exit",30000){device.executeShellCommand("pidof "+context.packageName+":office").trim().isEmpty()};runCatching {repo.acceptOffice(source)}}
    }
    @Test fun textMarkdownAndPdfConversions()=runBlocking {
        val repo=DocumentRepository(context);var txt=repo.create(Format.TXT);txt=txt.copy(edits=txt.edits.copy(text=("한국어 문장 English 123\n").repeat(120)))
        val hash=ConversionFiles.digest(txt.base);val pdf=PortableConversions.convert(context,txt,"pdf");assertTrue(PdfEngine.inspect(pdf).pages>1)
        val pdfSession=repo.open(uri(pdf),"UTF-8")
        val png=PortableConversions.convert(context,pdfSession,"png.zip",72,"1-2");java.util.zip.ZipFile(png).use {assertEquals(2,it.size())}
        val jpg=PortableConversions.convert(context,pdfSession,"jpg.zip",144,"1");java.util.zip.ZipFile(jpg).use {assertEquals(1,it.size())}
        assertEquals(listOf(0,2,3),PortableConversions.pages("1,3-4",4))
        val scan=File(context.cacheDir,"tests/text-source.pdf").apply {parentFile!!.mkdirs()}
        PDDocument().use {doc->val page=PDPage();doc.addPage(page);PDPageContentStream(doc,page).use {cs->cs.beginText();cs.setFont(PDType1Font.HELVETICA,14f);cs.newLineAtOffset(30f,700f);cs.showText("PDF_TEXT_TEST");cs.endText()};doc.save(scan)}
        val extracted=PortableConversions.convert(context,repo.open(uri(scan),"UTF-8"),"txt");assertTrue(extracted.readText().contains("PDF_TEXT_TEST"))
        var md=repo.create(Format.MD);md=md.copy(edits=md.edits.copy(text="# 제목\n\n**굵게**\n\n| A | B |\n|---|---|\n| 1 | 2 |\n"))
        assertTrue(PortableConversions.convert(context,md,"html").readText().contains("<table>"));assertTrue(PdfEngine.inspect(PortableConversions.convert(context,md,"pdf")).pages>=1)
        assertTrue(PortableConversions.convert(context,md,"txt").readText().contains("굵게"));assertEquals(hash,ConversionFiles.digest(txt.base))
        File(output,"portable.json").writeText(JSONObject().put("txtPdf",true).put("mdPdfHtmlTxt",true).put("pdfPngJpgTxt",true).put("originalUnchanged",true).toString(2))
    }
}
