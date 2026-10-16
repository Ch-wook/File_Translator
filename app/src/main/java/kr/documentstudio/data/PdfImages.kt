package kr.documentstudio.data

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs

data class PdfImagePlacement(val id: Int,val token: String,val name: String,val x: Float,val y: Float,val width: Float,val height: Float)

/** Edits a page-level image occurrence without changing a shared image resource. */
object PdfImages {
    private data class Affine(val a: Double=1.0,val b: Double=0.0,val c: Double=0.0,val d: Double=1.0,val e: Double=0.0,val f: Double=0.0) {
        fun times(o: Affine)=Affine(a*o.a+c*o.b,b*o.a+d*o.b,a*o.c+c*o.d,b*o.c+d*o.d,a*o.e+c*o.f+e,b*o.e+d*o.f+f)
        fun inverse(): Affine {val det=a*d-b*c;require(det.isFinite() && abs(det)>1e-10) {"이미지 변환 행렬을 역변환할 수 없습니다."};return Affine(d/det,-b/det,-c/det,a/det,(c*f-d*e)/det,(b*e-a*f)/det)}
        fun point(x: Double,y: Double)=(a*x+c*y+e) to (b*x+d*y+f)
        fun values()=listOf(a,b,c,d,e,f)
    }
    private data class State(val matrix: Affine=Affine(),val unsafe: Boolean=false)
    private fun display(page: PDPage): Triple<Affine,Double,Double> {
        val r=page.cropBox;val rotation=((page.rotation%360)+360)%360
        val matrix=when(rotation) {
            90->Affine(0.0,1.0,-1.0,0.0,r.upperRightX.toDouble(),r.lowerLeftY.toDouble())
            180->Affine(-1.0,0.0,0.0,-1.0,r.upperRightX.toDouble(),r.upperRightY.toDouble())
            270->Affine(0.0,-1.0,1.0,0.0,r.lowerLeftX.toDouble(),r.upperRightY.toDouble())
            else->Affine(e=r.lowerLeftX.toDouble(),f=r.lowerLeftY.toDouble())
        }
        return Triple(matrix,if(rotation in listOf(90,270)) r.height.toDouble() else r.width.toDouble(),if(rotation in listOf(90,270)) r.width.toDouble() else r.height.toDouble())
    }
    fun list(file: File,pageIndex: Int): List<PdfImagePlacement> = PDDocument.load(file).use {doc ->
        val results=mutableListOf<PdfImagePlacement>();val page=doc.getPage(pageIndex);val (display,width,height)=display(page)
        walk(page) {id,name,matrix ->
            require(results.size<2000) {"이미지 목록 제한을 초과했습니다."}
            val visible=display.inverse().times(matrix)
            val corners=listOf(visible.point(0.0,0.0),visible.point(1.0,0.0),visible.point(0.0,1.0),visible.point(1.0,1.0))
            val left=corners.minOf {it.first};val right=corners.maxOf {it.first};val bottom=corners.minOf {it.second};val top=corners.maxOf {it.second}
            results.add(PdfImagePlacement(id,fingerprint(name,matrix),name,(100*left/width).toFloat(),(100*(height-top)/height).toFloat(),(100*(right-left)/width).toFloat(),(100*(top-bottom)/height).toFloat()))
            null
        };results
    }
    private fun fingerprint(name: String,matrix: Affine)=name+":"+matrix.values().joinToString(",")
    fun change(doc: PDDocument,pageIndex: Int,id: Int,expected: String,rectangle: List<Float>?) {
        val page=doc.getPage(pageIndex);var found=false
        val desired=rectangle?.let {r ->
            require(r.size==4 && r.all {it.isFinite()})
            val (x,y,w,h)=r.map {it.toDouble()/100}
            require(x>=0 && y>=0 && w>0 && h>0 && x+w<=1.000001 && y+h<=1.000001) {"이미지 위치·크기가 페이지 범위를 벗어납니다."}
            val (display,width,height)=display(page)
            display.times(Affine(a=w*width,d=h*height,e=x*width,f=(1-y-h)*height))
        }
        val tokens=walk(page) {index,name,matrix -> if(index!=id) null else {
            require(fingerprint(name,matrix)==expected) {"이미지가 변경되었습니다. 목록을 다시 여세요."};found=true
            if(desired==null) emptyList() else {
                val transform=matrix.inverse().times(desired)
                require(transform.values().all {it.isFinite() && abs(it)<=1e9}) {"이미지 변환 범위를 초과했습니다."}
                listOf(Operator.getOperator("q"))+transform.values().map {COSFloat(it.toFloat())}+listOf(Operator.getOperator("cm"),COSName.getPDFName(name),Operator.getOperator("Do"),Operator.getOperator("Q"))
            }
        }}
        require(found) {"편집 가능한 이미지가 없습니다. 중첩·클리핑·태그 이미지는 보호합니다."}
        val stream=PDStream(doc);stream.createOutputStream(COSName.FLATE_DECODE).use {ContentStreamWriter(it).writeTokens(tokens)};page.setContents(stream)
    }
    private fun walk(page: PDPage,edit: (Int,String,Affine)->List<Any>?): List<Any> {
        val bytes=ByteArrayOutputStream()
        page.contents.use {input -> val buffer=ByteArray(65536);while(true) {val n=input.read(buffer);if(n<0) break;require(bytes.size()+n<=4*1024*1024) {"이미지 편집은 해제된 페이지 스트림 4 MiB까지 지원합니다."};bytes.write(buffer,0,n)}}
        val parser=PDFStreamParser(bytes.toByteArray());val output=mutableListOf<Any>();val args=mutableListOf<Any>();val stack=ArrayDeque<State>();var state=State();var marked=0;var text=false;var id=0;var count=0
        while(true) {
            val token=parser.parseNextToken() ?: break;require(++count<=100000) {"페이지 연산자 수 제한을 초과했습니다."}
            if(token !is Operator) {args.add(token);continue}
            when(token.name) {
                "q"->{require(stack.size<100);stack.addLast(state)}
                "Q"->{require(stack.isNotEmpty()) {"잘못된 PDF 그래픽 상태입니다."};state=stack.removeLast()}
                "cm"->{require(args.size==6 && args.all {it is COSNumber});val v=args.map {(it as COSNumber).doubleValue()};require(v.all {it.isFinite()});state=state.copy(matrix=state.matrix.times(Affine(v[0],v[1],v[2],v[3],v[4],v[5])))}
                "W","W*","gs"->state=state.copy(unsafe=true)
                "BMC","BDC"->marked++
                "EMC"->{require(marked>0);marked--}
                "BT"->text=true
                "ET"->text=false
            }
            val resource=if(token.name=="Do" && args.size==1) args[0] as? COSName else null
            val image=resource?.let {page.resources?.getXObject(it)} as? PDImageXObject
            val replacement=if(image!=null && !image.isStencil && !state.unsafe && marked==0 && !text && runCatching {state.matrix.inverse()}.isSuccess) edit(id,resource.name,state.matrix) else null
            if(image!=null) id++
            if(replacement!=null) output.addAll(replacement) else {output.addAll(args);output.add(token)}
            args.clear()
        }
        require(args.isEmpty() && stack.isEmpty() && marked==0 && !text) {"완전하지 않은 PDF 내용 스트림입니다."}
        return output
    }
}
