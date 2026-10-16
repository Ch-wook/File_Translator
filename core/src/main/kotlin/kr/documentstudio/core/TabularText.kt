package kr.documentstudio.core

/** Excel-compatible quoted tab-separated clipboard text, including multiline cells. */
object TabularText {
    fun encode(rows: List<List<String>>): String = rows.joinToString("\r\n") {row->row.joinToString("\t") {value->if(value.any {it in "\t\r\n\""}) "\""+value.replace("\"","\"\"")+"\"" else value}}
    fun decode(text: String): List<List<String>> {
        require(text.length<=2_000_000);val rows=mutableListOf<List<String>>();val row=mutableListOf<String>();val cell=StringBuilder();var quoted=false;var closed=false;var i=0;var count=0
        fun finishCell() {require(++count<=10000) {"붙여넣기는 최대 10,000셀까지 지원합니다."};require(cell.length<=32767);row.add(cell.toString());cell.setLength(0);closed=false}
        fun finishRow() {finishCell();rows.add(row.toList());row.clear()}
        while(i<text.length) {val ch=text[i]
            if(quoted) {if(ch=='"') {if(i+1<text.length && text[i+1]=='"') {cell.append('"');i++} else {quoted=false;closed=true}} else cell.append(ch)}
            else when(ch) {'"'->{require(cell.isEmpty() && !closed) {"따옴표가 올바르지 않은 표 텍스트입니다."};quoted=true};'\t'->finishCell();'\r','\n'->{finishRow();if(ch=='\r' && i+1<text.length && text[i+1]=='\n') i++};else->{require(!closed) {"닫는 따옴표 뒤에는 탭 또는 줄바꿈이 필요합니다."};cell.append(ch)}}
            require(cell.length<=32767);i++
        }
        require(!quoted) {"닫히지 않은 따옴표가 있습니다."}
        if(row.isNotEmpty() || cell.isNotEmpty() || closed || rows.isEmpty() || text.endsWith('\t')) finishRow()
        return rows
    }
}
