package kr.documentstudio

import kr.documentstudio.data.*
import org.junit.Assert.*
import org.junit.Test

class UserProblemPrivacyTest {
    @Test fun arbitraryExceptionDetailsCannotReachTheUserMessage() {
        val secret="content://private/documents/계약서?token=private-secret"
        for(error in listOf(SecurityException(secret),java.io.FileNotFoundException(secret),IllegalArgumentException(secret),IllegalStateException(secret),java.io.IOException(secret))){
            val p=UserProblems.explain(error);assertFalse((p.title+p.advice+p.code).contains(secret));assertFalse((p.title+p.advice).contains("private-secret"))
        }
    }
    @Test fun wrappedOriginalConflictRetainsItsSpecificSafeRecoveryAdvice() {
        val p=UserProblems.explain(java.io.IOException("unsafe detail",OriginalChanged()))
        assertEquals("ORIGINAL_CHANGED",p.code);assertTrue(p.advice.contains("다른 이름"))
    }
}
