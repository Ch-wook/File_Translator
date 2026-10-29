package kr.documentstudio.core

import org.junit.Assert.*
import org.junit.Test

class DocumentModeTest {
    @Test fun unknownAbsentAndMalformedOpenIntentsRemainViewOnly() {
        for(value in listOf(null,"","view","edit","EDITOR","DELETE"," READ ","VIEW")) assertEquals(OpenIntent.VIEW,OpenIntent.parse(value))
        assertEquals(OpenIntent.EDIT,OpenIntent.parse("EDIT"))
    }
    @Test fun onlyEditingPresentationAuthorizesMutation() {
        assertEquals(listOf(PresentationState.EDITING),PresentationState.entries.filter {it.allowsMutation})
    }
}
