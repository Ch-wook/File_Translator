package kr.documentstudio.core

import org.w3c.dom.Document
import org.w3c.dom.Element

/** OWPML shape references. No pagination or layout engine is implied. */
object HangulFormatting {
    private fun Document.element(ns: String,name: String,configure: Element.()->Unit = {})=createElementNS(ns,name).apply(configure)
    private const val HH="http://www.hancom.co.kr/hwpml/2011/head"
    val paragraphKeys=setOf("align","lineSpacing","beforePt","afterPt","indentMm","firstLineMm","headingType","headingLevel","numberingId")
    fun selectStyle(header: Document,run: Element,props: Map<String,String>) {
        props["styleId"]?.let {id->
            val style=requireNotNull(Xml.elements(header,"style").firstOrNull {it.getAttribute("id")==id}) {"해당 한글 스타일 ID가 없습니다."}
            require(style.getAttribute("lockForm")!="1") {"잠긴 스타일입니다."}
            val paragraph=run.parentNode as Element;paragraph.setAttribute("styleIDRef",id)
            if(style.getAttribute("type")=="PARA") paragraph.setAttribute("paraPrIDRef",style.getAttribute("paraPrIDRef"))
            (0 until paragraph.childNodes.length).mapNotNull {paragraph.childNodes.item(it) as? Element}.filter {it.localName=="run"}.forEach {it.setAttribute("charPrIDRef",style.getAttribute("charPrIDRef"))}
        }
    }
    fun character(char: Element,props: Map<String,String>) {
        listOf(Triple("tracking","spacing",-50..50),Triple("scale","ratio",50..200),Triple("offset","offset",-100..100)).forEach {(key,name,range)->props[key]?.let {value->
            val number=value.toInt();require(number in range) {"$key 범위: ${range.first}~${range.last}"}
            val e=Xml.child(char,name) ?: error("한글 글자 속성 누락: $name")
            listOf("hangul","latin","hanja","japanese","other","symbol","user").forEach {e.setAttribute(it,number.toString())}
        }}
    }
    fun paragraph(header: Document,para: Element,props: Map<String,String>) {
        listOf(Triple("beforePt","prev",100.0),Triple("afterPt","next",100.0),Triple("indentMm","left",7200.0/25.4),Triple("firstLineMm","intent",7200.0/25.4)).forEach {(key,name,factor)->props[key]?.let {value->
            val n=value.toDouble();require(n.isFinite() && n in (if(key=="firstLineMm") -60.0 else 0.0)..(if(key.endsWith("Pt")) 200.0 else 60.0))
            val margins=Xml.elements(para,"margin");require(margins.isNotEmpty())
            margins.forEach {margin->val e=Xml.child(margin,name) ?: error("한글 문단 여백 속성 누락");e.setAttribute("value",(n*factor).toInt().toString());e.setAttribute("unit","HWPUNIT")}
        }}
        props["headingType"]?.let {type->
            require(type in setOf("NONE","NUMBER","BULLET"))
            val heading=Xml.child(para,"heading") ?: error("문단 머리 속성 누락")
            val level=(props["headingLevel"] ?: "1").toInt();require(level in 1..7)
            val id=if(type=="NONE") "0" else props["numberingId"]?.also {id->require(Xml.elements(header,if(type=="NUMBER") "numbering" else "bullet").any {it.getAttribute("id")==id}) {"번호/글머리표 ID가 없습니다."}} ?: createHeading(header,type)
            heading.setAttribute("type",type);heading.setAttribute("idRef",id);heading.setAttribute("level",(level-1).toString())
        }
    }
    private fun createHeading(header: Document,type: String): String {
        val listName=if(type=="NUMBER") "numberings" else "bullets";val name=if(type=="NUMBER") "numbering" else "bullet"
        val refs=Xml.elements(header,"refList").single()
        val list=Xml.child(refs,listName) ?: header.element(HH,"hh:$listName").also {e->
            val order=listOf("fontfaces","borderFills","charProperties","tabProperties","numberings","bullets","paraProperties","styles","memoProperties","trackChanges","trackChangeAuthors")
            val following=(0 until refs.childNodes.length).mapNotNull {refs.childNodes.item(it) as? Element}.firstOrNull {order.indexOf(it.localName)>order.indexOf(listName)}
            refs.insertBefore(e,following)
        }
        require(Xml.elements(list,name).size<10000)
        val id=((Xml.elements(list,name).maxOfOrNull {it.getAttribute("id").toInt()} ?: 0)+1).toString()
        list.appendChild(header.element(HH,"hh:$name") {
            setAttribute("id",id)
            if(type=="NUMBER") setAttribute("start","1") else {setAttribute("char","•");setAttribute("useImage","0")}
            for(level in 1..if(type=="NUMBER") 7 else 1) appendChild(header.element(HH,"hh:paraHead") {
                setAttribute("level",level.toString());setAttribute("align","LEFT");setAttribute("useInstWidth","1");setAttribute("autoIndent","1");setAttribute("widthAdjust","0");setAttribute("textOffsetType","PERCENT");setAttribute("textOffset","50");setAttribute("numFormat","DIGIT");setAttribute("charPrIDRef","4294967295");setAttribute("checkable","0")
                if(type=="NUMBER") {setAttribute("start","1");textContent="^$level."}
            })
        });list.setAttribute("itemCnt",Xml.elements(list,name).size.toString());return id
    }
    fun read(header: Document,text: Element): Map<String,String> = buildMap {
        val run=text.parentNode as Element;val p=run.parentNode as? Element ?: return@buildMap
        val char=Xml.elements(header,"charPr").firstOrNull {it.getAttribute("id")==run.getAttribute("charPrIDRef")}
        if(char!=null) {
            listOf("tracking" to "spacing","scale" to "ratio","offset" to "offset").forEach {(key,name)->Xml.child(char,name)?.getAttribute("hangul")?.takeIf {it.isNotBlank()}?.let {put(key,it)}}
            Xml.child(char,"strikeout")?.let {put("strike",(it.getAttribute("shape")!="NONE").toString())}
            val fid=Xml.child(char,"fontRef")?.getAttribute("hangul")
            Xml.elements(header,"fontface").firstOrNull {it.getAttribute("lang")=="HANGUL"}?.let {face->Xml.elements(face,"font").firstOrNull {it.getAttribute("id")==fid}?.let {put("font",it.getAttribute("face"))}}
        }
        put("styleId",p.getAttribute("styleIDRef"))
        val para=Xml.elements(header,"paraPr").firstOrNull {it.getAttribute("id")==p.getAttribute("paraPrIDRef")}
        if(para!=null) {
            Xml.child(para,"align")?.let {put("align",when(it.getAttribute("horizontal")) {"JUSTIFY"->"both";else->it.getAttribute("horizontal").lowercase()})}
            Xml.elements(para,"lineSpacing").firstOrNull()?.takeIf {it.getAttribute("type")=="PERCENT"}?.getAttribute("value")?.toDoubleOrNull()?.let {put("lineSpacing",(it/100).toString())}
            Xml.elements(para,"margin").firstOrNull()?.let {m->listOf(Triple("beforePt","prev",100.0),Triple("afterPt","next",100.0),Triple("indentMm","left",7200.0/25.4),Triple("firstLineMm","intent",7200.0/25.4)).forEach {(key,name,factor)->Xml.child(m,name)?.getAttribute("value")?.toDoubleOrNull()?.let {put(key,(it/factor).toString())}}}
            Xml.child(para,"heading")?.let {put("headingType",it.getAttribute("type"));put("headingLevel",((it.getAttribute("level").toIntOrNull() ?: 0)+1).toString());if(it.getAttribute("type")!="NONE") put("numberingId",it.getAttribute("idRef"))}
        }
    }
}
