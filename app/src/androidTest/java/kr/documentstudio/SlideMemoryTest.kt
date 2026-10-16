package kr.documentstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.runBlocking
import kr.documentstudio.core.*
import kr.documentstudio.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class SlideMemoryTest {
    @Test fun decodedSlideImagesHaveAnAggregateBudgetAndAreReleased() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();PDFBoxResourceLoader.init(context)
        val repo=DocumentRepository(context);val session=repo.create(Format.PPTX);val section=session.sections.first().id
        val image=File(context.cacheDir,"tests/${UUID.randomUUID()}.png").apply {parentFile!!.mkdirs()}
        val bitmap=Bitmap.createBitmap(1400,1400,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.BLUE)}
        try {image.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}} finally {bitmap.recycle()}
        var base=session.base
        repeat(5) {
            val next=File(session.base.parentFile,"memory-${UUID.randomUUID()}.pptx")
            PackageFeatures.apply(base,next,change=DocumentChange("slide.image",section),attachment=image);base=next
        }
        val scene=SlideEngine.load(session.copy(base=base),PackageDocument.open(base).sections.first())
        val images=scene.shapes.mapNotNull {it.image}.distinct()
        try {
            assertEquals(4,images.size);assertEquals(1,scene.omitted)
            assertTrue(images.sumOf {it.width.toLong()*it.height}<=8_000_000)
        } finally {scene.recycle()}
        assertTrue(images.all {it.isRecycled})
    }
}
