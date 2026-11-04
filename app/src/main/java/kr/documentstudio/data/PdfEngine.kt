package kr.documentstudio.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import kr.documentstudio.core.*
import java.io.File
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.form.*
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.util.Matrix
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory

data class PdfForm(val name: String,val value: String,val kind: String,val options: List<String>,val readOnly: Boolean)
data class PdfPageSize(val width: Float,val height: Float)
data class PdfInfo(val pages: Int, val archival: Boolean,val forms: List<PdfForm> = emptyList(),val annotatedPages: Set<Int> = emptySet(),val pageSizes: List<PdfPageSize> = emptyList())
object PdfEngine {
    /** Reading/drawing must show pending in-place edits as well as the saved base. */
    fun renderWorking(s: Session,page: Int,viewportWidth: Int?=null): android.graphics.Bitmap {
        if(s.edits.values.isEmpty()) return if(viewportWidth==null)render(s.base,page,annotationPages=s.pdf?.annotatedPages) else renderForViewport(s.base,page,viewportWidth,2_000_000,s.pdf?.annotatedPages)
        val stage=java.io.File.createTempFile("pdf-working-",".pdf",s.base.parentFile)
        try {
            write(s.base,stage,emptyList(),false,s.edits.values)
            return if(viewportWidth==null)render(stage,page) else renderForViewport(stage,page,viewportWidth,2_000_000)
        } finally {stage.delete()}
    }

