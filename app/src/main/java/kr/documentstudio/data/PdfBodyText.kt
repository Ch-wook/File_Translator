package kr.documentstudio.data

import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType3Font
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

data class PdfBodyRun(val id: Int,val text: String,val font: String,val bounds: PdfTextBounds?=null)
data class PdfTextBounds(val x: Float,val y: Float,val width: Float,val height: Float,val size: Float)

/** Edits horizontal page-level Tj/TJ strings. Forms, tagged text and vertical/type-3 fonts stay intact. */
object PdfBodyText {
    private data class Affine(val a: Float=1f,val b: Float=0f,val c: Float=0f,val d: Float=1f,val e: Float=0f,val f: Float=0f) {
        fun times(o: Affine)=Affine(a*o.a+c*o.b,b*o.a+d*o.b,a*o.c+c*o.d,b*o.c+d*o.d,a*o.e+c*o.f+e,b*o.e+d*o.f+f)
        fun point(x: Float,y: Float)=(a*x+c*y+e) to (b*x+d*y+f)
    }
    private data class State(val font: PDFont?=null,val size: Float=0f,val character: Float=0f,val word: Float=0f,val rendering: Int=0,
        val matrix: Affine=Affine(),val horizontal: Float=1f,val leading: Float=0f,val rise: Float=0f,val clipped: Boolean=false)
    private data class Metrics(val text: String,val width: Float,val glyphs: Int,val spaces: Int)
    fun runs(file: File,page: Int): List<PdfBodyRun> = PDDocument.load(file).use {doc->val result=mutableListOf<PdfBodyRun>();var characters=0;walk(doc.getPage(page)) {id,value,state->if(result.size<3000 && value.text.isNotBlank()) {characters+=value.text.length;require(characters<=2_000_000) {"본문 목록의 텍스트 크기 제한을 초과했습니다."};result.add(PdfBodyRun(id,value.text,state.font?.name ?: "폰트",bounds(doc.getPage(page),value,state)))};null};result}
    private fun bounds(page: PDPage,value: Metrics,state: State): PdfTextBounds? {
        if(state.clipped) return null
        val crop=page.cropBox;val rotation=((page.rotation%360)+360)%360
        val width=if(rotation in listOf(90,270)) crop.height else crop.width
        val height=if(rotation in listOf(90,270)) crop.width else crop.height
        fun screen(x: Float,y: Float): Pair<Float,Float> {
            val (px,py)=state.matrix.point(x,y);val u=px-crop.lowerLeftX;val v=py-crop.lowerLeftY
            return when(rotation) {90->v to u;180->crop.width-u to v;270->crop.height-v to crop.width-u;else->u to crop.height-v}
        }
        val baseline=screen(0f,state.rise);val along=screen(state.horizontal,state.rise)
        if(along.first<=baseline.first || kotlin.math.abs(along.second-baseline.second)>0.01f) return null
        val descriptor=state.font?.fontDescriptor
        val ascent=(descriptor?.ascent?.takeIf {it.isFinite() && it>0} ?: 800f)/1000f
        val descent=(descriptor?.descent?.takeIf {it.isFinite() && it<0} ?: -200f)/1000f
        val advance=(value.width/1000f*state.size+value.glyphs*state.character+value.spaces*state.word)*state.horizontal
        val points=listOf(screen(0f,state.rise+descent*state.size),screen(advance,state.rise+descent*state.size),screen(0f,state.rise+ascent*state.size),screen(advance,state.rise+ascent*state.size))
        val left=points.minOf {it.first};val top=points.minOf {it.second};val right=points.maxOf {it.first};val bottom=points.maxOf {it.second}
        if(!listOf(left,top,right,bottom).all {it.isFinite()} || right<=left || bottom<=top) return null
        return PdfTextBounds(left/width*100,top/height*100,(right-left)/width*100,(bottom-top)/height*100,kotlin.math.hypot(state.matrix.c,state.matrix.d)*state.size)
    }
    fun replace(doc: PDDocument,pageIndex: Int,id: Int,expected: String,text: String) {
        require(text.length<=32767 && text.none {it in "\r\n"}) {"본문 문자열은 줄바꿈 없이 32,767자까지 지원합니다."}
        var found=false;val page=doc.getPage(pageIndex)
        val tokens=walk(page) {index,value,state->if(index!=id) null else {
            require(value.text==expected) {"본문이 변경되었습니다. 편집 목록을 다시 여세요."}
            val encoded=try {state.font!!.encode(text)} catch(e: Exception) {throw IllegalArgumentException("원래 폰트로 이 문자를 표현할 수 없습니다. 원본 본문을 보존합니다.",e)}
            val next=metrics(state.font!!,encoded)
            val adjustment=next.width-value.width+1000f/state.size*((next.glyphs-value.glyphs)*state.character+(next.spaces-value.spaces)*state.word)
            require(adjustment.isFinite() && kotlin.math.abs(adjustment)<=10_000_000f) {"문자 위치 보정 범위를 초과했습니다."}
            found=true;COSString(encoded) to adjustment
        }}
        require(found) {"편집 가능한 본문 문자열을 찾지 못했습니다."}
        val stream=PDStream(doc);stream.createOutputStream(COSName.FLATE_DECODE).use {ContentStreamWriter(it).writeTokens(tokens)};page.setContents(stream)
    }
    private fun metrics(font: PDFont,bytes: ByteArray): Metrics {
        val input=ByteArrayInputStream(bytes);val text=StringBuilder();var width=0f;var glyphs=0;var spaces=0
        while(input.available()>0) {val before=input.available();val code=font.readCode(input);require(input.available()<before && ++glyphs<=32767);val unicode=font.toUnicode(code) ?: error("폰트 문자 매핑을 읽을 수 없습니다.");text.append(unicode);require(text.length<=32767);width+=font.getWidth(code);if(code==32 && before-input.available()==1) spaces++}
        require(width.isFinite());return Metrics(text.toString(),width,glyphs,spaces)
    }
    private fun walk(page: PDPage,edit: (Int,Metrics,State)->Pair<COSString,Float>?): List<Any> {
        val data=ByteArrayOutputStream();page.contents.use {input->val buffer=ByteArray(65536);while(true) {val n=input.read(buffer);if(n<0) break;require(data.size()+n<=4*1024*1024) {"본문 직접 편집은 해제된 페이지 스트림 4 MiB까지 지원합니다."};data.write(buffer,0,n)}}
        val parser=PDFStreamParser(data.toByteArray());val output=mutableListOf<Any>();val operands=mutableListOf<Any>();val stack=ArrayDeque<State>();var state=State();var marked=0;var id=0;var count=0;var textMatrix=Affine();var lineMatrix=Affine();var positionKnown=true
        fun editable()=state.font!=null && state.font !is PDType3Font && !state.font!!.isVertical && state.size.isFinite() && kotlin.math.abs(state.size)>0.001f && state.rendering in 0..2 && marked==0
        fun number(i: Int,default: Float=0f)=(operands.getOrNull(i) as? COSNumber)?.floatValue() ?: default
        fun nextLine() {lineMatrix=lineMatrix.times(Affine(f=-state.leading));textMatrix=lineMatrix;positionKnown=true}
        fun advance(bytes: ByteArray) {
            val font=state.font ?: run {positionKnown=false;return}
            val input=ByteArrayInputStream(bytes);var width=0f;var glyphs=0;var spaces=0
            try {while(input.available()>0) {val before=input.available();val code=font.readCode(input);require(input.available()<before && ++glyphs<=32767);width+=font.getWidth(code);if(code==32 && before-input.available()==1) spaces++}
                textMatrix=textMatrix.times(Affine(e=(width/1000f*state.size+glyphs*state.character+spaces*state.word)*state.horizontal))
            } catch(_: Exception) {positionKnown=false}
        }
        fun callbackState()=state.copy(matrix=state.matrix.times(textMatrix),clipped=state.clipped || !positionKnown)
        while(true) {val token=parser.parseNextToken() ?: break;require(++count<=100000) {"페이지 연산자 수 제한을 초과했습니다."}
            if(token !is Operator) {operands.add(token);continue}
            val name=token.name
            when(name) {
                "q" -> {require(stack.size<100);stack.addLast(state)}
                "Q" -> if(stack.isNotEmpty()) state=stack.removeLast()
                "cm" -> if(operands.size==6) state=state.copy(matrix=state.matrix.times(Affine(number(0),number(1),number(2),number(3),number(4),number(5))))
                "W","W*" -> state=state.copy(clipped=true)
                "BT" -> {textMatrix=Affine();lineMatrix=Affine();positionKnown=true}
                "Tm" -> {textMatrix=Affine(number(0),number(1),number(2),number(3),number(4),number(5));lineMatrix=textMatrix;positionKnown=true}
                "Td","TD" -> {if(name=="TD") state=state.copy(leading=-number(1));lineMatrix=lineMatrix.times(Affine(e=number(0),f=number(1)));textMatrix=lineMatrix;positionKnown=true}
                "TL" -> state=state.copy(leading=number(0))
                "Tz" -> state=state.copy(horizontal=number(0,100f)/100f)
                "Ts" -> state=state.copy(rise=number(0))
                "T*" -> nextLine()
                "Tf" -> {val fontName=operands.getOrNull(0) as? COSName;val size=operands.getOrNull(1) as? COSNumber;state=state.copy(font=fontName?.let {runCatching {page.resources?.getFont(it)}.getOrNull()},size=size?.floatValue() ?: 0f)}
                "Tc" -> state=state.copy(character=(operands.firstOrNull() as? COSNumber)?.floatValue() ?: 0f)
                "Tw" -> state=state.copy(word=(operands.firstOrNull() as? COSNumber)?.floatValue() ?: 0f)
                "Tr" -> state=state.copy(rendering=(operands.firstOrNull() as? COSNumber)?.intValue() ?: 0)
                "gs" -> state=state.copy(font=null) // ExtGState can replace the text font; require a later explicit Tf.
                "BDC","BMC" -> marked++
                "EMC" -> marked=(marked-1).coerceAtLeast(0)
                "\"" -> {state=state.copy(word=(operands.getOrNull(0) as? COSNumber)?.floatValue() ?: state.word,character=(operands.getOrNull(1) as? COSNumber)?.floatValue() ?: state.character)}
            }
            var operator=token
            if(name=="Tj" && editable()) {
                val string=operands.lastOrNull() as? COSString
                if(string!=null) {val value=runCatching {metrics(state.font!!,string.bytes)}.getOrNull();val replacement=if(value!=null) edit(id,value,callbackState()) else null;id++;advance(string.bytes)
                    if(replacement!=null) {operands.clear();operands.add(COSArray().apply {add(replacement.first);add(COSFloat(replacement.second))});operator=Operator.getOperator("TJ")}}
            } else if(name=="TJ" && editable()) {
                val array=operands.lastOrNull() as? COSArray
                if(array!=null) {val next=COSArray();for(index in 0 until array.size()) {val item=array.get(index);if(item is COSString) {val value=runCatching {metrics(state.font!!,item.bytes)}.getOrNull();val replacement=if(value!=null) edit(id,value,callbackState()) else null;id++;advance(item.bytes);if(replacement!=null) {next.add(replacement.first);next.add(COSFloat(replacement.second))} else next.add(item)} else {next.add(item);if(item is COSNumber) textMatrix=textMatrix.times(Affine(e=-item.floatValue()/1000f*state.size*state.horizontal))}};operands[operands.lastIndex]=next}
            }
            if(name=="Tj" && !editable()) (operands.lastOrNull() as? COSString)?.let {advance(it.bytes)}
            if(name=="TJ" && !editable()) (operands.lastOrNull() as? COSArray)?.let {array->for(i in 0 until array.size()) {val item=array.get(i);if(item is COSString) advance(item.bytes) else if(item is COSNumber) textMatrix=textMatrix.times(Affine(e=-item.floatValue()/1000f*state.size*state.horizontal))}}
            if(name=="'" || name=="\"") {nextLine();(operands.lastOrNull() as? COSString)?.let {advance(it.bytes)}}
            output.addAll(operands);output.add(operator);operands.clear()
        }
        require(operands.isEmpty()) {"완전하지 않은 페이지 내용 스트림입니다."};return output
    }
}
