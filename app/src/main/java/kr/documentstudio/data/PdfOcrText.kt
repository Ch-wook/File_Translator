package kr.documentstudio.data

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import org.json.JSONArray
import java.io.File

object PdfOcrText {
    fun add(doc: PDDocument,pages: JSONArray,fontFile: File) {
        require(pages.length() in 1..20)
        val indexes=(0 until pages.length()).map {pages.getJSONObject(it).getInt("page")};require(indexes.distinct().size==indexes.size)
        val font=fontFile.inputStream().use {PDType0Font.load(doc,it,true)}
        for(i in 0 until pages.length()) {
            val request=pages.getJSONObject(i);val index=request.getInt("page");require(index in 0 until doc.numberOfPages)
            val page=doc.getPage(index);require(!page.cosObject.getBoolean(COSName.getPDFName("StudioOCR"),false)) {"이미 검색 텍스트를 추가한 페이지입니다. 실행 취소 후 다시 인식하세요."}
            val lines=request.getJSONArray("lines");require(lines.length() in 1..1000)
            val crop=page.cropBox;val rotation=((page.rotation%360)+360)%360;require(rotation in listOf(0,90,180,270))
            val width=if(rotation in listOf(90,270)) crop.height else crop.width;val height=if(rotation in listOf(90,270)) crop.width else crop.height
            val matrix=when(rotation) {90->Matrix(0f,1f,-1f,0f,crop.upperRightX,crop.lowerLeftY);180->Matrix(-1f,0f,0f,-1f,crop.upperRightX,crop.upperRightY);270->Matrix(0f,-1f,1f,0f,crop.lowerLeftX,crop.upperRightY);else->Matrix(1f,0f,0f,1f,crop.lowerLeftX,crop.lowerLeftY)}
            var characters=0
            PDPageContentStream(doc,page,PDPageContentStream.AppendMode.APPEND,true,true).use {out->
                out.saveGraphicsState();out.transform(matrix);out.beginMarkedContent(COSName.getPDFName("DocumentStudioOCR"))
                for(j in 0 until lines.length()) {
                    val line=lines.getJSONArray(j);require(line.length()==5);val text=line.getString(0);characters+=text.length;require(text.length in 1..10000 && characters<=50000 && text.none {it.isISOControl()})
                    val n=(1..4).map {line.getDouble(it).toFloat()};require(n.all {it.isFinite() && it in 0f..1f} && n[2]>n[0] && n[3]>n[1])
                    val size=((n[3]-n[1])*height*.8f).coerceIn(.5f,500f);val advance=font.getStringWidth(text)*size/1000;require(advance>0)
                    out.beginText();out.setRenderingMode(RenderingMode.NEITHER);out.setFont(font,size);out.setHorizontalScaling(((n[2]-n[0])*width/advance*100).coerceIn(1f,10000f));out.newLineAtOffset(n[0]*width,(1-n[3])*height);out.showText(text);out.endText()
                }
                out.endMarkedContent();out.restoreGraphicsState()
            };page.cosObject.setBoolean(COSName.getPDFName("StudioOCR"),true)
        }
    }
}
