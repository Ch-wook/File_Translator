package kr.documentstudio.data

import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.json.JSONObject

/** Pending direct-page edits use the same persisted, undoable journal as text/forms/ink. */
object PdfTextEdits {
    private val address=Regex("^pdf:text:(\\d+)#(\\d+)$")
    fun key(page: Int,id: Int)="pdf:text:$page#$id"
    fun value(previous: String?,expected: String,text: String): String {
        val original=previous?.let {JSONObject(it).getString("expected")} ?: expected
        return JSONObject().put("expected",original).put("text",text).toString()
    }
    fun apply(document: PDDocument,values: Map<String,String>) {
        values.forEach {(key,value)->
            val match=address.matchEntire(key) ?: return@forEach
            require(value.length<=262144) {"PDF 본문 수정 크기를 초과했습니다."}
            val json=JSONObject(value)
            PdfBodyText.replace(document,match.groupValues[1].toInt(),match.groupValues[2].toInt(),json.getString("expected"),json.getString("text"))
        }
    }
}
