package kr.documentstudio.core

data class TextEdit(val text: String,val first: Int,val last: Int)
object MarkdownEditing {
    fun apply(source: String,start: Int,end: Int,command: String): TextEdit {
        val a=minOf(start,end).coerceIn(0,source.length);val b=maxOf(start,end).coerceIn(a,source.length)
        val selected=source.substring(a,b)
        fun insert(prefix: String,suffix: String,placeholder: String): TextEdit {
            val value=selected.ifEmpty {placeholder};val replacement=prefix+value+suffix
            return TextEdit(source.replaceRange(a,b,replacement),a+prefix.length,a+prefix.length+value.length)
        }
        return when(command) {
            "bold" -> insert("**","**","굵은 글자")
            "italic" -> insert("*","*","기울임")
            "link" -> insert("[","](https://example.com)","링크 이름")
            "code" -> insert("`","`","코드")
            "block" -> insert("```\n","\n```","코드")
            "table" -> insert("\n| 제목 | 제목 |\n| --- | --- |\n| "," | 내용 |\n","내용")
            "heading","list","number","task","quote" -> {
                val prefix=when(command) {"heading"->"# ";"list"->"- ";"number"->"1. ";"task"->"- [ ] ";else->"> "}
                val first=source.lastIndexOf('\n',(a-1).coerceAtLeast(-1))+1
                val last=source.indexOf('\n',if(b>a && source[b-1]=='\n') b-1 else b).let {if(it<0) source.length else it}
                val body=source.substring(first,last).split('\n').joinToString("\n") {prefix+it}
                TextEdit(source.replaceRange(first,last,body),first,last+(body.length-(last-first)))
            }
            else -> error("지원하지 않는 Markdown 명령")
        }.also {require(it.text.toByteArray().size<=2*1024*1024)}
    }
}