    private const val PREFIX = "DocumentStudio:"
    fun inspect(file: File): PdfInfo = PDDocument.load(file).use { doc ->
        require(!doc.isEncrypted) { "암호화된 PDF는 지원하지 않습니다." }
        require(doc.signatureDictionaries.isEmpty()) { "전자서명 PDF는 서명 보호를 위해 열 수 없습니다." }
        require(doc.numberOfPages in 1..2000) { "PDF 페이지 제한(2,000)을 초과했습니다." }
        val xmp = doc.documentCatalog.metadata?.exportXMPMetadata()?.use { it.bufferedReader().readText() } ?: ""
        val forms=doc.documentCatalog.acroForm?.fieldTree?.map {field ->
            val options=when(field) {is PDChoice -> field.options;is PDButton -> field.onValues.toList();else->emptyList()}
            PdfForm(field.fullyQualifiedName,field.valueAsString,when(field) {is PDTextField->"text";is PDCheckBox->"checkbox";is PDChoice->"choice";is PDRadioButton->"radio";else->"unsupported"},options,field.isReadOnly)
        } ?: emptyList()
        val sizes=(0 until doc.numberOfPages).map {index->
            val page=doc.getPage(index);val box=page.cropBox
            val rotated=((page.rotation%360)+360)%360 in listOf(90,270)
            val width=if(rotated)box.height else box.width;val height=if(rotated)box.width else box.height
            require(width.isFinite() && height.isFinite() && width>0 && height>0) {"PDF 페이지 크기를 확인하지 못했습니다."}
            PdfPageSize(width,height)
        }
        PdfInfo(doc.numberOfPages, xmp.contains("pdfaid", ignoreCase = true),forms,(0 until doc.numberOfPages).filter {doc.getPage(it).annotations.isNotEmpty()}.toSet(),sizes)
    }
    fun render(file: File, index: Int,maxWidth: Int=1600,maxHeight: Int=2200,annotationPages: Set<Int>?=null): Bitmap {
        require(maxWidth in 1..1600 && maxHeight in 1..2200)
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer -> renderer.openPage(index).use { page ->
                val scale = minOf(maxWidth.toFloat() / page.width, maxHeight.toFloat() / page.height, 2.5f)
                return Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also {
                    try {
                        it.eraseColor(Color.WHITE); page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        if(annotationPages==null || index in annotationPages) PdfAnnotations.overlay(file,index,it)
                    } catch(failure: Throwable) {it.recycle();throw failure}
                }
            } }
        }
    }
    /** A bounded viewport raster, also used for sharp read-only zoom without PDFBox reparsing. */
    fun renderForViewport(file: File,index: Int,requestedWidth: Int,maxPixels: Int=4_000_000,annotationPages: Set<Int>?=null): Bitmap {
        ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use {fd->
            PdfRenderer(fd).use {renderer->renderer.openPage(index).use {page->
                val (w,h)=PdfRasterBudget.dimensions(page.width,page.height,requestedWidth,maxPixels)
                val image=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                try {
                    image.eraseColor(Color.WHITE);page.render(image,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    if(annotationPages==null || index in annotationPages)PdfAnnotations.overlay(file,index,image)
                    return image
                } catch(e: Throwable){image.recycle();throw e}
            }}
        }
    }
    fun renderAtDpi(file: File,index: Int,dpi: Int): Bitmap {
        require(dpi in 72..216)
        ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use {fd->
            PdfRenderer(fd).use {renderer->renderer.openPage(index).use {page->
                val w=kotlin.math.ceil(page.width*dpi/72.0).toInt();val h=kotlin.math.ceil(page.height*dpi/72.0).toInt()
                require(w>0 && h>0 && w.toLong()*h<=16000000) {"페이지가 너무 큽니다. 해상도를 낮추세요."}
                val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                try{bitmap.eraseColor(Color.WHITE);page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);PdfAnnotations.overlay(file,index,bitmap);return bitmap}
                catch(e:Throwable){bitmap.recycle();throw e}
            }}
        }
    }
    /** Native page dimensions already include CropBox and rotation; no PDFBox document parse. */
    fun displaySize(file: File,index: Int): Pair<Float,Float> =
        ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use {fd->
            PdfRenderer(fd).use {renderer->renderer.openPage(index).use {page->page.width.toFloat() to page.height.toFloat()}}
        }
    fun write(base: File, destination: File, strokes: List<Stroke>, ordinaryCopy: Boolean,values: Map<String,String> = emptyMap(),layers: List<InkLayer> = listOf(InkLayer())) {
        InkLayers.validate(layers)
        require(strokes.all {s->layers.any {it.id==s.layerId}})
        PDDocument.load(base).use { doc ->
            PdfTextEdits.apply(doc,values)
            doc.documentCatalog.cosObject.setString("StudioLayers",StateCodec.layers(layers).toString())
            values.filterKeys {it.startsWith("pdf:form#")}.forEach {(key,value)->
                val field=doc.documentCatalog.acroForm?.getField(key.removePrefix("pdf:form#")) ?: error("PDF 양식 필드를 찾을 수 없습니다.")
                require(!field.isReadOnly && field !is PDSignatureField) {"읽기 전용·서명 필드는 수정할 수 없습니다."}
                field.setValue(value)
            }
            if (ordinaryCopy) { doc.documentCatalog.metadata = null; doc.documentCatalog.cosObject.removeItem(COSName.getPDFName("OutputIntents")) }
            (0 until doc.numberOfPages).forEach { index ->
                val page = doc.getPage(index)
                page.annotations = page.annotations.filterNot { it.annotationName?.startsWith(PREFIX) == true }.toMutableList()
                val rect = page.cropBox; val rotation = ((page.rotation % 360) + 360) % 360
                val displayWidth = if (rotation in listOf(90,270)) rect.height else rect.width
                layers.flatMap {l->strokes.filter {it.layerId==l.id}}.filter { it.anchor == "pdf:$index" }.forEach { s ->
                    val points = shapePoints(s)
                    fun map(p: InkPoint): Pair<Float,Float> = when(rotation) {
                        90 -> (rect.lowerLeftX + p.y * rect.width) to (rect.lowerLeftY + p.x * rect.height)
                        180 -> (rect.upperRightX - p.x * rect.width) to (rect.lowerLeftY + p.y * rect.height)
                        270 -> (rect.upperRightX - p.y * rect.width) to (rect.upperRightY - p.x * rect.height)
                        else -> (rect.lowerLeftX + p.x * rect.width) to (rect.upperRightY - p.y * rect.height)
                    }
                    val xy = points.map { map(it) }
                    val dict = COSDictionary().apply {
                        setItem(COSName.TYPE, COSName.ANNOT); setName(COSName.SUBTYPE, "Ink")
                        setItem(COSName.getPDFName("InkList"), COSArray().apply { add(COSArray().apply { xy.forEach { (x,y) -> add(COSFloat(x)); add(COSFloat(y)) } }) })
                    }
                    val annotation = PDAnnotationMarkup(dict).apply {
                        annotationName = PREFIX + s.id
                        titlePopup = "문서 스튜디오"
                        contents = if(s.tool == InkTool.HIGHLIGHTER) "형광펜" else "필기"
                        color = PDColor(floatArrayOf(Color.red(s.color)/255f, Color.green(s.color)/255f, Color.blue(s.color)/255f), PDDeviceRGB.INSTANCE)
                        constantOpacity = s.opacity * if(s.tool == InkTool.HIGHLIGHTER) 0.28f else if(s.tool==InkTool.PENCIL) 0.6f else 1f
                        borderStyle = PDBorderStyleDictionary().apply { width = s.width * displayWidth }
                        val margin = PdfInkAppearance.margin(s,points,displayWidth)
                        rectangle = PDRectangle(xy.minOf { it.first }-margin, xy.minOf { it.second }-margin,
                            xy.maxOf { it.first }-xy.minOf { it.first }+margin*2, xy.maxOf { it.second }-xy.minOf { it.second }+margin*2)
                        isPrinted = layers.first {it.id==s.layerId}.visible
                        isHidden = !isPrinted
                    }
                    page.annotations.add(annotation)
                    annotation.cosObject.setString("StudioTool",s.tool.name)
                    annotation.cosObject.setFloat(COSName.getPDFName("StudioOpacity"),s.opacity)
                    annotation.cosObject.setFloat(COSName.getPDFName("StudioWidth"),s.width)
                    annotation.cosObject.setFloat(COSName.getPDFName("StudioCurve"),s.pressureCurve)
                    annotation.cosObject.setFloat(COSName.getPDFName("StudioSmoothing"),s.smoothing)
                    annotation.cosObject.setString("StudioLayer",s.layerId)
                    annotation.cosObject.setBoolean(COSName.getPDFName("StudioSnapped"),s.snapped)
                    annotation.cosObject.setString("StudioPoints",org.json.JSONArray().apply {s.points.forEach {p->put(org.json.JSONArray(listOf(p.x,p.y,p.pressure,p.tilt)))}}.toString())
                    PdfInkAppearance.write(doc,annotation,s,points,xy,displayWidth)
                }
            }
            doc.save(destination)
        }
        inspect(destination)
    }
    /** Import our portable standard ink, while preserving other applications' annotations in the base. */
    fun importOwned(file: File): List<Stroke> = PDDocument.load(file).use { doc ->
        val strokes = mutableListOf<Stroke>()
        (0 until doc.numberOfPages).forEach { index ->
            val page = doc.getPage(index); val rect = page.cropBox; val rotation = ((page.rotation%360)+360)%360
            page.annotations.filter { it.annotationName?.startsWith(PREFIX) == true }.forEach { ann ->
                val lists = ann.cosObject.getDictionaryObject(COSName.getPDFName("InkList")) as? COSArray ?: return@forEach
                if (lists.size() == 0) return@forEach
                val coords = (lists.getObject(0) as? COSArray)?.toFloatArray() ?: return@forEach
                val pts = coords.toList().chunked(2).filter { it.size == 2 }.map { (x,y) ->
                    val nx = (x-rect.lowerLeftX)/rect.width; val ny = (y-rect.lowerLeftY)/rect.height
                    when(rotation) { 90 -> InkPoint(ny,nx); 180 -> InkPoint(1-nx,ny); 270 -> InkPoint(1-ny,1-nx); else -> InkPoint(nx,1-ny) }
                }
                val mark = ann as? PDAnnotationMarkup
                val displayWidth = if(rotation in listOf(90,270)) rect.height else rect.width
                val stored=ann.cosObject.getString("StudioPoints")
                val original=if(stored!=null) runCatching {val array=org.json.JSONArray(stored);require(array.length()<=10000);(0 until array.length()).map {n->array.getJSONArray(n).let {p->InkPoint(p.getDouble(0).toFloat(),p.getDouble(1).toFloat(),p.getDouble(2).toFloat(),p.optDouble(3,0.0).toFloat())}}.also {points->require(points.all {it.x.isFinite() && it.y.isFinite() && it.x in 0f..1f && it.y in 0f..1f && it.pressure.isFinite() && it.tilt.isFinite()})}}.getOrNull() else null
                val tool=runCatching {InkTool.valueOf(ann.cosObject.getString("StudioTool") ?: "")}.getOrNull() ?: if((mark?.constantOpacity ?: 1f)<0.5f) InkTool.HIGHLIGHTER else InkTool.PEN
                strokes.add(Stroke(ann.annotationName.removePrefix(PREFIX), "pdf:$index",tool,
                    ann.color?.toRGB()?.or(0xFF000000.toInt()) ?: Color.BLACK, ann.cosObject.getFloat(COSName.getPDFName("StudioWidth"),(mark?.borderStyle?.width ?: 2f)/displayWidth).also {require(it.isFinite() && it in .0001f..0.1f)}, original ?: pts,ann.cosObject.getFloat(COSName.getPDFName("StudioOpacity"),1f).coerceIn(0f,1f),
                    ann.cosObject.getFloat(COSName.getPDFName("StudioCurve"),1f).also {require(it.isFinite() && it in 0.25f..3f)},
                    ann.cosObject.getFloat(COSName.getPDFName("StudioSmoothing"),0f).also {require(it.isFinite() && it in 0f..1f)},
                    ann.cosObject.getString("StudioLayer") ?: "default",ann.cosObject.getBoolean(COSName.getPDFName("StudioSnapped"),false)))
            }
        }
        strokes
    }
    fun importLayers(file: File): List<InkLayer> = PDDocument.load(file).use {doc->
        doc.documentCatalog.cosObject.getString("StudioLayers")?.let {require(it.length<=8192);StateCodec.decodeLayers(org.json.JSONArray(it))} ?: listOf(InkLayer())
    }
    fun cleanOwned(file: File) {
        val tmp = File(file.parentFile, "clean-${System.nanoTime()}.pdf")
        var changed = false
        PDDocument.load(file).use { doc ->
            doc.pages.forEach { page ->
                val annotations = page.annotations
                val clean = annotations.filterNot { it.annotationName?.startsWith(PREFIX) == true }
                if (clean.size != annotations.size) { page.annotations = clean.toMutableList(); changed = true }
            }
            if(changed) doc.save(tmp)
        }
        if(changed) { tmp.copyTo(file, overwrite=true); tmp.delete() }
    }
    fun text(file: File,page: Int?=null): String = PDDocument.load(file).use {doc ->
        val stripper=PDFTextStripper().apply {if(page!=null) {startPage=page+1;endPage=page+1}}
        val text=stripper.getText(doc);require(text.length<=2_000_000) {"텍스트 추출 제한을 초과했습니다."};text
    }
    fun search(file: File,query: String): List<Int> = PDDocument.load(file).use {doc ->
        require(query.isNotBlank());val stripper=PDFTextStripper();val found=mutableListOf<Int>()
        val compact=query.filterNot {it.isWhitespace()}
        for(i in 0 until doc.numberOfPages) {stripper.startPage=i+1;stripper.endPage=i+1;val text=stripper.getText(doc);if(text.contains(query,true) || compact.isNotEmpty() && text.filterNot {it.isWhitespace()}.contains(compact,true)) found.add(i)};found
    }
    /** Returns the mapping of output pages to input pages; -1 denotes a newly added page. */
    fun change(base: File,destination: File,change: DocumentChange,attachment: File? = null): List<Int> = PDDocument.load(base).use {doc ->
        val count=doc.numberOfPages;val mapping=(0 until count).toMutableList();val index=change.target.toIntOrNull() ?: 0
        require(index in 0 until count);val args=change.arguments
        when(change.type) {
            "pdf.markupAdd","pdf.annotationEdit","pdf.annotationDelete" -> PdfAnnotations.change(doc,index,change)
            "pdf.bookmarkAdd","pdf.bookmarkRename","pdf.bookmarkDelete" -> PdfBookmarks.change(doc,index,change)
            "pdf.replaceText" -> PdfBodyText.replace(doc,index,args.getValue("id").toInt(),args.getValue("expected"),args.getValue("text"))
            "pdf.ocrPages" -> {val json=args.getValue("pages");require(json.length<=2_000_000);PdfOcrText.add(doc,org.json.JSONArray(json),requireNotNull(attachment))}
            "pdf.transformImage","pdf.deleteImage" -> PdfImages.change(doc,index,args.getValue("id").toInt(),args.getValue("expected"),if(change.type=="pdf.deleteImage") null else listOf("x","y","width","height").map {args.getValue(it).toFloat()})
            "pdf.add" -> {doc.pages.insertAfter(PDPage(doc.getPage(index).mediaBox),doc.getPage(index));mapping.add(index+1,-1)}
            "pdf.delete" -> {require(count>1) {"마지막 페이지는 삭제할 수 없습니다."};require(doc.getPage(index).annotations.none {it.subtype=="Widget"}) {"양식 위젯이 있는 페이지는 삭제할 수 없습니다."};PdfBookmarks.beforePageDeletion(doc,index);doc.removePage(index);mapping.removeAt(index)}
            "pdf.rotate" -> {val angle=args["angle"]?.toInt() ?: 90;require(angle in listOf(90,180,270));val p=doc.getPage(index);p.rotation=((p.rotation+angle)%360+360)%360}
            "pdf.move" -> {
                val target=args.getValue("index").toInt();require(target in 0 until count);val page=doc.getPage(index);doc.pages.remove(page)
                if(target>=doc.numberOfPages) doc.addPage(page) else doc.pages.insertBefore(page,doc.getPage(target));mapping.removeAt(index);mapping.add(target,index)
            }
            "pdf.extract" -> {
                require(doc.documentCatalog.acroForm?.fields.isNullOrEmpty()) {"양식 PDF 추출은 지원하지 않습니다. 원본 필드를 보호합니다."}
                val start=args.getValue("first").toInt()-1;val end=args.getValue("last").toInt()-1;require(start in 0 until count && end in start until count)
                PDDocument().use {out -> for(i in start..end) {val page=out.importPage(doc.getPage(i));page.resources=doc.getPage(i).resources};out.save(destination)}
                inspect(destination);return@use (start..end).toList()
            }
            "pdf.merge" -> {
                require(attachment!=null);val otherInfo=inspect(attachment)
                require(doc.documentCatalog.acroForm?.fields.isNullOrEmpty() && otherInfo.forms.isEmpty()) {"양식 PDF 병합은 지원하지 않습니다."}
                PDDocument.load(attachment).use {other->PDFMergerUtility().appendDocument(doc,other)};repeat(otherInfo.pages) {mapping.add(-1)}
            }
            "pdf.note" -> {
                val text=args.getValue("text");require(text.length in 1..10000);val x=args.getValue("x").toFloat()/100;val y=args.getValue("y").toFloat()/100;require(x in 0f..1f && y in 0f..1f)
                val page=doc.getPage(index);val rect=page.cropBox
                page.annotations.add(PDAnnotationText().apply {contents=text;titlePopup="문서 스튜디오";annotationName="DocumentStudioNote:"+java.util.UUID.randomUUID();rectangle=PDRectangle(rect.lowerLeftX+x*rect.width,rect.upperRightY-y*rect.height-20,20f,20f);isPrinted=true})
            }
            "pdf.text","pdf.image" -> {
                val page=doc.getPage(index);val rect=page.cropBox;val rotation=((page.rotation%360)+360)%360
                val width=if(rotation in listOf(90,270)) rect.height else rect.width;val height=if(rotation in listOf(90,270)) rect.width else rect.height
                val x=(args["x"] ?: "10").toFloat()/100;val y=(args["y"] ?: "10").toFloat()/100;require(x in 0f..0.95f && y in 0f..0.95f)
                val matrix=when(rotation) {90->Matrix(0f,1f,-1f,0f,rect.upperRightX,rect.lowerLeftY);180->Matrix(-1f,0f,0f,-1f,rect.upperRightX,rect.upperRightY);270->Matrix(0f,-1f,1f,0f,rect.lowerLeftX,rect.upperRightY);else->Matrix(1f,0f,0f,1f,rect.lowerLeftX,rect.lowerLeftY)}
                if(change.type=="pdf.image") {
                    require(attachment!=null);val bounds=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true};android.graphics.BitmapFactory.decodeFile(attachment.path,bounds)
                    require(bounds.outWidth>0 && bounds.outHeight>0 && bounds.outWidth.toLong()*bounds.outHeight<=8_000_000) {"이미지는 최대 800만 화소까지 지원합니다."}
                    val bitmap=android.graphics.BitmapFactory.decodeFile(attachment.path) ?: error("이미지를 읽을 수 없습니다.")
                    try {val w=(args["width"] ?: "40").toFloat()/100*width;val h=w*bitmap.height/bitmap.width;require(w>0 && w<=width*(1-x) && h<=height*(1-y)) {"이미지가 페이지 범위를 벗어납니다."};val image=LosslessFactory.createFromImage(doc,bitmap)
                        PDPageContentStream(doc,page,PDPageContentStream.AppendMode.APPEND,true,true).use {stream->stream.transform(matrix);stream.drawImage(image,x*width,height*(1-y)-h,w,h)}
                    } finally {bitmap.recycle()}
                } else {
                    val text=args.getValue("text");require(text.length in 1..5000);val size=(args["size"] ?: "14").toFloat();require(size in 4f..100f)
                    val overlay=File(destination.parentFile,"overlay-${java.util.UUID.randomUUID()}.pdf")
                    val native=android.graphics.pdf.PdfDocument()
                    try {val p=native.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(kotlin.math.ceil(width).toInt(),kotlin.math.ceil(height).toInt(),1).create())
                        val paint=android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {textSize=size;color=Color.BLACK}
                        val layout=android.text.StaticLayout.Builder.obtain(text,0,text.length,paint,((1-x)*width-4).toInt().coerceAtLeast(1)).build();require(layout.height<=height*(1-y)) {"텍스트가 페이지 범위를 벗어납니다."}
                        p.canvas.translate(x*width,y*height);layout.draw(p.canvas);native.finishPage(p);overlay.outputStream().use {native.writeTo(it)}
                        PDDocument.load(overlay).use {layer->val form=LayerUtility(doc).importPageAsForm(layer,0);PDPageContentStream(doc,page,PDPageContentStream.AppendMode.APPEND,true,true).use {stream->stream.transform(matrix);stream.drawForm(form)}}
                    } finally {native.close();overlay.delete()}
                }
            }
            else -> error("지원하지 않는 PDF 편집입니다.")
        }
        doc.save(destination);inspect(destination);mapping
    }
}

fun shapePoints(s: Stroke): List<InkPoint> {
    return InkGeometry.path(s)
}
