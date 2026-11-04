package kr.documentstudio.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kr.documentstudio.core.*
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.commonmark.renderer.html.HtmlRenderer
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension

object PortableConversions {
    fun targets(format: Format): List<String> = when(format) {
        Format.PDF -> listOf("png.zip","jpg.zip","txt")
        Format.MD -> listOf("pdf","html","txt")
        Format.TXT -> listOf("pdf","html","md")
        Format.HWP -> listOf("pdf","hwpx")
        else -> if(format in DocumentRepository.visualFormats) listOf("pdf") else emptyList()
    }
    fun pages(spec: String,count: Int): List<Int> {
        if(spec.isBlank())return (0 until count).toList()
        val values=linkedSetOf<Int>()
        for(term in spec.split(',')){
            val parts=term.trim().split('-');require(parts.size in 1..2) {"페이지 범위를 확인하세요."}
            val a=parts[0].toIntOrNull() ?: error("페이지 번호를 입력하세요.")
            val b=if(parts.size==2)parts[1].toIntOrNull() ?: error("페이지 번호를 입력하세요.") else a
            require(a in 1..count && b in a..count) {"페이지 범위를 확인하세요."}
            for(p in a..b)values.add(p-1)
        }
        return values.sorted()
    }
    fun convert(context: Context,s: Session,target: String,dpi: Int=144,pageSpec: String="",fontSize: Float=12f,check: ()->Unit={},progress: (String)->Unit={}): File {
        require(target in targets(s.format));require(dpi in 72..216 && fontSize in 8f..32f)
        val dir=File(context.cacheDir,"conversions").apply {mkdirs()}
        val output=File(dir,UUID.randomUUID().toString()+"."+target)
        val original=ConversionFiles.digest(s.base)
        try {
            check();progress("파일 변환 중")
            when {
                s.format==Format.PDF && target.endsWith(".zip") -> {
                    val source=File(dir,UUID.randomUUID().toString()+".pdf")
                    try {PdfEngine.write(s.base,source,s.edits.strokes,s.pdf!!.archival,s.edits.values,s.edits.layers)
                        val selected=pages(pageSpec,PdfEngine.inspect(source).pages);require(selected.size<=300) {"최대 300쪽씩 변환하세요."}
                        ZipOutputStream(output.outputStream().buffered()).use {zip->for((index,page) in selected.withIndex()){
                            check();progress("페이지 변환 중 · ${index+1} / ${selected.size}")
                            val size=PdfEngine.displaySize(source,page);val w=(size.first*dpi/72).toInt();val h=(size.second*dpi/72).toInt()
                            require(w>0 && h>0 && w.toLong()*h<=16000000) {"페이지가 너무 큽니다. 해상도를 낮추세요."}
                            val bitmap=PdfEngine.renderAtDpi(source,page,dpi)
                            try {val ext=if(target=="png.zip")"png" else "jpg";zip.putNextEntry(ZipEntry("page-%04d.%s".format(page+1,ext)))
                                require(bitmap.compress(if(ext=="png")Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,92,zip));zip.closeEntry()
                            } finally {bitmap.recycle()}
                        }}
                        java.util.zip.ZipFile(output).use {z->require(z.size()==selected.size);val entries=z.entries();while(entries.hasMoreElements()){check();z.getInputStream(entries.nextElement()).use {input->val bytes=input.readBytes();val options=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true};android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,options);require(options.outWidth>0 && options.outHeight>0)}}}
                    } finally {source.delete()}
                }
                s.format==Format.PDF && target=="txt" -> {
                    val source=File(dir,UUID.randomUUID().toString()+".pdf")
                    val text=try{PdfEngine.write(s.base,source,s.edits.strokes,s.pdf!!.archival,s.edits.values,s.edits.layers);PdfEngine.text(source)}finally{source.delete()}
                    require(text.isNotBlank()) {"텍스트 정보가 없습니다. 스캔 PDF OCR 변환은 아직 지원하지 않습니다."};output.writeText(text,Charsets.UTF_8)
                }
                target=="pdf" && s.format in listOf(Format.TXT,Format.MD) -> {
                    val content=if(s.format==Format.MD)MarkdownRender.render(s.edits.text) else s.edits.text
                    textPdf(content,output,fontSize,check,progress);PdfEngine.inspect(output)
                }
                target=="html" -> {
                    val body=if(s.format==Format.MD)HtmlRenderer.builder().extensions(listOf(TablesExtension.create(),StrikethroughExtension.create())).escapeHtml(true).sanitizeUrls(true).build().render(MarkdownTasks.parse(s.edits.text))
                        else "<pre>"+s.edits.text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")+"</pre>"
                    output.writeText("<!doctype html><html lang=\"ko\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"><title>문서</title><body>"+body+"</body></html>")
                }
                target=="md" -> output.writeText(s.edits.text)
                target=="txt" && s.format==Format.MD -> output.writeText(MarkdownRender.render(s.edits.text).toString())
                else -> error("이 변환은 원본 문서 엔진에서 진행하세요.")
            }
            check();progress("결과 검증 중");require(output.length() in 1..128L*1024*1024)
            require(ConversionFiles.digest(s.base)==original) {"원본 해시가 변경되었습니다."}
            return output
        }catch(e:Exception){output.delete();throw e}
    }
    private fun textPdf(content: CharSequence,file: File,size: Float,check: ()->Unit,progress: (String)->Unit) {
        require(content.length<=2*1024*1024) {"텍스트가 너무 큽니다. 나누어 변환하세요."}
        val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply {textSize=size;color=Color.BLACK}
        val layout=StaticLayout.Builder.obtain(content,0,content.length,paint,511).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(4f,1f).build()
        val pdf=PdfDocument()
        try {
            var line=0;var pageNo=0
            do {check();require(pageNo<1000) {"최대 1000쪽까지 변환합니다."};val top=layout.getLineTop(line)
                var end=line+1;while(end<layout.lineCount && layout.getLineBottom(end)-top<=748)end++
                val page=pdf.startPage(PdfDocument.PageInfo.Builder(595,842,++pageNo).create());page.canvas.drawColor(Color.WHITE)
                page.canvas.save();page.canvas.clipRect(42f,48f,553f,48f+layout.getLineTop(end)-top);page.canvas.translate(42f,48f-top);layout.draw(page.canvas);page.canvas.restore();pdf.finishPage(page)
                progress("페이지 생성 중 · "+pageNo);line=end
            }while(line<layout.lineCount)
            file.outputStream().use {pdf.writeTo(it)}
        }finally{pdf.close()}
    }
}
