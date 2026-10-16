package kr.documentstudio.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.*
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.*
import com.tom_roush.pdfbox.rendering.ImageType
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.tom_roush.pdfbox.util.Matrix
import kr.documentstudio.core.DocumentChange
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.*

data class PdfGlyph(val first: Int,val last: Int,val quad: FloatArray?)
data class PdfSelectableText(val text: String,val glyphs: List<PdfGlyph>)
data class PdfAnnotationEntry(val index: Int,val kind: String,val text: String,val color: String,val expected: String)

object PdfAnnotations {
    private val kinds=setOf("Highlight","Underline","StrikeOut")
    fun selectable(file: File,page: Int): PdfSelectableText = PDDocument.load(file).use {selectable(it,page)}
    private fun selectable(doc: PDDocument,page: Int): PdfSelectableText {
        require(page in 0 until doc.numberOfPages)
        val crop=doc.getPage(page).cropBox
        val text=StringBuilder();val glyphs=ArrayList<PdfGlyph>()
        val stripper=object: PDFTextStripper() {
            override fun writeString(ignored: String,positions: MutableList<TextPosition>) {
                for(p in positions) {
                    val content=p.unicode.orEmpty();if(content.isEmpty()) continue
                    require(text.length+content.length<=1_000_000 && glyphs.size<100000) {"페이지 텍스트 선택 용량 제한을 넘었습니다."}
                    val first=text.length;text.append(content)
                    if(content.all {it.isWhitespace()}) continue
                    val m=p.textMatrix
                    val ax=m.translateX+crop.lowerLeftX;val ay=m.translateY+crop.lowerLeftY
                    val bx=p.endX+crop.lowerLeftX;val by=p.endY+crop.lowerLeftY
                    val width=hypot(bx-ax,by-ay);val up=hypot(m.shearX,m.scaleY);val h=p.heightDir
                    val quad=if(width>0.001f && up>0.001f && h>0.001f && !p.font.isVertical) {
                        val vx=m.shearX/up;val vy=m.scaleY/up
                        floatArrayOf(ax+vx*h,ay+vy*h,bx+vx*h,by+vy*h,ax-vx*h*0.2f,ay-vy*h*0.2f,bx-vx*h*0.2f,by-vy*h*0.2f)
                            .takeIf {q -> q.all {it.isFinite() && abs(it)<1_000_000} && abs((bx-ax)*vy-(by-ay)*vx)>width*0.1f}
                    } else null
                    glyphs.add(PdfGlyph(first,text.length,quad))
                }
            }
            override fun writeWordSeparator() {if(text.isNotEmpty() && !text.last().isWhitespace()) text.append(' ')}
            override fun writeLineSeparator() {if(text.isNotEmpty() && text.last()!='\n') text.append('\n')}
        }
        stripper.sortByPosition=true;stripper.startPage=page+1;stripper.endPage=page+1
        stripper.getText(doc)
        return PdfSelectableText(text.toString(),glyphs)
    }
    fun list(file: File,page: Int): List<PdfAnnotationEntry> = PDDocument.load(file).use {doc ->
        doc.getPage(page).annotations.mapIndexedNotNull {i,ann ->
            if(ann.subtype !in kinds && ann.subtype!="Text") null
            else PdfAnnotationEntry(i,ann.subtype,ann.contents.orEmpty(),String.format("%06X",(ann.color?.toRGB() ?: 0xFFE36B) and 0xffffff),fingerprint(ann))
        }
    }
    fun change(doc: PDDocument,page: Int,change: DocumentChange) {
        val args=change.arguments;val target=doc.getPage(page)
        require(target.annotations.size<5000) {"페이지 주석 수 제한을 넘었습니다."}
        if(change.type=="pdf.markupAdd") {
            val kind=args.getValue("kind");require(kind in kinds)
            val selected=selectable(doc,page);val first=args.getValue("first").toInt();val last=args.getValue("last").toInt()
            require(first in selected.text.indices && last in first+1..selected.text.length && last-first<=10000)
            require(selected.text.substring(first,last)==args.getValue("expected")) {"선택한 본문이 바뀌었습니다. 다시 선택해 주세요."}
            val chosen=selected.glyphs.filter {it.last>first && it.first<last}
            require(chosen.isNotEmpty() && chosen.size<=4000) {"선택 범위를 줄여 주세요."}
            require(chosen.all {it.quad!=null}) {"이 선택에는 지원하지 않는 세로쓰기 또는 글자 좌표가 있습니다."}
            val quads=merge(chosen.map {it.quad!!})
            val ann=PDAnnotationTextMarkup(kind).apply {
                annotationName="DocumentStudioMarkup:"+UUID.randomUUID();titlePopup="문서 스튜디오"
                contents=args.getValue("expected");quadPoints=quads.flatMap {it.toList()}.toFloatArray()
                color=rgb(args["color"] ?: "FFE36B");constantOpacity=if(kind=="Highlight") 0.3f else 1f;isPrinted=true
            }
            appearance(doc,ann);target.annotations.add(ann)
            return
        }
        val index=args.getValue("id").toInt();val ann=target.annotations.getOrNull(index) ?: error("주석을 다시 선택해 주세요.")
        require(ann.subtype in kinds || ann.subtype=="Text")
        require(fingerprint(ann)==args.getValue("expected")) {"주석이 바뀌었습니다. 목록을 다시 열어 주세요."}
        when(change.type) {
            "pdf.annotationEdit" -> {
                val content=args.getValue("text");require(content.length<=10000)
                ann.contents=content
                args["color"]?.let {ann.color=rgb(it)}
                if(ann is PDAnnotationTextMarkup) appearance(doc,ann)
                else {ann.cosObject.removeItem(COSName.AP);ann.constructAppearances(doc)}
            }
            "pdf.annotationDelete" -> {
                require(target.annotations.none {it.cosObject.getDictionaryObject(COSName.getPDFName("IRT"))===ann.cosObject}) {"답글이 연결된 주석은 삭제할 수 없습니다."}
                val popup=(ann as? PDAnnotationMarkup)?.popup?.cosObject
                target.annotations=target.annotations.filterNot {it.cosObject===ann.cosObject || it.cosObject===popup}.toMutableList()
            }
            else -> error("지원하지 않는 주석 명령")
        }
    }
    private fun rgb(value: String): PDColor {
        require(value.matches(Regex("[0-9a-fA-F]{6}"))) {"6자리 색상을 입력하세요."}
        val c=value.toInt(16)
        return PDColor(floatArrayOf((c shr 16 and 255)/255f,(c shr 8 and 255)/255f,(c and 255)/255f),PDDeviceRGB.INSTANCE)
    }
    private fun fingerprint(ann: PDAnnotation): String {
        val value=listOf(ann.subtype,ann.annotationName.orEmpty(),ann.contents.orEmpty(),ann.rectangle?.toString().orEmpty(),
            (ann as? PDAnnotationTextMarkup)?.quadPoints?.joinToString(",").orEmpty(),ann.color?.toRGB().toString()).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {"%02x".format(it)}
    }
    private fun merge(quads: List<FloatArray>): List<FloatArray> {
        val result=ArrayList<FloatArray>()
        for(q in quads) {
            val last=result.lastOrNull()
            val h=if(last==null) 0f else hypot(last[0]-last[4],last[1]-last[5])
            if(last!=null && hypot(last[2]-q[0],last[3]-q[1])<h*0.8f && hypot(last[6]-q[4],last[7]-q[5])<h*0.8f &&
                abs((last[2]-last[0])*(q[1]-last[1])-(last[3]-last[1])*(q[0]-last[0]))<hypot(last[2]-last[0],last[3]-last[1])*h*0.15f) {
                last[2]=q[2];last[3]=q[3];last[6]=q[6];last[7]=q[7]
            } else result.add(q.copyOf())
        }
        return result
    }
    /** Explicit appearance makes rotated/sheared quads portable to other readers. */
    private fun appearance(doc: PDDocument,ann: PDAnnotationTextMarkup) {
        val quads=ann.quadPoints;require(quads.isNotEmpty() && quads.size%8==0 && quads.all {it.isFinite()})
        val xs=quads.filterIndexed {i,_ -> i%2==0};val ys=quads.filterIndexed {i,_ -> i%2==1}
        val rect=PDRectangle(xs.min()-1,ys.min()-1,xs.max()-xs.min()+2,ys.max()-ys.min()+2)
        ann.rectangle=rect
        val stream=PDAppearanceStream(doc).apply {bBox=PDRectangle(rect.width,rect.height);resources=PDResources()}
        PDPageContentStream(doc,stream).use {out ->
            out.transform(Matrix.getTranslateInstance(-rect.lowerLeftX,-rect.lowerLeftY))
            out.setGraphicsStateParameters(PDExtendedGraphicsState().apply {nonStrokingAlphaConstant=ann.constantOpacity;strokingAlphaConstant=ann.constantOpacity})
            out.setNonStrokingColor(ann.color);out.setStrokingColor(ann.color)
            for(i in quads.indices step 8) {
                if(ann.subtype=="Highlight") {
                    out.moveTo(quads[i],quads[i+1]);out.lineTo(quads[i+2],quads[i+3]);out.lineTo(quads[i+6],quads[i+7]);out.lineTo(quads[i+4],quads[i+5]);out.closePath();out.fill()
                } else {
                    val ratio=if(ann.subtype=="StrikeOut") 0.45f else 0f
                    out.setLineWidth((hypot(quads[i]-quads[i+4],quads[i+1]-quads[i+5])*0.07f).coerceAtLeast(0.5f))
                    out.moveTo(quads[i+4]+(quads[i]-quads[i+4])*ratio,quads[i+5]+(quads[i+1]-quads[i+5])*ratio)
                    out.lineTo(quads[i+6]+(quads[i+2]-quads[i+6])*ratio,quads[i+7]+(quads[i+3]-quads[i+7])*ratio);out.stroke()
                }
            }
        }
        ann.appearance=PDAppearanceDictionary().apply {setNormalAppearance(stream)}
        if(doc.version<1.4f) doc.version=1.4f
    }
    /** Native PdfRenderer omits many annotations. Composite their appearances only. */
    fun overlay(file: File,index: Int,bitmap: Bitmap) = PDDocument.load(file).use {source ->
        val original=source.getPage(index);val annotations=original.annotations
        if(annotations.isEmpty()) return@use
        require(annotations.size<=5000) {"페이지 주석 표시 한도를 넘었습니다."}
        PDDocument().use {overlay ->
            val page=PDPage(original.mediaBox).apply {cropBox=original.cropBox;rotation=0;resources=original.resources;this.annotations=annotations.toMutableList()}
            overlay.addPage(page)
            val rotation=((original.rotation%360)+360)%360
            val width=if(rotation in listOf(90,270)) page.cropBox.height else page.cropBox.width
            val image=PDFRenderer(overlay).renderImage(0,bitmap.width/width,ImageType.ARGB)
            try {
                val canvas=Canvas(bitmap);canvas.save()
                when(rotation) {90 -> {canvas.translate(bitmap.width.toFloat(),0f);canvas.rotate(90f)};180 -> {canvas.translate(bitmap.width.toFloat(),bitmap.height.toFloat());canvas.rotate(180f)};270 -> {canvas.translate(0f,bitmap.height.toFloat());canvas.rotate(270f)}}
                val target=if(rotation in listOf(90,270)) RectF(0f,0f,bitmap.height.toFloat(),bitmap.width.toFloat()) else RectF(0f,0f,bitmap.width.toFloat(),bitmap.height.toFloat())
                canvas.drawBitmap(image,null,target,null);canvas.restore()
            } finally {image.recycle()}
        }
    }
}
