package kr.documentstudio.core

import kotlin.math.pow
import kotlin.math.round

/** Small, explicit local evaluator; unsupported Excel functions remain intact on disk. */
class Formula(private val cells: Map<String, String>,private val sheets: Map<String,Map<String,String>> = emptyMap(),private val currentSheet: String?=null,private val date1904: Boolean=false) {
    private data class Range(val rows: Int,val columns: Int,val values: List<Any>)
    private val active = mutableSetOf<String>()
    private var steps = 0
    private var comparisonWork=0
    fun display(address: String): String {
        steps = 0
        comparisonWork=0
        return try { val value = cell(address); when (value) {
            is Double -> if(value.isFinite()) printable(value) else "#NUM!"
            else -> value.toString()
        } } catch (e: Exception) { e.message?.takeIf { it.startsWith('#') } ?: "#VALUE!" }
    }
    private fun cell(address: String,sheet: String?=currentSheet): Any {
        require(++steps < 10000) { "#LIMIT!" }
        val key=(sheet ?: "").lowercase()+"!"+address
        require(active.size < 100 && active.add(key)) { "#CYCLE!" }
        try {
            val data=if(sheet==null || sheet.equals(currentSheet,true)) cells else sheets.entries.firstOrNull {it.key.equals(sheet,true)}?.value ?: error("#REF!")
            val value = data[address] ?: ""
            val result=if(value.startsWith('=')) Parser(value.drop(1),sheet).parse() else value.toDoubleOrNull() ?: value
            require(result !is String || result.length<=32767) {"#LIMIT!"};return result
        } finally { active.remove(key) }
    }
    private fun number(value: Any): Double = when(value) { is Double -> value; is Boolean -> if (value) 1.0 else 0.0; "" -> 0.0; else -> value.toString().toDoubleOrNull() ?: error("#VALUE!") }
    private fun printable(value: Any): String = when(value) {is Double -> if(value.isFinite()) java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString() else "#NUM!";is Boolean -> if(value) "TRUE" else "FALSE";else -> value.toString()}
    private fun flatten(value: Any)=if(value is Range) value.values else listOf(value)
    private fun equal(a: Any,b: Any)=if(a is String && b is String) a.equals(b,true) else a==b
    private fun criterion(value: Any,criteria: Any): Boolean {
        val s=printable(criteria)
        val op=Regex("^(>=|<=|<>|=|>|<)").find(s)?.value ?: "="
        val literal=if(s.startsWith(op) && criteria is String) s.drop(op.length) else s
        val n=literal.toDoubleOrNull()
        if(n!=null) {
            val v=(value as? Double) ?: return false
            return when(op) {">=" -> v>=n;"<="->v<=n;">"->v>n;"<"->v<n;"<>"->v!=n;else->v==n}
        }
        val pattern=mutableListOf<Int>();var escaped=false
        literal.forEach { ch ->
            if(escaped) {pattern.add(ch.code);escaped=false}
            else when(ch) {'~'->escaped=true;'*'->if(pattern.lastOrNull()!=-1) pattern.add(-1);'?'->pattern.add(-2);else->pattern.add(ch.code)}
        };if(escaped) pattern.add('~'.code)
        val text=printable(value);var p=0;var t=0;var star=-1;var checkpoint=0
        var match=true
        while(t<text.length) {
            require(++comparisonWork<=1_000_000) {"#LIMIT!"}
            when {p<pattern.size && (pattern[p]==-2 || pattern[p]>=0 && pattern[p].toChar().equals(text[t],true))->{p++;t++}
                p<pattern.size && pattern[p]==-1->{star=p++;checkpoint=t}
                star>=0->{p=star+1;t=++checkpoint}
                else->{match=false;break}}
        }
        while(p<pattern.size && pattern[p]==-1) p++
        match=match && p==pattern.size
        return if(op=="<>") !match else match
    }
    private inner class Parser(source: String,private val sheet: String?=currentSheet) {
        private val matches = Regex("(?:'(?:[^']|'')+'|[\\p{L}_][\\p{L}\\p{N}_.]*)!|\\\"(?:[^\\\"]|\\\"\\\")*\\\"|\\$?[A-Za-z]+\\$?[0-9]+|[A-Za-z_][A-Za-z_0-9]*|(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?|>=|<=|<>|[+*/^(),:=<>-]")
            .findAll(source).toList()
        private val tokens = matches.map {it.value}
        private var position = 0
        init {
            var end=0
            matches.forEach {m -> require(source.substring(end,m.range.first).isBlank()) {"#NAME?"};end=m.range.last+1}
            require(source.substring(end).isBlank()) {"#NAME?"}
            require(tokens.size <= 512) { "#LIMIT!" }
        }
        private fun peek() = tokens.getOrNull(position)
        private fun take() = tokens.getOrNull(position++) ?: error("#VALUE!")
        fun parse(): Any { val result = compare(); require(position == tokens.size) { "#VALUE!" }; return result }
        private fun compare(): Any {
            var a = add()
            while (peek() in listOf("=", "<>", ">", "<", ">=", "<=")) {
                val op = take(); val b = add()
                a = when(op) { "=" -> equal(a,b); "<>" -> !equal(a,b); ">" -> number(a) > number(b); "<" -> number(a) < number(b); ">=" -> number(a) >= number(b); else -> number(a) <= number(b) }
            }; return a
        }
        private fun add(): Any { var a = multiply(); while (peek() in listOf("+", "-")) { val op = take(); val b = multiply(); a = if (op == "+") number(a) + number(b) else number(a) - number(b) }; return a }
        private fun multiply(): Any { var a = power(); while (peek() in listOf("*", "/")) { val op = take(); val b = number(power()); a = if(op == "*") number(a) * b else { require(b != 0.0) { "#DIV/0!" }; number(a) / b } }; return a }
        private fun power(): Any { var a = atom(); if (peek() == "^") { take(); a = number(a).pow(number(power())) }; return a }
        private fun atom(): Any {
            var t = take();var referenceSheet=sheet
            if(t.endsWith('!')) {referenceSheet=t.dropLast(1).removeSurrounding("'").replace("''","'");t=take();require(validAddress(t.replace("$","").uppercase())) {"#REF!"}}
            if (t == "+") return number(atom())
            if (t == "-") return -number(atom())
            if (t == "(") { val v = compare(); require(take() == ")"); return v }
            t.toDoubleOrNull()?.let { return it }
            if (t.startsWith('"')) return t.drop(1).dropLast(1).replace("\"\"", "\"")
            if (t.uppercase() in listOf("TRUE", "FALSE")) return t.uppercase() == "TRUE"
            val ref = t.replace("$", "").uppercase()
            if (validAddress(ref)) {
                if (peek() != ":") return cell(ref,referenceSheet)
                take(); val end = take().replace("$", "").uppercase(); require(validAddress(end)) { "#REF!" }
                val firstRow = ref.dropWhile { it.isLetter() }.toInt(); val lastRow = end.dropWhile { it.isLetter() }.toInt()
                require(lastRow >= firstRow && column(end) >= column(ref) && (lastRow.toLong()-firstRow+1)*(column(end)-column(ref)+1) <= 10000) { "#LIMIT!" }
                return Range(lastRow-firstRow+1,column(end)-column(ref)+1,(firstRow..lastRow).flatMap { r -> (column(ref)..column(end)).map { c -> cell(columnName(c) + r,referenceSheet) } })
            }
            require(take() == "(") { "#NAME?" }
            if(t.uppercase() in listOf("IF","IFS")) {
                // Parse all three expressions but evaluate only the selected branch.
                val parts=mutableListOf<String>();var depth=0;var start=position
                while(true) {
                    val token=peek() ?: error("#VALUE!")
                    if(token==")" && depth==0) {parts.add(tokens.subList(start,position).joinToString(""));position++;break}
                    if(token=="," && depth==0) {parts.add(tokens.subList(start,position).joinToString(""));start=position+1}
                    if(token=="(") depth++ else if(token==")") depth--
                    position++
                }
                if(t.uppercase()=="IF") {
                    require(parts.size in 2..3) {"#VALUE!"}
                    val condition=Parser(parts[0],sheet).parse()
                    val branch=if(condition==true || condition is Double && condition!=0.0) 1 else 2
                    return if(branch>=parts.size) false else Parser(parts[branch],sheet).parse()
                }
                require(parts.size%2==0) {"#VALUE!"}
                for(i in parts.indices step 2) {val condition=Parser(parts[i],sheet).parse();if(condition==true || condition is Double && condition!=0.0) return Parser(parts[i+1],sheet).parse()}
                error("#N/A")
            }
            val args = mutableListOf<Any>()
            if (peek() != ")") { args.add(compare()); while(peek() == ",") { take(); args.add(compare()) } }
            require(take() == ")") { "#VALUE!" }
            val flat = args.flatMap(::flatten)
            val nums = flat.filterIsInstance<Double>()
            return when (t.uppercase()) {
                "SUM" -> nums.sum(); "COUNT" -> nums.size.toDouble()
                "AVERAGE" -> { require(nums.isNotEmpty()) { "#DIV/0!" }; nums.average() }
                "MIN" -> nums.minOrNull() ?: 0.0; "MAX" -> nums.maxOrNull() ?: 0.0
                "ROUND" -> { require(args.size == 2); val factor = 10.0.pow(number(args[1])); val n = number(args[0])*factor; kotlin.math.sign(n)*kotlin.math.floor(kotlin.math.abs(n)+0.5)/factor }
                "IF" -> { require(args.size == 3); if (args[0] == true || args[0] is Double && number(args[0]) != 0.0) args[1] else args[2] }
                "CONCAT" -> StringBuilder().apply {flat.forEach {value->val text=printable(value);require(length+text.length<=32767) {"#LIMIT!"};append(text)}}.toString()
                "COUNTIF" -> {require(args.size==2);flatten(args[0]).count {criterion(it,args[1])}.toDouble()}
                "SUMIF" -> {
                    require(args.size in 2..3);val range=flatten(args[0]);val sum=flatten(args.getOrElse(2){args[0]});require(range.size==sum.size) {"#VALUE!"}
                    range.indices.filter {criterion(range[it],args[1])}.sumOf {(sum[it] as? Double) ?: 0.0}
                }
                "INDEX" -> {
                    require(args.size in 2..3);val range=args[0] as? Range ?: error("#VALUE!");val row=number(args[1]).toInt();val col=if(args.size==3) number(args[2]).toInt() else 1
                    require(row in 1..range.rows && col in 1..range.columns) {"#REF!"};range.values[(row-1)*range.columns+col-1]
                }
                "MATCH" -> {
                    require(args.size in 2..3);val range=flatten(args[1]);val mode=if(args.size==3) number(args[2]).toInt() else 1
                    val index=when(mode) {0->range.indexOfFirst {criterion(it,args[0])};1->range.indexOfLast {(it as? Double)?.let {n->n<=number(args[0])}==true};-1->range.indexOfLast {(it as? Double)?.let {n->n>=number(args[0])}==true};else->error("#VALUE!")}
                    require(index>=0) {"#N/A"};(index+1).toDouble()
                }
                "VLOOKUP" -> {
                    require(args.size in 3..4);val table=args[1] as? Range ?: error("#VALUE!");val col=number(args[2]).toInt();require(col in 1..table.columns) {"#REF!"}
                    val exact=args.getOrNull(3)==false || args.getOrNull(3)==0.0
                    val row=if(exact) (0 until table.rows).firstOrNull {criterion(table.values[it*table.columns],args[0])} else (0 until table.rows).lastOrNull {(table.values[it*table.columns] as? Double)?.let {n->n<=number(args[0])}==true}
                    require(row!=null) {"#N/A"};table.values[row*table.columns+col-1]
                }
                "XLOOKUP" -> {
                    require(args.size in 3..6);val keys=flatten(args[1]);val values=flatten(args[2]);require(keys.size==values.size) {"#VALUE!"}
                    val mode=args.getOrNull(4)?.let(::number)?.toInt() ?: 0;val search=args.getOrNull(5)?.let(::number)?.toInt() ?: 1
                    require(mode in listOf(-1,0,1,2) && search in listOf(-1,1)) {"#NAME?"}
                    val indices=if(search==-1) keys.indices.reversed() else keys.indices.toList()
                    var index=indices.firstOrNull {if(mode==2) criterion(keys[it],args[0]) else equal(keys[it],args[0])}
                    if(index==null && mode in listOf(-1,1)) {val lookup=number(args[0]);val candidates=indices.filter {keys[it] is Double && if(mode==-1) number(keys[it])<lookup else number(keys[it])>lookup};index=if(mode==-1) candidates.maxByOrNull {number(keys[it])} else candidates.minByOrNull {number(keys[it])}}
                    if(index==null) args.getOrNull(3) ?: error("#N/A") else values[index]
                }
                "TEXT" -> {
                    require(args.size==2);val pattern=args[1].toString()
                    require(pattern.length<=120 && pattern.none {it<' '}) {"#VALUE!"}
                    CellFormatting.format(printable(number(args[0])),pattern,date1904)
                }
                else -> error("#NAME?")
            }
        }
    }
}
