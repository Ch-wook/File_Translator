package kr.documentstudio.core

/** Maps a single paragraph input back to its original runs without flattening their styles. */
object ParagraphEditing {
    fun text(fields: List<Field>,edits: Edits)=fields.joinToString("") {edits.values[it.id] ?: it.text}
    fun fieldAt(fields: List<Field>,edits: Edits,caret: Int): Field? {
        var offset=0
        var empty: Field?=null
        var containing: Field?=null
        fields.forEach {f->
            val length=(edits.values[f.id] ?: f.text).length
            if(length==0 && offset==caret) empty=f
            if(containing==null && caret>=offset && caret<offset+length) containing=f
            offset+=length
        }
        return empty ?: containing ?: fields.lastOrNull()
    }

    fun replace(fields: List<Field>,edits: Edits,value: String): Map<String,String> {
        require(fields.isNotEmpty())
        val old=text(fields,edits)
        if(old==value) return emptyMap()
        var start=0
        while(start<minOf(old.length,value.length) && old[start]==value[start]) start++
        // Kotlin/Compose use UTF-16 offsets. Never cut a surrogate pair at a diff edge.
        if(start>0 && start<old.length && old[start].isLowSurrogate()) start--
        var end=old.length;var nextEnd=value.length
        while(end>start && nextEnd>start && old[end-1]==value[nextEnd-1]) {end--;nextEnd--}
        if(end<old.length && old[end].isLowSurrogate()) {end++;nextEnd++}
        val insert=value.substring(start,nextEnd)
        val texts=fields.map {edits.values[it.id] ?: it.text}
        val owner=fields.indexOf(fieldAt(fields,edits,start))
        var offset=0
        return buildMap {
            fields.forEachIndexed {i,f->
                val source=texts[i];val localStart=(start-offset).coerceIn(0,source.length);val localEnd=(end-offset).coerceIn(localStart,source.length)
                val updated=source.substring(0,localStart)+(if(i==owner) insert else "")+source.substring(localEnd)
                if(source!=updated) put(f.id,updated)
                offset+=source.length
            }
        }
    }
}
