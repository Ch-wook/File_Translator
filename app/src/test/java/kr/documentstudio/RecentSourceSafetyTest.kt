package kr.documentstudio

import android.net.Uri
import kr.documentstudio.core.*
import kr.documentstudio.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=StudioApplication::class)
class RecentSourceSafetyTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private fun original()=File.createTempFile("recent-source-",".txt",context.cacheDir).apply {writeText("원본")}
    @Test fun cleanRecentReadsAnExternalChangeWithoutDestroyingThePreviousBaseline()=runBlocking {
        val file=original();val repo=DocumentRepository(context);val first=repo.open(Uri.fromFile(file))
        file.writeText("외부 앱의 변경");val reopened=repo.reopenRecent(first.id)
        assertEquals("외부 앱의 변경",reopened.edits.text);assertFalse(reopened.dirty)
        assertEquals("원본",first.base.readText());assertNotEquals(first.base,reopened.base)
    }
    @Test fun dirtyRecentAlwaysKeepsLatestUnsavedEditsWhenTheOriginalChanges()=runBlocking {
        val file=original();val repo=DocumentRepository(context);val first=repo.open(Uri.fromFile(file))
        val dirty=first.copy(edits=first.edits.copy(text="앱의 미저장 한글"));repo.checkpoint(dirty);file.writeText("외부의 새 내용")
        assertEquals(dirty.edits,repo.reopenRecent(first.id).edits);assertEquals("외부의 새 내용",file.readText())
    }
    @Test fun missingOriginalFallsBackToAClearlyIdentifiedLocalCopy()=runBlocking {
        val file=original();val repo=DocumentRepository(context);val first=repo.open(Uri.fromFile(file));assertTrue(file.delete())
        val reopened=repo.reopenRecent(first.id);assertEquals("원본",reopened.edits.text)
        assertNotNull(reopened.recoveryWarning);assertFalse(file.exists())
    }
    @Test fun hidingAndRestoringARecentNeverDeletesItsRecoveryOrOriginal()=runBlocking {
        val file=original();val repo=DocumentRepository(context);val first=repo.open(Uri.fromFile(file))
        repo.hideRecent(first.id,true);assertFalse(repo.recents().any {it.id==first.id})
        assertTrue(repo.recents(true).first {it.id==first.id}.hidden)
        assertTrue(file.isFile);assertTrue(first.base.isFile)
        repo.hideRecent(first.id,false);assertTrue(repo.recents().any {it.id==first.id})
    }
    @Test fun directAndTreeDocumentAliasesCannotBypassOriginalProtection() {
        assertTrue(DocumentUris.same("content://documents/document/primary%3ADocuments%2Fcontract.hwpx",
            "content://documents/tree/primary%3ADocuments/document/primary%3ADocuments%2Fcontract.hwpx"))
        assertFalse(DocumentUris.same("content://documents/document/one","content://documents/document/two"))
        assertFalse(DocumentUris.same("content://other/document/one","content://documents/document/one"))
    }
}
