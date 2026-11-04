package kr.documentstudio

import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebView
import kotlinx.coroutines.runBlocking
import kr.documentstudio.core.*
import kr.documentstudio.data.DocumentRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Checks actual merged Android assets and the native bridge, not a browser-only mock. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=StudioApplication::class)
class HangulAndroidHostTest {
    private fun request(url: String)=object: WebResourceRequest {
        override fun getUrl()=Uri.parse(url)
        override fun isForMainFrame()=false
        override fun isRedirect()=false
        override fun hasGesture()=false
        override fun getMethod()="GET"
        override fun getRequestHeaders()=emptyMap<String,String>()
    }
    @Test fun bundledDocumentEngineAndPrivateDocumentAreServedAtTheExactAndroidOrigin()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val repo=DocumentRepository(context);val session=repo.create(Format.HWPX);val working=repo.prepareOffice(session)
        val intent=Intent(context,HangulEditorActivity::class.java).putExtra("sessionId",session.id).putExtra("workingPath",working.path).putExtra("displayName",session.name)
        val controller=Robolectric.buildActivity(HangulEditorActivity::class.java,intent).create()
        try {
            val activity=controller.get()
            val field=HangulEditorActivity::class.java.getDeclaredField("browser").apply {isAccessible=true}
            val web=field.get(activity) as WebView
            val client=web.webViewClient
            val origin="https://hangul.documentstudio.local"
            val host=client.shouldInterceptRequest(web,request("$origin/"))!!
            assertEquals("text/html",host.mimeType)
            assertTrue(host.data.bufferedReader().readText().contains("chrome=embed"))
            val engine=client.shouldInterceptRequest(web,request("$origin/engine/index.html"))!!
            assertEquals("text/html",engine.mimeType)
            val html=engine.data.bufferedReader().readText()
            val module=Regex("src=\"(/engine/assets/[^\"]+\\.js)\"").find(html)!!.groupValues[1]
            val script=client.shouldInterceptRequest(web,request(origin+module))!!
            assertEquals("text/javascript",script.mimeType)
            assertTrue(script.data.readBytes().size>10000)
            assertArrayEquals(working.readBytes(),client.shouldInterceptRequest(web,request("$origin/document"))!!.data.readBytes())
            assertEquals(403,client.shouldInterceptRequest(web,request("https://outside.invalid/document"))!!.statusCode)
            assertEquals(403,client.shouldInterceptRequest(web,request("file:///data/data/kr.documentstudio/files/private"))!!.statusCode)

            val bridge=activity.Bridge();bridge.loaded()
            assertEquals("VIEW",org.json.JSONObject(bridge.describe()).getString("openIntent"))
            assertFalse(bridge.beginWrite(16))
            assertFalse(File(working.path+".edited").exists())
            assertTrue(bridge.setEditing(true))
            val before=working.readBytes();val changed=File(working.parentFile,"bridge-result.hwpx")
            val doc=PackageDocument.open(working);doc.write(changed,Edits(values=mapOf(doc.sections.first().fields.first().id to "한글 Android bridge")))
            val bytes=changed.readBytes()
            assertTrue(bridge.beginWrite(bytes.size))
            for(chunk in bytes.asList().chunked(49152)) assertTrue(bridge.writeChunk(Base64.encodeToString(chunk.toByteArray(),Base64.NO_WRAP)))
            val report="{\"schemaVersion\":1,\"outputFormat\":\"hwpx\",\"count\":0,\"losses\":[]}"
            assertTrue(org.json.JSONObject(bridge.finishWrite(report)).getBoolean("ok"))
            assertEquals("한글 Android bridge",PackageDocument.open(working).sections.first().fields.first().text)
            assertArrayEquals(before,session.base.readBytes())
            assertTrue(File(working.path+".edited").isFile)
            assertTrue(bridge.setEditing(false))
            assertFalse(bridge.beginWrite(3))
            assertTrue(bridge.setEditing(true))
            val valid=working.readBytes()
            assertTrue(bridge.beginWrite(3));assertTrue(bridge.writeChunk(Base64.encodeToString(byteArrayOf(1,2,3),Base64.NO_WRAP)))
            assertFalse(org.json.JSONObject(bridge.finishWrite(report)).getBoolean("ok"))
            assertArrayEquals(valid,working.readBytes())
        } finally {controller.destroy()}
    }
    @Test fun closingAnEarlierHostCannotKillANewerOpenDocument()=runBlocking {
        val context=RuntimeEnvironment.getApplication();val repo=DocumentRepository(context)
        val first=repo.create(Format.HWPX);val second=repo.create(Format.HWPX)
        val firstWorking=repo.prepareOffice(first);val secondWorking=repo.prepareOffice(second)
        fun intent(id: String,file: File)=Intent(context,HangulEditorActivity::class.java).putExtra("sessionId",id).putExtra("workingPath",file.path)
        org.robolectric.shadows.ShadowProcess.clearKilledProcesses()
        val older=Robolectric.buildActivity(HangulEditorActivity::class.java,intent(first.id,firstWorking)).create()
        val newer=Robolectric.buildActivity(HangulEditorActivity::class.java,intent(second.id,secondWorking)).create()
        var olderDestroyed=false
        try {
            older.get().finish();older.destroy();olderDestroyed=true
            assertFalse("Another live document must keep its engine process",org.robolectric.shadows.ShadowProcess.wasKilled(android.os.Process.myPid()))
            assertTrue(secondWorking.isFile)
        } finally {
            if(!olderDestroyed) older.destroy()
            newer.get().finish();newer.destroy()
        }
        assertTrue("The last closed host can release its engine process",org.robolectric.shadows.ShadowProcess.wasKilled(android.os.Process.myPid()))
    }
}
