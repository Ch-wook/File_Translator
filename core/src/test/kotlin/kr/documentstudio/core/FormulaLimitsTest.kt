package kr.documentstudio.core

import org.junit.Assert.*
import org.junit.Test

class FormulaLimitsTest {
    @Test fun wildcardCriteriaHandleEscapesAndCase() {
        val f=Formula(mapOf("A1" to "Alpha", "A2" to "a*", "A3" to "ab", "B1" to "=COUNTIF(A1:A3,\"A*\")", "B2" to "=COUNTIF(A1:A3,\"a~*\")", "B3" to "=COUNTIF(A1:A3,\"a?\")"))
        assertEquals("3",f.display("B1"));assertEquals("1",f.display("B2"));assertEquals("2",f.display("B3"))
    }
    @Test(timeout=3000) fun adversarialWildcardHasBoundedWork() {
        val f=Formula(mapOf("A1" to "a".repeat(32767), "B1" to "=COUNTIF(A1,\"*${"a".repeat(200)}b\")"))
        assertEquals("#LIMIT!",f.display("B1"))
        // Work counters reset after an expensive evaluation.
        assertEquals("a".repeat(32767),f.display("A1"))
    }
    @Test fun textFormatsDatesAndWorkbookEpoch() {
        assertEquals("2024-01-01",Formula(mapOf("A1" to "=TEXT(45292,\"yyyy-mm-dd\")")).display("A1"))
        assertEquals("1904-01-02",Formula(mapOf("A1" to "=TEXT(1,\"yyyy-mm-dd\")"),date1904=true).display("A1"))
        assertEquals("12.5%",Formula(mapOf("A1" to "=TEXT(0.125,\"0.0%\")")).display("A1"))
    }
}
