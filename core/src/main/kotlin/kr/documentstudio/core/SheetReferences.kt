package kr.documentstudio.core

/** A1 references are tokenized outside string literals and structured/external references. */
object SheetReferences {
    enum class Axis { ROW, COLUMN }
    data class Shift(val axis: Axis,val at: Int,val count: Int,val delete: Boolean) {
        val limit get()=if(axis==Axis.ROW) 1048576 else 16384
        init {require(at>=1 && count in 1..10000 && at.toLong()+count-1<=if(axis==Axis.ROW) 1048576 else 16384)}
        fun point(value: Int): Int? = when {value<at->value; !delete->value+count; value<at+count->null; else->value-count}
        fun interval(first: Int,last: Int): Pair<Int,Int>? {
            if(!delete) return (if(first>=at) first+count else first) to (if(last>=at) last+count else last)
            if(last<at) return first to last
            if(first>=at+count) return first-count to last-count
            val low=if(first<at) first else at
            val high=if(last>=at+count) last-count else at-1
            return if(low<=high) low to high else null
        }
    }
    private data class Address(val column: Int?,val row: Int?,val fixedColumn: Boolean,val fixedRow: Boolean) {
        fun text()=(column?.let {(if(fixedColumn) "$" else "")+columnName(it)} ?: "")+(row?.let {(if(fixedRow) "$" else "")+it} ?: "")
    }
    private val addressPattern=Regex("(\\$?)([A-Za-z]{1,3})(\\$?)([1-9][0-9]{0,6})|(\\$?)([A-Za-z]{1,3})|(\\$?)([1-9][0-9]{0,6})")
    private val reference=Regex("(?<![\\p{L}\\p{N}_.])(?:(('(?:[^']|'')+'|[\\p{L}_][\\p{L}\\p{N}_.]*)!)?)(\\$?[A-Za-z]{1,3}\\$?[1-9][0-9]{0,6}(?::\\$?[A-Za-z]{1,3}\\$?[1-9][0-9]{0,6})?|\\$?[A-Za-z]{1,3}:\\$?[A-Za-z]{1,3}|\\$?[1-9][0-9]{0,6}:\\$?[1-9][0-9]{0,6})(?![\\p{L}\\p{N}_.])")
    private fun parse(text: String): Address? {
        val m=addressPattern.matchEntire(text) ?: return null
        val a=if(m.groupValues[2].isNotEmpty()) Address(column(m.groupValues[2].uppercase()),m.groupValues[4].toInt(),m.groupValues[1].isNotEmpty(),m.groupValues[3].isNotEmpty())
            else if(m.groupValues[6].isNotEmpty()) Address(column(m.groupValues[6].uppercase()),null,m.groupValues[5].isNotEmpty(),false)
            else Address(null,m.groupValues[8].toInt(),false,m.groupValues[7].isNotEmpty())
        return a.takeIf {(it.column ?: 1) in 1..16384 && (it.row ?: 1) in 1..1048576}
    }
    private fun rewrite(formula: String,transform: (String?,Address,Address?)->String): String {
        val protected=BooleanArray(formula.length);var quoted=false;var sheetQuoted=false;var brackets=0;var i=0
        while(i<formula.length) {
            val ch=formula[i]
            if(!quoted && ch=='\'') {if(sheetQuoted && i+1<formula.length && formula[i+1]=='\'') {i+=2;continue};sheetQuoted=!sheetQuoted;i++;continue}
            if(sheetQuoted) {i++;continue}
            if(ch=='"') {protected[i]=true;if(quoted && i+1<formula.length && formula[i+1]=='"') {protected[i+1]=true;i+=2;continue};quoted=!quoted}
            else if(quoted) protected[i]=true
            else {if(ch=='[') brackets++;if(brackets>0) protected[i]=true;if(ch==']' && brackets>0) brackets--}
            i++
        }
        return reference.replace(formula) {m ->
            val prefix=m.groupValues[1];val name=m.groupValues[2].takeIf {it.isNotEmpty()}?.removeSurrounding("'")?.replace("''","'")
            val pieces=m.groupValues[3].split(':');val first=parse(pieces[0]);val last=pieces.getOrNull(1)?.let(::parse)
            val function=prefix.isEmpty() && formula.substring(m.range.last+1).trimStart().startsWith('(')
            if(m.range.any {protected[it]} || m.range.first>0 && formula[m.range.first-1] in "]!" || first==null || pieces.size==2 && last==null || function || name?.contains('[')==true) m.value
            else prefix+transform(name,first,last)
        }
    }
    fun copy(formula: String,rows: Int,columns: Int): String = rewrite(formula) {_,first,last ->
        fun move(a: Address): Address? {val r=a.row?.let {it+if(a.fixedRow) 0 else rows};val c=a.column?.let {it+if(a.fixedColumn) 0 else columns};return if((r ?: 1) !in 1..1048576 || (c ?: 1) !in 1..16384) null else a.copy(column=c,row=r)}
        val a=move(first);val b=last?.let(::move);if(a==null || last!=null && b==null) "#REF!" else a.text()+(b?.let {":"+it.text()} ?: "")
    }
    fun structural(formula: String,currentSheet: String?,targetSheet: String,shift: Shift): String {
        // A 3-D reference needs workbook-order interval handling, which this editor cannot safely infer.
        val outsideStrings=formula.replace(Regex("\"(?:[^\"]|\"\")*\""),"")
        require(!Regex("(?:'[^']*:[^']*'|[\\p{L}_][\\p{L}\\p{N}_.]*:[\\p{L}_][\\p{L}\\p{N}_.]*)!").containsMatchIn(outsideStrings)) {"3차원 시트 참조가 있는 문서의 행·열 구조 변경은 지원하지 않습니다."}
        return rewrite(formula) {name,first,last ->
            if(!(name ?: currentSheet).equals(targetSheet,true)) first.text()+(last?.let {":"+it.text()} ?: "")
            else {
                fun value(a: Address)=if(shift.axis==Axis.ROW) a.row else a.column
                fun set(a: Address,n: Int)=if(shift.axis==Axis.ROW) a.copy(row=n) else a.copy(column=n)
                val a=value(first);val b=last?.let(::value)
                if(a==null) first.text()+(last?.let {":"+it.text()} ?: "")
                else {
                    val interval=if(b!=null) {val low=minOf(a,b);val high=maxOf(a,b);shift.interval(low,high)?.let {if(a<=b) it else it.second to it.first}} else shift.point(a)?.let {it to it}
                    if(interval==null || interval.first !in 1..shift.limit || interval.second !in 1..shift.limit) "#REF!"
                    else set(first,interval.first).text()+(last?.let {":"+set(it,interval.second).text()} ?: "")
                }
            }
        }
    }
}
