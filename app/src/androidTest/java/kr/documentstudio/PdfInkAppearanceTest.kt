package kr.documentstudio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kr.documentstudio.core.*
import kr.documentstudio.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class PdfInkAppearanceTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private fun file()=File(context.cacheDir,"tests/${UUID.randomUUID()}.pdf").apply {parentFile!!.mkdirs()}
    @Test fun standardAppearancePreservesPressureWidthOnRotatedCroppedPages() {
        val stroke=Stroke(anchor="pdf:0",tool=InkTool.PEN,color=0xFF123456.toInt(),width=.02f,pressureCurve=2f,points=listOf(InkPoint(.2f,.5f,.2f),InkPoint(.5f,.5f,1f),InkPoint(.8f,.5f,2f)))
        for(rotation in listOf(0,90,180,270)) {
            val source=file();PDDocument().use {doc->doc.addPage(PDPage(PDRectangle(430f,320f)).apply {cropBox=PDRectangle(20f,30f,350f,240f);this.rotation=rotation});doc.save(source)}
            val saved=file();PdfEngine.write(source,saved,listOf(stroke),false)
            PDDocument.load(saved).use {doc->val annotation=doc.getPage(0).annotations.single();val commands=annotation.normalAppearanceStream.cosObject.createInputStream().use {it.readBytes().toString(Charsets.US_ASCII)}
                val widths=Regex("([0-9.]+) w").findAll(commands).map {it.groupValues[1]}.toSet();assertTrue("$rotation: $commands",widths.size>=2)
            }
            val bitmap=PdfEngine.render(saved,0)
            fun count(x: Float)=(0 until bitmap.height).count {y->val p=bitmap.getPixel((bitmap.width*x).toInt(),y);android.graphics.Color.red(p)<200}
            val thin=count(.35f);val thick=count(.65f);assertTrue("rotation $rotation thin=$thin thick=$thick",thin>0 && thick>thin*1.5);bitmap.recycle()
            assertEquals(stroke,PdfEngine.importOwned(saved).single())
        }
    }
}
