package kr.documentstudio.core

data class TextEdit(val text: String,val first: Int,val last: Int)

/** Changes Markdown source, never an unrelated rich-text model. Ranges use Android's UTF-16 offsets. */
object MarkdownEditing {
    fun apply(source: String,start: Int,end: Int,command: String): TextEdit {
        val a=minOf(start,end).coerceIn(0,source.length)
        val b=maxOf(start,end).coerceIn(a,source.length)
        val selected=source.substring(a,b)
        fun insert(prefix: String,suffix: String,placeholder: String): TextEdit {
            val value=selected.ifEmpty {placeholder}
            return TextEdit(source.replaceRange(a,b,prefix+value+suffix),a+prefix.length,a+prefix.length+value.length)
        }
        fun toggle(marker: String,placeholder: String): TextEdit {
            val n=marker.length
            // Our tools keep the inner text selected. A second tap removes only this delimiter.
            val before=source.substring(0,a).takeLastWhile {it==marker[0]}.length
            val after=source.substring(b).takeWhile {it==marker[0]}.length
            val surrounded=before>=n && after>=n && (marker!="*" || (before%2==1 && after%2==1))
            if(b>a && surrounded)
                return TextEdit(source.removeRange(b,b+n).removeRange(a-n,a),a-n,b-n)
            if(selected.length>2*n && selected.startsWith(marker) && selected.endsWith(marker) &&
                (marker!="*" || !selected.startsWith("**")))
                return TextEdit(source.replaceRange(a,b,selected.substring(n,selected.length-n)),a,b-2*n)
            return insert(marker,marker,placeholder)
        }
        fun lines(): TextEdit {
            val first=source.lastIndexOf('\n',a-1)+1
            val last=source.indexOf('\n',if(b>a && source[b-1]=='\n')b-1 else b).let {if(it<0)source.length else it}
            val original=source.substring(first,last).split('\n')
            val heading=if(command=="heading")1 else command.removePrefix("heading").toIntOrNull()
            val list=Regex("^(\\s*)(?:[-+*] \\[[ xX]] |[-+*] |\\d+[.)] )")
            val kind=when(command) {
                "list"->Regex("^\\s*[-+*] (?!\\[[ xX]] )")
                "number"->Regex("^\\s*\\d+[.)] ")
                "task"->Regex("^\\s*[-+*] \\[[ xX]] ")
                "quote"->Regex("^\\s*> ?")
                else->Regex("^#{${heading}} ")
            }
            val remove=original.filter {it.isNotBlank()}.let {it.isNotEmpty() && it.all(kind::containsMatchIn)}
            var number=0
            val body=original.joinToString("\n") { line->
                if(remove) line.replaceFirst(kind,"")
                else if(line.isBlank() && original.size>1)line
                else if(heading!=null) "#".repeat(heading)+" "+line.replaceFirst(Regex("^#{1,6} +"),"")
                else if(command=="quote") "> "+line
                else {
                    val indent=line.takeWhile {it==' ' || it=='\t'}
                    val content=line.replaceFirst(list,indent)
                    val prefix=when(command) {"number"->"${++number}. ";"task"->"- [ ] ";else->"- "}
                    indent+prefix+content.removePrefix(indent)
                }
            }
            return TextEdit(source.replaceRange(first,last,body),first,first+body.length)
        }
        return when(command) {
            "bold"->toggle("**","굵은 글자")
            "italic"->toggle("*","기울임")
            "strike"->toggle("~~","취소선")
            "link"->insert("[","](https://example.com)","링크 이름")
            "image"->insert("![","](https://example.com/image.png)","이미지 설명")
            "code"->{
                if(a>0 && b<source.length && source[a-1]=='\u0060' && source[b]=='\u0060')toggle("\u0060","코드")
                else {
                    val ticks=Regex("\u0060+").findAll(selected).maxOfOrNull {it.value.length} ?: 0
                    val fence="\u0060".repeat(ticks+1)
                    val pad=if(selected.startsWith('\u0060')||selected.endsWith('\u0060'))" " else ""
                    insert(fence+pad,pad+fence,"코드")
                }
            }
            "block"->{
                val ticks=Regex("\u0060+").findAll(selected).maxOfOrNull {it.value.length} ?: 0
                val fence="\u0060".repeat(maxOf(3,ticks+1))
                insert("$fence\n","\n$fence","코드")
            }
            "table"->insert("\n| 제목 | 제목 |\n| --- | --- |\n| "," | 내용 |\n","내용")
            "rule"->insert("\n\n---\n\n","","")
            "heading","heading1","heading2","heading3","heading4","heading5","heading6","list","number","task","quote"->lines()
            else->throw IllegalArgumentException("지원하지 않는 Markdown 명령")
        }.also {require(it.text.toByteArray(Charsets.UTF_8).size<=2*1024*1024) {"Markdown 파일은 2MiB까지 편집할 수 있습니다."}}
    }
}
