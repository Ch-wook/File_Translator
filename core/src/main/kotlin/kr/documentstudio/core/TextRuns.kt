package kr.documentstudio.core

import org.w3c.dom.Element
import org.w3c.dom.Node

/** Format-native inline breaks; no literal newline is written into an OOXML text node. */
internal object TextRuns {
    const val HP="http://www.hancom.co.kr/hwpml/2011/paragraph"
    fun isText(e: Element)=e.localName=="t" && e.namespaceURI in listOf(PackageDocument.W,PackageDocument.A,HP) &&
        children(e).all {it !is Element || (e.namespaceURI==HP && it.namespaceURI==HP && it.localName in listOf("lineBreak","tab"))}
    fun children(e: Node)=(0 until e.childNodes.length).map {e.childNodes.item(it)}
    private fun control(n: Node): String?=if(n is Element) when {
        n.namespaceURI==PackageDocument.W && n.localName in listOf("br","cr") && n.getAttributeNS(PackageDocument.W,"type") in listOf("","textWrapping")->"\n"
        n.namespaceURI==PackageDocument.W && n.localName=="tab"->"\t"
        else->null
    } else null
    private fun ownedControls(t: Element): List<Node> {
        if(t.namespaceURI!=PackageDocument.W) return emptyList()
        val before=generateSequence(t.previousSibling) {it.previousSibling}.takeWhile {control(it)!=null}.toList().reversed()
        val after=if(generateSequence(t.nextSibling) {it.nextSibling}.none {it is Element && it.localName=="t"})
            generateSequence(t.nextSibling) {it.nextSibling}.takeWhile {control(it)!=null}.toList() else emptyList()
        return before+after
    }
    private fun slideBreaks(t: Element): Pair<List<Node>,List<Node>> {
        if(t.namespaceURI!=PackageDocument.A) return emptyList<Node>() to emptyList()
        fun isBreak(n: Node)=n is Element && n.namespaceURI==PackageDocument.A && n.localName=="br"
        val run=t.parentNode
        val before=generateSequence(run.previousSibling) {it.previousSibling}.takeWhile(::isBreak).toList().reversed()
        val following=generateSequence(run.nextSibling) {it.nextSibling}.toList()
        val after=if(following.filterIsInstance<Element>().none {Xml.elements(it,"t").isNotEmpty()}) following.takeWhile(::isBreak) else emptyList()
        return before to after
    }
    fun read(t: Element): String {
        if(t.namespaceURI==HP) return children(t).joinToString("") {if(it is Element) if(it.localName=="tab") "\t" else "\n" else it.textContent}
        val controls=ownedControls(t)
        val before=controls.takeWhile {it.compareDocumentPosition(t).toInt() and Node.DOCUMENT_POSITION_FOLLOWING.toInt()!=0}.joinToString("") {control(it).orEmpty()}
        val after=controls.drop(before.length).joinToString("") {control(it).orEmpty()}
        val (slideBefore,slideAfter)=slideBreaks(t)
        return "\n".repeat(slideBefore.size)+before+t.textContent+after+"\n".repeat(slideAfter.size)
    }
    fun clearControls(t: Element) {
        val (before,after)=slideBreaks(t)
        (ownedControls(t)+before+after).forEach {it.parentNode.removeChild(it)}
    }
    fun write(t: Element,input: String) {
        val value=input.replace("\r\n","\n").replace('\r','\n')
        val doc=t.ownerDocument;val ns=t.namespaceURI;val prefix=t.prefix ?: if(ns==HP) "hp" else if(ns==PackageDocument.W) "w" else "a"
        clearControls(t)
        t.textContent=""
        t.setAttributeNS("http://www.w3.org/XML/1998/namespace","xml:space","preserve")
        if(ns==HP) {
            var start=0
            value.forEachIndexed {i,c->if(c=='\n' || c=='\t') {
                t.appendChild(doc.createTextNode(value.substring(start,i)))
                t.appendChild(doc.createElementNS(ns,"$prefix:"+if(c=='\n') "lineBreak" else "tab"));start=i+1
            }}
            t.appendChild(doc.createTextNode(value.substring(start)));return
        }
        if(ns==PackageDocument.A) {
            val lines=value.split('\n');t.textContent=lines.first()
            val run=t.parentNode;val paragraph=run.parentNode;var previous: Node=run
            lines.drop(1).forEach {line->
                val br=doc.createElementNS(ns,"$prefix:br");paragraph.insertBefore(br,previous.nextSibling)
                val next=run.cloneNode(false) as Element
                Xml.child(run as Element,"rPr")?.let {next.appendChild(it.cloneNode(true))}
                next.appendChild(doc.createElementNS(ns,"$prefix:t").also {it.textContent=line;it.setAttributeNS("http://www.w3.org/XML/1998/namespace","xml:space","preserve")})
                paragraph.insertBefore(next,br.nextSibling);previous=next
            };return
        }
        val run=t.parentNode;var previous: Node=t;var start=0;var first=true
        value.forEachIndexed {i,c->if(c=='\n' || c=='\t') {
            val part=value.substring(start,i)
            if(first) {t.textContent=part;first=false} else {
                val text=t.cloneNode(false);text.textContent=part;run.insertBefore(text,previous.nextSibling);previous=text
            }
            val br=doc.createElementNS(ns,"$prefix:"+if(c=='\n') "br" else "tab")
            run.insertBefore(br,previous.nextSibling);previous=br;start=i+1
        }}
        if(first) t.textContent=value else {
            val tail=t.cloneNode(false);tail.textContent=value.substring(start);run.insertBefore(tail,previous.nextSibling)
        }
    }
}
