package kr.documentstudio.core

import org.w3c.dom.Element

/** Split only selected text, keeping drawings, controls, links and run properties in order. */
internal object TextRangeFormatting {
    fun apply(paragraph: Element,start: Int,end: Int,style: (Element)->Unit) {
        val texts=Xml.elements(paragraph,"t").filter {t->TextRuns.isText(t) &&
            generateSequence(t.parentNode) {it.parentNode}.filterIsInstance<Element>().firstOrNull {it.localName=="p"}===paragraph}
        val full=texts.joinToString("") {TextRuns.read(it)}
        require(start in 0..full.length && end in start..full.length) {"서식을 바꿀 텍스트를 선택하세요."}
        require((start==full.length || !full[start].isLowSurrogate()) && (end==full.length || !full[end].isLowSurrogate()))
        var position=0
        var emptyAtCaret: Element?=null
        var containingCaret: Element?=null
        texts.forEach {text->
            val length=TextRuns.read(text).length
            if(length==0 && position==start) emptyAtCaret=text
            if(containingCaret==null && start>=position && start<position+length) containingCaret=text
            position+=length
        }
        val caretTarget=emptyAtCaret ?: containingCaret ?: texts.lastOrNull()
        var offset=0
        texts.forEach {text->
            val value=TextRuns.read(text);val left=(start-offset).coerceIn(0,value.length);val right=(end-offset).coerceIn(0,value.length)
            val caret=start==end && text===caretTarget
            offset+=value.length
            if(left>=right && !caret) return@forEach
            val run=text.parentNode as Element;val parent=run.parentNode;val doc=run.ownerDocument
            TextRuns.clearControls(text)
            val properties=Xml.child(run,"rPr")
            val children=TextRuns.children(run).filter {it!==properties}
            children.forEach {child->
                if(child===text) {
                    listOf(Triple(0,left,false),Triple(left,right,true),Triple(right,value.length,false)).filter {it.first<it.second || (caret && it.third)}.forEach {(a,b,selected)->
                        val fragment=run.cloneNode(false) as Element
                        properties?.let {fragment.appendChild(it.cloneNode(true))}
                        val t=text.cloneNode(false) as Element;fragment.appendChild(t)
                        parent.insertBefore(fragment,run)
                        TextRuns.write(t,value.substring(a,b))
                        if(selected) style(t)
                    }
                } else {
                    val fragment=run.cloneNode(false) as Element
                    properties?.let {fragment.appendChild(it.cloneNode(true))}
                    fragment.appendChild(child);parent.insertBefore(fragment,run)
                }
            }
            parent.removeChild(run)
        }
        if(paragraph.namespaceURI==TextRuns.HP) Xml.elements(paragraph,"linesegarray").forEach {it.parentNode.removeChild(it)}
    }
}
