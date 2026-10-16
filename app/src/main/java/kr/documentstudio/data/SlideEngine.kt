package kr.documentstudio.data

import android.graphics.*
import android.text.*
import android.text.style.*
import kr.documentstudio.core.*
import org.w3c.dom.Element
import java.util.zip.ZipFile
import java.io.File

data class SlideShape(val id: String,val x: Float,val y: Float,val width: Float,val height: Float,val rotation: Float,
    val geometry: String,val fill: Int?,val text: CharSequence,val alignment: Layout.Alignment,val image: Bitmap?=null)
data class SlideScene(val width: Float,val height: Float,val shapes: List<SlideShape>,val omitted: Int) {
    fun recycle() {shapes.forEach {it.image?.recycle()}}
}
/** Basic direct shapes only; theme/master/SmartArt and grouped geometry are explicitly excluded. */
object SlideEngine {
    fun load(s: Session,section: Section): SlideScene = ZipFile(s.base).use {zip ->
        fun xml(path: String)=Xml.parse(zip.getInputStream(zip.getEntry(path) ?: error("슬라이드 파트 누락")).use {input->
            val bytes=input.readBytes();require(bytes.size<=8*1024*1024);bytes
        })
        val presentation=xml("ppt/presentation.xml");val size=Xml.elements(presentation,"sldSz").single()
        val width=size.getAttribute("cx").toFloat()/12700;val height=size.getAttribute("cy").toFloat()/12700;require(width>0 && height>0)
        val doc=xml(section.id);val tree=Xml.elements(doc,"spTree").first();val allTexts=Xml.elements(doc,"t").filter {it.namespaceURI==PackageDocument.A}
        val relPath=section.id.substringBeforeLast('/')+"/_rels/"+section.id.substringAfterLast('/')+".rels"
        val rels=if(zip.getEntry(relPath)!=null) Xml.elements(xml(relPath),"Relationship").filter {it.getAttribute("TargetMode")!="External"}.associate {it.getAttribute("Id") to it.getAttribute("Target")} else emptyMap()
        var omitted=0
        val shapes=(0 until tree.childNodes.length).map {tree.childNodes.item(it)}.filterIsInstance<Element>().mapNotNull {shape ->
            if(shape.localName in listOf("nvGrpSpPr","grpSpPr")) return@mapNotNull null
            if(shape.localName !in listOf("sp","pic")) {omitted++;return@mapNotNull null}
            val pr=Xml.child(shape,"spPr");val transform=pr?.let {Xml.child(it,"xfrm")};val off=transform?.let {Xml.child(it,"off")};val ext=transform?.let {Xml.child(it,"ext")}
            if(off==null || ext==null) {omitted++;return@mapNotNull null}
            val x=off.getAttribute("x").toFloat()/12700;val y=off.getAttribute("y").toFloat()/12700;val w=ext.getAttribute("cx").toFloat()/12700;val h=ext.getAttribute("cy").toFloat()/12700
            if(!listOf(x,y,w,h).all {it.isFinite()} || w<=0 || h<=0) {omitted++;return@mapNotNull null}
            val geometry=pr.let {Xml.child(it,"prstGeom")?.getAttribute("prst") ?: "rect"}
            if(geometry !in listOf("rect","roundRect","ellipse")) omitted++
            val fill=pr.let {Xml.child(it,"solidFill")?.let {f->Xml.child(f,"srgbClr")?.getAttribute("val")?.let {c->runCatching {Color.parseColor("#$c")}.getOrNull()}}}
            val builder=SpannableStringBuilder()
            Xml.elements(shape,"p").forEachIndexed {pIndex,paragraph ->
                if(pIndex>0) builder.append('\n')
                Xml.elements(paragraph,"t").forEach {t ->
                    val fieldId=section.id+"#"+allTexts.indexOf(t);val props=s.edits.styles[fieldId] ?: emptyMap();val runPr=Xml.child(t.parentNode as Element,"rPr")
                    val start=builder.length;builder.append(s.edits.values[fieldId] ?: t.textContent);val end=builder.length
                    fun span(value: Any) {builder.setSpan(value,start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)}
                    val font=props["font"] ?: runPr?.let {Xml.child(it,"ea")?.getAttribute("typeface")?.takeIf {v->v.isNotBlank()} ?: Xml.child(it,"latin")?.getAttribute("typeface")}
                    val pt=props["size"]?.toFloatOrNull() ?: runPr?.getAttribute("sz")?.toFloatOrNull()?.div(100) ?: 24f
                    span(AbsoluteSizeSpan(pt.toInt().coerceIn(1,200)))
                    val bold=(props["bold"] ?: runPr?.getAttribute("b")) in listOf("true","1");val italic=(props["italic"] ?: runPr?.getAttribute("i")) in listOf("true","1")
                    span(StyleSpan(if(bold && italic) Typeface.BOLD_ITALIC else if(bold) Typeface.BOLD else if(italic) Typeface.ITALIC else Typeface.NORMAL))
                    if(!font.isNullOrBlank()) span(TypefaceSpan(font))
                    val color=props["color"] ?: runPr?.let {Xml.child(it,"solidFill")?.let {f->Xml.child(f,"srgbClr")?.getAttribute("val")}}
                    if(color!=null) runCatching {span(ForegroundColorSpan(Color.parseColor("#"+color.removePrefix("#"))))}
                    if((props["underline"] ?: runPr?.getAttribute("u")) in listOf("true","sng")) span(UnderlineSpan())
                }
            }
            val align=Xml.elements(shape,"pPr").firstOrNull()?.getAttribute("algn")
            var bitmap: Bitmap?=null
            if(shape.localName=="pic") {
                val rid=Xml.elements(shape,"blip").firstOrNull()?.getAttributeNS(PackageDocument.R,"embed")
                rels[rid]?.let {target->
                    val normalized=java.net.URI("/"+section.id.substringBeforeLast('/')+"/"+target).normalize().path.removePrefix("/")
                    val entry=zip.getEntry(normalized)
                    if(entry!=null && entry.size in 1..8*1024*1024) {
                        val bytes=zip.getInputStream(entry).use {it.readBytes()};val options=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
                        options.inJustDecodeBounds=false;options.inSampleSize=1;while(options.outWidth.toLong()*options.outHeight/options.inSampleSize/options.inSampleSize>2_000_000) options.inSampleSize*=2
                        bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
                    } else omitted++
                }
            }
            SlideShape(Xml.elements(shape,"cNvPr").first().getAttribute("id"),x,y,w,h,(transform.getAttribute("rot").toFloatOrNull() ?: 0f)/60000,geometry,fill,builder,when(align) {"ctr"->Layout.Alignment.ALIGN_CENTER;"r"->Layout.Alignment.ALIGN_OPPOSITE;else->Layout.Alignment.ALIGN_NORMAL},bitmap)
        }
        SlideScene(width,height,shapes,omitted)
    }
    fun draw(canvas: Canvas,scene: SlideScene,width: Float,height: Float) {
        canvas.save();canvas.clipRect(0f,0f,width,height);canvas.scale(width/scene.width,height/scene.height)
        canvas.drawRect(0f,0f,scene.width,scene.height,Paint().apply {color=Color.WHITE})
        scene.shapes.forEach {s->
            canvas.save();canvas.rotate(s.rotation,s.x+s.width/2,s.y+s.height/2)
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=s.fill ?: Color.TRANSPARENT}
            if(s.fill!=null) when(s.geometry) {"ellipse"->canvas.drawOval(s.x,s.y,s.x+s.width,s.y+s.height,paint);"roundRect"->canvas.drawRoundRect(s.x,s.y,s.x+s.width,s.y+s.height,8f,8f,paint);else->canvas.drawRect(s.x,s.y,s.x+s.width,s.y+s.height,paint)}
            s.image?.let {canvas.drawBitmap(it,null,RectF(s.x,s.y,s.x+s.width,s.y+s.height),Paint(Paint.ANTI_ALIAS_FLAG))}
            if(s.text.isNotEmpty()) {
                val textPaint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply {textSize=24f;color=Color.BLACK}
                val layout=StaticLayout.Builder.obtain(s.text,0,s.text.length,textPaint,(s.width-14).toInt().coerceAtLeast(1)).setAlignment(s.alignment).setIncludePad(false).build()
                canvas.clipRect(s.x,s.y,s.x+s.width,s.y+s.height);canvas.translate(s.x+7,s.y+4);layout.draw(canvas)
            };canvas.restore()
        };canvas.restore()
    }
    fun export(s: Session,destination: File) {
        val pdf=android.graphics.pdf.PdfDocument()
        try {s.sections.forEachIndexed {i,section ->val scene=load(s,section);try {val page=pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(scene.width.toInt(),scene.height.toInt(),i+1).create());draw(page.canvas,scene,scene.width,scene.height);s.edits.strokes.filter {it.anchor==section.id}.forEach {drawStroke(page.canvas,it,scene.width,scene.height)};pdf.finishPage(page)} finally {scene.recycle()}};destination.outputStream().use {pdf.writeTo(it)}} finally {pdf.close()}
    }
}
