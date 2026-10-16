package kr.documentstudio.core

import org.junit.Assert.*
import org.junit.Test

class FormulaCatalogTest {
    @Test fun everyAdvertisedExampleIsEvaluated() {
        val cells=mapOf("A1" to "1","A2" to "2","A3" to "3","B1" to "10","B2" to "20","B3" to "30")
        assertEquals(16,FormulaCatalog.functions.size)
        FormulaCatalog.functions.forEach {help->assertEquals(help.name,help.result,Formula(cells+("D1" to help.example)).display("D1"))}
    }
    @Test fun completionPreservesExistingArgumentsAndQuotedText() {
        assertEquals("=SUM(",FormulaCatalog.complete("=su","SUM"))
        assertEquals("=IF(A1>0,SUM(",FormulaCatalog.complete("=IF(A1>0,su","SUM"))
        assertEquals("=SUM(A1)+ MAX(",FormulaCatalog.complete("=SUM(A1)+ ma","MAX"))
        for(input in listOf("text","=A1","=SUM(A1:A2)","=CONCAT(\"SU","=CONCAT(\"a\"\"SU")) {
            assertEquals(input,FormulaCatalog.complete(input,"SUM"));assertTrue(FormulaCatalog.suggestions(input).isEmpty())
        }
        assertEquals("=SUM(",FormulaCatalog.complete("","SUM"))
        assertEquals(listOf("SUM","SUMIF"),FormulaCatalog.suggestions("=su").map {it.name})
    }
    @Test fun searchMatchesNamesAndKoreanDescriptions() {
        assertEquals("AVERAGE",FormulaCatalog.search("평균").single().name)
        assertEquals("XLOOKUP",FormulaCatalog.search("xlookup").single().name)
        assertTrue(FormulaCatalog.search("없는 함수").isEmpty())
    }
}
