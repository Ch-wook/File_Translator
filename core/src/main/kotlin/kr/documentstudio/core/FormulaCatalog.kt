package kr.documentstudio.core

data class FormulaHelp(val name: String,val signature: String,val description: String,val example: String,val result: String)

/** The catalogue only advertises functions implemented by the local evaluator. */
object FormulaCatalog {
    val functions=listOf(
        FormulaHelp("SUM","SUM(값, …)","숫자의 합계","=SUM(2,3)","5"),
        FormulaHelp("AVERAGE","AVERAGE(값, …)","숫자의 평균","=AVERAGE(2,4)","3"),
        FormulaHelp("COUNT","COUNT(값, …)","숫자가 있는 셀 개수","=COUNT(2,\"문자\",4)","2"),
        FormulaHelp("COUNTIF","COUNTIF(범위, 조건)","조건에 맞는 셀 개수","=COUNTIF(A1:A3,\">1\")","2"),
        FormulaHelp("SUMIF","SUMIF(범위, 조건, 합계범위)","조건에 맞는 숫자 합계","=SUMIF(A1:A3,\">1\",B1:B3)","50"),
        FormulaHelp("IF","IF(조건, 참일 때, 거짓일 때)","조건에 따른 값 선택","=IF(2>1,\"예\",\"아니요\")","예"),
        FormulaHelp("IFS","IFS(조건, 값, …)","처음 참인 조건의 값","=IFS(FALSE,0,TRUE,7)","7"),
        FormulaHelp("VLOOKUP","VLOOKUP(찾을 값, 표 범위, 열 번호, FALSE)","표의 첫 열에서 값 찾기","=VLOOKUP(2,A1:B3,2,FALSE)","20"),
        FormulaHelp("XLOOKUP","XLOOKUP(찾을 값, 검색범위, 반환범위)","범위에서 일치하는 값 찾기","=XLOOKUP(3,A1:A3,B1:B3)","30"),
        FormulaHelp("INDEX","INDEX(범위, 행 번호, 열 번호)","범위의 지정 위치 값","=INDEX(A1:B3,2,2)","20"),
        FormulaHelp("MATCH","MATCH(찾을 값, 범위, 0)","정확히 일치하는 값의 위치","=MATCH(2,A1:A3,0)","2"),
        FormulaHelp("ROUND","ROUND(숫자, 자릿수)","지정 자릿수 반올림","=ROUND(1.256,2)","1.26"),
        FormulaHelp("MAX","MAX(값, …)","가장 큰 숫자","=MAX(2,8,4)","8"),
        FormulaHelp("MIN","MIN(값, …)","가장 작은 숫자","=MIN(2,8,4)","2"),
        FormulaHelp("TEXT","TEXT(숫자, \"표시 형식\")","숫자·날짜·백분율을 문자열로 표시","=TEXT(0.25,\"0%\")","25%"),
        FormulaHelp("CONCAT","CONCAT(값, …)","문자열 연결","=CONCAT(\"문서\",\" 편집\")","문서 편집")
    )
    fun search(query: String): List<FormulaHelp> = functions.filter {
        query.isBlank() || it.name.contains(query.trim(),true) || it.description.contains(query.trim(),true)
    }
    private fun suffix(input: String): MatchResult? {
        if(!input.startsWith('=')) return null
        // A doubled quote stays within the same string literal.
        var quoted=false;var i=1
        while(i<input.length) {if(input[i]=='"') {if(quoted && i+1<input.length && input[i+1]=='"') i++ else quoted=!quoted};i++}
        if(quoted) return null
        return Regex("(?:^=|[+\\-*/^,(=<>])\\s*([A-Za-z][A-Za-z0-9._]*)$").find(input)
    }
    fun suggestions(input: String): List<FormulaHelp> = suffix(input)?.groupValues?.get(1)?.let {prefix -> functions.filter {it.name.startsWith(prefix,true)}} ?: emptyList()
    fun complete(input: String,name: String): String {
        require(functions.any {it.name==name})
        val suffix=suffix(input)
        return if(suffix!=null && name.startsWith(suffix.groupValues[1],true)) input.substring(0,suffix.groups[1]!!.range.first)+name+"("
        else if(input.isBlank() || input=="=") "=$name(" else input
    }
}
