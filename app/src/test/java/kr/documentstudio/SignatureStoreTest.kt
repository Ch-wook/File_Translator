package kr.documentstudio

import android.graphics.Bitmap
import android.util.Base64
import kr.documentstudio.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=StudioApplication::class)
class SignatureStoreTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private val current get()=File(context.filesDir,"signatures.json")
    @Before fun clean(){listOf("signatures.json","signatures.json.bak","signatures-last-good.json","signature-recovery").forEach {File(context.filesDir,it).deleteRecursively()}}
    private fun value(id: String="one"): String {
        val bitmap=Bitmap.createBitmap(16,8,Bitmap.Config.ARGB_8888);bitmap.eraseColor(android.graphics.Color.BLUE)
        val bytes=ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes);bitmap.recycle()
        return JSONArray().put(JSONObject().put("id",id).put("name","내 서명").put("w",16).put("h",8)
            .put("png","data:image/png;base64,"+Base64.encodeToString(bytes.toByteArray(),Base64.NO_WRAP)).put("strokes",JSONArray())).toString()
    }
    @Test fun realTransparentPngAndLibraryMetadataSurviveReopening() {
        val expected=value();SignatureStore(context).write(expected);assertEquals(expected,SignatureStore(context).read())
    }
    @Test fun damagedLibraryIsPreservedAndCannotBeOverwrittenAsAnEmptyLibrary() {
        val store=SignatureStore(context);store.write(value());current.writeText("truncated private data")
        assertThrows(SignatureStoreUnavailable::class.java){store.read()}
        assertThrows(SignatureStoreUnavailable::class.java){store.write("[]")}
        assertEquals("truncated private data",current.readText())
        assertEquals(1,File(context.filesDir,"signature-recovery").listFiles()!!.size)
    }
    @Test fun explicitPreviousRestoreKeepsTheDamagedSnapshotAndOldValidLibrary() {
        val store=SignatureStore(context);val old=value();store.write(old);store.write(value("two"));current.writeText("damaged")
        assertTrue(store.canRestorePrevious());store.restorePrevious();assertEquals(old,store.read())
        assertTrue(File(context.filesDir,"signature-recovery").listFiles()!!.any {it.readText()=="damaged"})
    }
    @Test fun duplicateIdsAndTruncatedPngDoNotChangeTheExistingLibrary() {
        val store=SignatureStore(context);val expected=value();store.write(expected)
        val array=JSONArray(expected);array.put(array.getJSONObject(0))
        assertThrows(IllegalArgumentException::class.java){store.write(array.toString())}
        val bad=JSONArray(expected);bad.getJSONObject(0).put("png","data:image/png;base64,iVBORw0KGgo=")
        assertThrows(IllegalArgumentException::class.java){store.write(bad.toString())}
        assertEquals(expected,store.read())
    }
    @Test fun recoveryArchiveContainsThePreviousAndDamagedDataWithoutChangingThem() {
        val store=SignatureStore(context);store.write(value());store.write(value("two"));current.writeText("corrupt")
        assertThrows(SignatureStoreUnavailable::class.java){store.read()}
        java.util.zip.ZipFile(store.recoveryArchive()).use {zip->
            assertNotNull(zip.getEntry("current.json"));assertNotNull(zip.getEntry("previous.json"))
            assertEquals(3,zip.size())
        }
        assertEquals("corrupt",current.readText())
    }
    @Test fun atomicLibraryBackupIsRecoveredBeforeValidation() {
        val store=SignatureStore(context);val expected=value();store.write(expected)
        assertTrue(current.renameTo(File(current.path+".bak")))
        assertEquals(expected,store.read())
    }
}
